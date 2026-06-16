"""Answer-generation harness for B3.4 doc-ingestion answer rows.

Reads retrieval export rows (from Phase 10a or live Java exports), calls the
configured LLM in the requested answer mode(s), and writes answer rows
conforming to the 10d-B2/B3.4 answer schema.

For a smoke test the harness can be fed with fixture retrieval rows; for
accepted-size runs it reads the real export dataset root.
"""

from __future__ import annotations

import json
import hashlib
import os
import time
from collections.abc import Mapping, Sequence
from contextlib import suppress
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import SAFE_RUN_ID, build_manifest, write_json_artifact, write_run_artifacts

ARTIFACT_FILES = ("manifest.json", "samples.jsonl", "failures.jsonl", "report.json")
DEFAULT_ZHIPUAI_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
DEFAULT_ZAI_CODING_BASE_URL = "https://api.z.ai/api/coding/paas/v4"

# ── Control-mode labels ─────────────────────────────────────────────────────

NO_RAG = "no-rag"
FULL_RAG = "full-rag"
WRONG_CONTEXT = "wrong-context"
ORACLE_CONTEXT = "oracle-context"
RERANKER_OFF = "reranker-off"
RERANKER_ON = "reranker-on"
LEGACY_CONTROL_MATRIX_MODES = (NO_RAG, FULL_RAG, WRONG_CONTEXT, ORACLE_CONTEXT, RERANKER_OFF, RERANKER_ON)
DEFAULT_CONTROL_MODES = (FULL_RAG,)
SUPPORTED_CONTROL_MODES = set(LEGACY_CONTROL_MATRIX_MODES)

PROMPT_SYSTEM = (
    "You are a helpful assistant. Answer the user's question accurately and concisely. "
    "When context is provided, base your answer only on that context and give the shortest answer "
    "that fully answers the question. Prefer the exact requested value, name, date, or phrase. "
    "Do not add prefaces, explanations, citations, source descriptions, or facts that were not asked for. "
    "When no context is provided, answer from your own knowledge and say so."
)


@dataclass(frozen=True)
class AnswerHarnessConfig:
    run_id: str
    mode: str = "doc-ingestion-answer-smoke"
    dataset_id: str = "doc-ingestion-retrieval-v1"
    control_modes: tuple[str, ...] = DEFAULT_CONTROL_MODES
    llm_provider: str = "deepseek"
    llm_model: str = "deepseek-chat"
    llm_base_url: str | None = None
    llm_api_key: str | None = None
    llm_temperature: float = 0.0
    llm_max_tokens: int = 1024
    max_samples: int | None = None
    splits: tuple[str, ...] = ("calibration", "development")
    final_top_k: int = 8
    context_token_budget: int = 6000
    dataset_hash: str | None = None
    split_hashes: tuple[tuple[str, str], ...] = ()
    source_artifact: str | None = None
    git_branch: str = "unknown"
    git_sha: str = "unknown"
    # Injected for testability
    _generate: Any | None = None

    def __post_init__(self) -> None:
        if not SAFE_RUN_ID.fullmatch(self.run_id):
            raise ValueError(f"unsafe run id: {self.run_id}")
        if not self.control_modes:
            raise ValueError("at least one control mode is required")
        for mode in self.control_modes:
            if mode not in SUPPORTED_CONTROL_MODES:
                raise ValueError(f"unknown control mode: {mode}")
        if self.max_samples is not None and self.max_samples < 1:
            raise ValueError("max_samples must be positive or None")
        if self.final_top_k < 1:
            raise ValueError("final_top_k must be positive")
        if self.context_token_budget < 1:
            raise ValueError("context_token_budget must be positive")

    def as_dict(self) -> dict[str, Any]:
        return {
            "datasetId": self.dataset_id,
            "controlModes": list(self.control_modes),
            "llmProvider": self.llm_provider,
            "llmModel": self.llm_model,
            "llmTemperature": self.llm_temperature,
            "llmMaxTokens": self.llm_max_tokens,
            "maxSamples": self.max_samples,
            "splits": list(self.splits),
            "finalTopK": self.final_top_k,
            "contextTokenBudget": self.context_token_budget,
            "datasetHash": self.dataset_hash,
            "splitHashes": dict(self.split_hashes),
            "sourceArtifact": self.source_artifact,
        }


def run_answer_harness(
    *,
    rows: Sequence[Mapping[str, Any]],
    output_root: Path,
    config: AnswerHarnessConfig,
) -> Path:
    """Generate answer rows in all control modes and write run artifacts.

    ``rows`` are retrieval export records — they must have ``userInput``,
    ``referenceContextIds``, and ``metadata.retrievedContexts`` (or
    ``metadata.referenceContent`` for Oracle-context).

    Returns the output run directory.
    """
    rows = _filter_rows(rows, config)
    if not rows:
        raise ValueError("no rows to generate answers for")

    config_dict = config.as_dict()
    fingerprint = config_fingerprint(config_dict)
    generate = config._generate or _generate_answer

    checkpoint_dir, checkpoint_samples = _checkpoint_paths(output_root, config.run_id)
    generated = _load_or_create_checkpoint(
        checkpoint_dir=checkpoint_dir,
        checkpoint_samples=checkpoint_samples,
        run_id=config.run_id,
        config_fingerprint=fingerprint,
    )
    completed_sample_ids = {str(row.get("sampleId") or "") for row in generated}
    failures: list[dict[str, Any]] = []
    shuffled_contexts = _shuffled_contexts(rows)

    for index, row in enumerate(rows):
        for mode in config.control_modes:
            answer_sample_id = f"{row.get('sampleId', '')}-{mode}"
            if answer_sample_id in completed_sample_ids:
                continue
            try:
                answer_row = _generate_row(
                    row=row,
                    mode=mode,
                    shuffled_contexts=shuffled_contexts,
                    index=index,
                    config=config,
                    generate=generate,
                )
                generated.append(answer_row)
                completed_sample_ids.add(answer_sample_id)
                _append_checkpoint_row(checkpoint_samples, answer_row)
            except Exception as exc:
                failures.append(
                    {
                        "sampleId": str(row.get("sampleId") or ""),
                        "controlMode": mode,
                        "errorCategory": _error_category(exc),
                        "message": str(exc)[:500],
                    }
                )

    status = "warn" if failures else "pass"
    manifest = build_manifest(
        run_id=config.run_id,
        suite="doc-ingestion-rag-effectiveness",
        mode=config.mode,
        timestamp=datetime.now(timezone.utc).isoformat(),
        git_branch=config.git_branch,
        git_sha=config.git_sha,
        dataset_id=config.dataset_id,
        dataset_hash=config.dataset_hash or _rows_hash(rows),
        config=config_dict,
        config_fingerprint=fingerprint,
        models={"answerProvider": config.llm_provider, "answerModel": config.llm_model},
        artifact_files=ARTIFACT_FILES,
    )
    metrics = {
        "status": status,
        "generatedRows": len(generated),
        "failedRows": len(failures),
        "controlModes": list(config.control_modes),
        "perMode": {mode: sum(1 for r in generated if _control_mode(r) == mode) for mode in config.control_modes},
    }
    run_dir = write_run_artifacts(output_root, manifest, metrics, generated, failures)
    write_json_artifact(run_dir / "report.json", {
        "runId": config.run_id,
        "suite": "doc-ingestion-rag-effectiveness",
        "mode": config.mode,
        "status": status,
        "datasetId": config.dataset_id,
        "datasetHash": config.dataset_hash or _rows_hash(rows),
        "configFingerprint": fingerprint,
        "metrics": metrics,
        "thresholdResults": [],
    })
    _remove_checkpoint(checkpoint_dir, checkpoint_samples)
    return run_dir


# ── Internal: per-row generation ────────────────────────────────────────────


def _generate_row(
    *,
    row: Mapping[str, Any],
    mode: str,
    shuffled_contexts: Sequence[Mapping[str, Any]],
    index: int,
    config: AnswerHarnessConfig,
    generate: Any,
) -> dict[str, Any]:
    metadata = dict(row.get("metadata") or {})
    query = str(row.get("userInput") or "")
    reference_content = str(metadata.get("referenceContent") or "")
    reference_answer = str(metadata.get("referenceAnswer") or reference_content)
    contexts = _context_list(row, metadata)
    context_texts = [_context_text(ctx) for ctx in contexts]

    # Select context per control mode
    if mode == NO_RAG:
        selected_contexts: list[dict[str, Any]] = []
        selected_texts: list[str] = []
    elif mode == FULL_RAG:
        selected_contexts = _limit_contexts(contexts, config.final_top_k)
        if not selected_contexts:
            raise ValueError("full-rag control requires retrieved/reranked contexts")
        selected_texts = list(context_texts)
    elif mode == RERANKER_ON:
        selected_contexts = _limit_contexts(contexts, config.final_top_k)
        if not selected_contexts:
            raise ValueError("reranker-on control requires retrieved/reranked contexts")
        selected_texts = [_context_text(ctx) for ctx in selected_contexts]
    elif mode == RERANKER_OFF:
        selected_contexts = _candidate_context_list(metadata, config.final_top_k)
        if not selected_contexts:
            raise ValueError("reranker-off control requires candidateContexts")
        selected_texts = [_context_text(ctx) for ctx in selected_contexts]
    elif mode == WRONG_CONTEXT:
        swapped = shuffled_contexts[index % len(shuffled_contexts)]
        if not swapped:
            raise ValueError("wrong-context control requires a disjoint source-group context")
        selected_contexts = list(swapped)
        selected_texts = [_context_text(ctx) for ctx in swapped]
    elif mode == ORACLE_CONTEXT:
        if reference_content:
            oracle_ctx = {"id": "oracle", "chunkId": "oracle", "text": reference_content}
            selected_contexts = [oracle_ctx]
            selected_texts = [reference_content]
        else:
            selected_contexts = []
            selected_texts = []
    else:
        raise ValueError(f"unknown control mode: {mode}")

    original_contexts = selected_contexts
    selected_contexts = _apply_context_budget(selected_contexts, config.context_token_budget)
    selected_texts = [_context_text(ctx) for ctx in selected_contexts]
    prompt = _build_prompt(query, _prompt_contexts(selected_contexts), mode)
    t0 = time.perf_counter()
    response_text, token_counts = generate(prompt, config)
    elapsed_ms = (time.perf_counter() - t0) * 1000.0
    if not str(response_text or "").strip():
        raise ValueError("LLM returned empty response")
    retrieval_ms = _control_retrieval_latency_ms(metadata, mode)
    token_counts = dict(token_counts or {})
    token_counts.setdefault("retrievedContextTokens", _approx_token_count(selected_texts))

    # Build RAGAS-compatible top-level retrievedContexts
    ragas_contexts = [
        {
            "id": str(ctx.get("id") or ctx.get("chunkId") or f"ctx-{i}"),
            "chunkId": str(ctx.get("chunkId") or ctx.get("id") or ""),
            "documentId": str(ctx.get("documentId") or ""),
            "text": _context_text(ctx),
            "score": float(ctx.get("score") or 0.0),
        }
        for i, ctx in enumerate(selected_contexts)
    ]

    answer_metadata = {
        "format": str(metadata.get("format") or "unknown"),
        "referenceContent": reference_content,
        "referenceAnswer": reference_answer,
        "generationMethod": metadata.get("generationMethod"),
        "questionProvenance": metadata.get("questionProvenance"),
        "retrievedContexts": list(contexts),  # original retrieval contexts
        "candidateContexts": metadata.get("candidateContexts"),
        "referenceContexts": [
            {"id": reference_id, "text": reference_content}
            for reference_id in row.get("referenceContextIds") or []
        ],
        "sourceSampleId": str(row.get("sampleId") or ""),
        "controlRun": {"mode": mode, "rerankerEnabled": _reranker_enabled(mode)},
        "citations": _extract_citations(response_text, selected_contexts),
        "latency": {
            "retrievalMs": retrieval_ms,
            "answerMs": elapsed_ms,
            "totalMs": retrieval_ms + elapsed_ms,
        },
        "retrievalLatency": metadata.get("retrievalLatency"),
        "retrievalProvenance": metadata.get("retrievalProvenance"),
        "tokenCounts": token_counts,
        "contextBudget": {
            "budgetTokens": config.context_token_budget,
            "originalApproxTokens": _approx_token_count([_context_text(ctx) for ctx in original_contexts]),
            "includedApproxTokens": _approx_token_count(selected_texts),
            "originalContextCount": len(original_contexts),
            "includedContextCount": len(selected_contexts),
            "truncated": selected_contexts != list(original_contexts),
        },
        "answerProvider": {"provider": config.llm_provider, "modelName": config.llm_model},
        # Copy through provenance metadata
        "sourceUrl": metadata.get("sourceUrl"),
        "sourceSha256": metadata.get("sourceSha256"),
        "sourceGroup": metadata.get("sourceGroup"),
        "knowledgeBaseId": metadata.get("knowledgeBaseId"),
        "referenceDocId": metadata.get("referenceDocId"),
        "referenceDocFilename": metadata.get("referenceDocFilename"),
        "parser": metadata.get("parser"),
        "chunker": metadata.get("chunker"),
        "embeddingModel": metadata.get("embeddingModel"),
        "embeddingProvider": metadata.get("embeddingProvider"),
        "mqEnabled": metadata.get("mqEnabled"),
        "mineruEnabled": metadata.get("mineruEnabled"),
        "mq": metadata.get("mq"),
        "mineru": metadata.get("mineru"),
    }

    return {
        "sampleId": f"{row.get('sampleId', '')}-{mode}",
        "datasetId": str(row.get("datasetId") or config.dataset_id),
        "sourceGroupId": str(row.get("sourceGroupId") or ""),
        "split": str(row.get("split") or "calibration"),
        "userInput": query,
        "response": response_text,
        "reference": reference_answer,
        "referenceContextIds": list(row.get("referenceContextIds") or []),
        "retrievedContexts": ragas_contexts,
        "metadata": {k: v for k, v in answer_metadata.items() if v is not None},
    }


# ── Context helpers ─────────────────────────────────────────────────────────


def _context_list(row: Mapping[str, Any], metadata: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    """Extract contexts from either top-level RAGAS format or metadata Java format."""
    contexts = row.get("retrievedContexts")
    if isinstance(contexts, list) and contexts:
        return [dict(ctx) if isinstance(ctx, Mapping) else {"text": str(ctx)} for ctx in contexts]
    contexts = metadata.get("retrievedContexts")
    if isinstance(contexts, list) and contexts:
        return [dict(ctx) if isinstance(ctx, Mapping) else {"text": str(ctx)} for ctx in contexts]
    return []


def _candidate_context_list(metadata: Mapping[str, Any], limit: int) -> list[Mapping[str, Any]]:
    contexts = metadata.get("candidateContexts")
    if not isinstance(contexts, list) or not contexts:
        return []
    return _limit_contexts([dict(ctx) if isinstance(ctx, Mapping) else {"text": str(ctx)} for ctx in contexts], limit)


def _limit_contexts(contexts: Sequence[Mapping[str, Any]], limit: int) -> list[Mapping[str, Any]]:
    return [dict(ctx) for ctx in list(contexts)[:limit]]


def _control_retrieval_latency_ms(metadata: Mapping[str, Any], mode: str) -> float:
    if mode in {NO_RAG, WRONG_CONTEXT, ORACLE_CONTEXT}:
        return 0.0
    key = "candidatePathMs" if mode == RERANKER_OFF else "finalProductionPathMs"
    latency = metadata.get("retrievalLatency")
    value = latency.get(key) if isinstance(latency, Mapping) else None
    if not isinstance(value, (int, float)) or isinstance(value, bool) or value < 0:
        raise ValueError(f"{mode} control requires non-negative retrievalLatency.{key}")
    return float(value)


def _apply_context_budget(contexts: Sequence[Mapping[str, Any]], token_budget: int) -> list[Mapping[str, Any]]:
    """Pack contexts into a conservative eval token budget while preserving chunk provenance."""
    remaining_chars = token_budget * 2
    packed: list[Mapping[str, Any]] = []
    for context in contexts:
        if remaining_chars <= 0:
            break
        original_text = _context_text(context)
        included_text = original_text[:remaining_chars]
        if not included_text:
            continue
        packed_context = dict(context)
        text_key = "text" if "text" in packed_context else "content"
        packed_context[text_key] = included_text
        if len(included_text) < len(original_text):
            packed_context["contextBudgetTruncated"] = True
        packed.append(packed_context)
        remaining_chars -= len(included_text)
    return packed


def _context_text(context: Mapping[str, Any]) -> str:
    return str(context.get("text") or context.get("content") or "")


def _filter_rows(rows: Sequence[Mapping[str, Any]], config: AnswerHarnessConfig) -> list[Mapping[str, Any]]:
    filtered = list(rows)
    if config.splits:
        allowed = set(config.splits)
        filtered = [row for row in filtered if str(row.get("split") or "") in allowed]
    if config.max_samples is not None:
        filtered = filtered[: config.max_samples]
    return filtered


def _reranker_enabled(mode: str) -> bool | None:
    if mode in {FULL_RAG, RERANKER_ON, WRONG_CONTEXT}:
        return True
    if mode in {RERANKER_OFF, NO_RAG}:
        return False
    return None


def _approx_token_count(texts: Sequence[str]) -> int:
    # Dense numeric/table content can tokenize near two characters per token.
    return sum(max(1, (len(text) + 1) // 2) for text in texts if text)


def _shuffled_contexts(rows: Sequence[Mapping[str, Any]]) -> list[list[Mapping[str, Any]]]:
    """Choose disjoint source-group contexts for the wrong-context control."""
    if len(rows) < 2:
        return [[] for _ in rows]
    result: list[list[Mapping[str, Any]]] = []
    for i, row in enumerate(rows):
        source_group = str(row.get("sourceGroupId") or "")
        reference_ids = {str(value) for value in row.get("referenceContextIds") or [] if str(value)}
        selected: list[Mapping[str, Any]] = []
        for offset in range(1, len(rows)):
            candidate = rows[(i + offset) % len(rows)]
            candidate_group = str(candidate.get("sourceGroupId") or "")
            candidate_contexts = _context_list(candidate, candidate.get("metadata") or {})
            candidate_ids = {
                str(context.get("chunkId") or context.get("id") or "")
                for context in candidate_contexts
                if isinstance(context, Mapping)
            }
            if candidate_contexts and candidate_group != source_group and not reference_ids.intersection(candidate_ids):
                selected = candidate_contexts
                break
        result.append(selected)
    return result


# ── Prompt building ─────────────────────────────────────────────────────────


def _build_prompt(query: str, context_texts: list[str], mode: str) -> list[dict[str, str]]:
    messages: list[dict[str, str]] = [{"role": "system", "content": PROMPT_SYSTEM}]
    if mode == NO_RAG:
        messages.append({"role": "user", "content": f"Question: {query}\n\nAnswer without any provided context."})
    elif mode == ORACLE_CONTEXT:
        joined = "\n\n---\n\n".join(context_texts) if context_texts else "(no reference content available)"
        messages.append({"role": "user", "content": f"Reference text:\n\n{joined}\n\nQuestion: {query}\n\nAnswer based on the reference text above."})
    elif mode == WRONG_CONTEXT:
        joined = "\n\n---\n\n".join(context_texts) if context_texts else "(no context available)"
        messages.append({"role": "user", "content": f"Context:\n\n{joined}\n\nQuestion: {query}\n\nAnswer based on the context above."})
    else:  # FULL_RAG
        joined = "\n\n---\n\n".join(context_texts) if context_texts else "(no context available)"
        messages.append({"role": "user", "content": f"Context:\n\n{joined}\n\nQuestion: {query}\n\nAnswer based on the context above."})
    return messages


def _prompt_contexts(contexts: Sequence[Mapping[str, Any]]) -> list[str]:
    sections: list[str] = []
    for index, context in enumerate(contexts, start=1):
        source = str(context.get("documentName") or context.get("documentId") or "Unknown")
        chunk_index = context.get("chunkIndex")
        source_line = f"[{index}] Source: {source} [KNOWLEDGE_BASE]"
        if chunk_index is not None:
            source_line += f" chunk {chunk_index}"
        lines = [source_line]
        if context.get("sectionPath"):
            lines.append(f"Section: {context['sectionPath']}")
        if context.get("contextText"):
            lines.append(f"Chunk Context:\n{context['contextText']}")
        lines.append(f"Chunk Content:\n{_context_text(context)}")
        sections.append("\n".join(lines))
    return sections


# ── Citation extraction ─────────────────────────────────────────────────────


def _extract_citations(response: str, contexts: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """Simple citation extraction: find chunk IDs referenced in the response."""
    citations: list[dict[str, Any]] = []
    for i, ctx in enumerate(contexts):
        chunk_id = str(ctx.get("chunkId") or ctx.get("id") or "")
        if not chunk_id:
            continue
        marker = f"[{i + 1}]"
        supported = marker in response or chunk_id[:8] in response
        citations.append({"sourceChunkId": chunk_id, "marker": marker, "supported": supported})
    return citations


# ── LLM calling ─────────────────────────────────────────────────────────────


def _generate_answer(messages: list[dict[str, str]], config: AnswerHarnessConfig) -> tuple[str, dict[str, int]]:
    """Call the configured LLM provider for answer generation."""
    from openai import OpenAI

    api_key = config.llm_api_key or _provider_api_key(config.llm_provider)
    if not api_key:
        raise RuntimeError(f"Missing API key for answer LLM provider: {config.llm_provider}")
    base_url = _clean_base_url(config.llm_base_url or _provider_base_url(config.llm_provider))
    client_kwargs: dict[str, Any] = {"api_key": api_key}
    if base_url:
        client_kwargs["base_url"] = base_url
    client = OpenAI(**client_kwargs)
    create_kwargs: dict[str, Any] = {
        "model": config.llm_model,
        "messages": messages,
        "temperature": config.llm_temperature,
        "max_tokens": config.llm_max_tokens,
    }
    if config.llm_provider.lower() in {"zhipu", "zhipuai", "z.ai", "zai", "z-ai", "zai-coding"}:
        create_kwargs["extra_body"] = {"thinking": {"type": "disabled"}}
    response = client.chat.completions.create(**create_kwargs)
    usage = response.usage
    token_counts = {
        "promptTokens": usage.prompt_tokens if usage else 0,
        "completionTokens": usage.completion_tokens if usage else 0,
        "totalTokens": usage.total_tokens if usage else 0,
    }
    content = response.choices[0].message.content or "" if response.choices else ""
    return content, token_counts


def _provider_api_key(provider: str) -> str | None:
    if provider == "deepseek":
        return os.getenv("CHATAGENT_EVAL_ANSWER_LLM_API_KEY") or os.getenv("CHATAGENT_DEEPSEEK_API_KEY")
    if provider in {"zhipu", "zhipuai"}:
        return _first_env(
            "CHATAGENT_ZHIPUAI_API_KEY",
            "CHATAGENT_ZHIPUAI_API_KEY_2",
            "CHATAGENT_ZHIPUAI_SECONDARY_API_KEY",
            "CHATAGENT_EVAL_ANSWER_LLM_API_KEY",
        )
    if provider in {"z.ai", "zai", "z-ai", "zai-coding"}:
        return _first_env("CHATAGENT_ZAI_CODING_API_KEY", "CHATAGENT_ZAI_API_KEY", "CHATAGENT_EVAL_ANSWER_LLM_API_KEY")
    if provider == "openai":
        return os.getenv("CHATAGENT_EVAL_ANSWER_LLM_API_KEY") or os.getenv("OPENAI_API_KEY")
    return os.getenv("CHATAGENT_EVAL_ANSWER_LLM_API_KEY")


def _provider_base_url(provider: str) -> str | None:
    if provider == "deepseek":
        return os.getenv("CHATAGENT_EVAL_ANSWER_LLM_BASE_URL") or os.getenv("CHATAGENT_DEEPSEEK_BASE_URL") or "https://api.deepseek.com"
    if provider in {"zhipu", "zhipuai"}:
        return (
            os.getenv("CHATAGENT_EVAL_ANSWER_LLM_BASE_URL")
            or os.getenv("CHATAGENT_ZHIPUAI_BASE_URL")
            or DEFAULT_ZHIPUAI_BASE_URL
        )
    if provider in {"z.ai", "zai", "z-ai", "zai-coding"}:
        return (
            os.getenv("CHATAGENT_EVAL_ANSWER_LLM_BASE_URL")
            or _first_env("CHATAGENT_ZAI_CODING_BASE_URL", "CHATAGENT_ZAI_BASE_URL")
            or DEFAULT_ZAI_CODING_BASE_URL
        )
    return os.getenv("CHATAGENT_EVAL_ANSWER_LLM_BASE_URL")


def _first_env(*names: str) -> str | None:
    for name in names:
        value = os.getenv(name)
        if value:
            return value
    return None


def _clean_base_url(value: str | None) -> str | None:
    if not value:
        return None
    cleaned = value.strip().strip(" \t\r\n\"';")
    return cleaned or None


# ── Helpers ─────────────────────────────────────────────────────────────────


def _checkpoint_paths(output_root: Path, run_id: str) -> tuple[Path, Path]:
    checkpoint_dir = output_root.resolve() / f".{run_id}.checkpoint"
    return checkpoint_dir, checkpoint_dir / "samples.jsonl"


def _load_or_create_checkpoint(
    *,
    checkpoint_dir: Path,
    checkpoint_samples: Path,
    run_id: str,
    config_fingerprint: str,
) -> list[dict[str, Any]]:
    checkpoint_dir.mkdir(parents=True, exist_ok=True)
    checkpoint_config = checkpoint_dir / "config.json"
    expected = {"runId": run_id, "configFingerprint": config_fingerprint}
    if checkpoint_config.exists():
        actual = json.loads(checkpoint_config.read_text(encoding="utf-8"))
        if actual != expected:
            raise ValueError(f"answer checkpoint config mismatch for run id: {run_id}")
    else:
        checkpoint_config.write_text(
            json.dumps(expected, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    if not checkpoint_samples.exists():
        return []
    return [
        json.loads(line)
        for line in checkpoint_samples.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def _append_checkpoint_row(checkpoint_samples: Path, row: Mapping[str, Any]) -> None:
    with checkpoint_samples.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(dict(row), ensure_ascii=False, sort_keys=True) + "\n")


def _remove_checkpoint(checkpoint_dir: Path, checkpoint_samples: Path) -> None:
    with suppress(FileNotFoundError):
        checkpoint_samples.unlink()
    with suppress(FileNotFoundError):
        (checkpoint_dir / "config.json").unlink()
    with suppress(FileNotFoundError, OSError):
        checkpoint_dir.rmdir()


def _control_mode(row: Mapping[str, Any]) -> str | None:
    control = row.get("controlRun") or (row.get("metadata") or {}).get("controlRun")
    if isinstance(control, Mapping):
        return str(control.get("mode") or "")
    return None


def _error_category(exc: Exception) -> str:
    name = type(exc).__name__
    if "empty response" in str(exc).lower():
        return "llm_empty_response"
    if "timeout" in name.lower():
        return "llm_timeout"
    if "auth" in name.lower() or "api_key" in str(exc).lower():
        return "llm_auth_error"
    if "rate" in name.lower() or "rate" in str(exc).lower():
        return "llm_rate_limit"
    return "llm_error"


def _rows_hash(rows: Sequence[Mapping[str, Any]]) -> str:
    digest = hashlib.sha256()
    for row in rows:
        digest.update(json.dumps(dict(row), ensure_ascii=False, sort_keys=True).encode("utf-8"))
        digest.update(b"\n")
    return f"sha256:{digest.hexdigest()}"
