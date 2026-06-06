"""Deterministic text-recall runner for real source files."""

from __future__ import annotations

import json
import math
import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from typing import Any

from chatagent_eval.deterministic_metrics import phrase_recall
from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import build_manifest, build_report, write_json_artifact, write_run_artifacts

DEFAULT_TEXT_RECALL_DATASET_ID = "sec-companyfacts-text-recall-v1"
ARTIFACT_FILES = ("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl", "report.json")


@dataclass(frozen=True)
class TextRecallConfig:
    run_id: str
    mode: str = "text-recall-smoke"
    dataset_id: str = DEFAULT_TEXT_RECALL_DATASET_ID
    chunk_size: int = 2000
    chunk_overlap: int = 200
    top_k: int = 5
    max_samples: int | None = None
    splits: tuple[str, ...] = ()
    parser: str = "html-visible-text"
    retrieval: str = "lexical-required-phrase-overlap"
    git_branch: str = "unknown"
    git_sha: str = "unknown"

    def __post_init__(self) -> None:
        if self.chunk_size <= 0:
            raise ValueError("chunk_size must be positive")
        if self.chunk_overlap < 0 or self.chunk_overlap >= self.chunk_size:
            raise ValueError("chunk_overlap must be non-negative and smaller than chunk_size")
        if self.top_k <= 0:
            raise ValueError("top_k must be positive")
        if self.max_samples is not None and self.max_samples <= 0:
            raise ValueError("max_samples must be positive when provided")

    def as_dict(self) -> dict[str, Any]:
        return {
            "datasetId": self.dataset_id,
            "chunkSize": self.chunk_size,
            "chunkOverlap": self.chunk_overlap,
            "topK": self.top_k,
            "maxSamples": self.max_samples,
            "splits": list(self.splits),
            "parser": self.parser,
            "retrieval": self.retrieval,
        }


def run_text_recall(*, dataset_root: Path, output_root: Path, config: TextRecallConfig) -> Path:
    dataset_manifest = _read_json(dataset_root / "manifests" / "datasets" / f"{config.dataset_id}.json")
    rows = _select_rows(_read_jsonl(dataset_root / dataset_manifest["localPath"]), config)
    config_dict = config.as_dict() | {
        "datasetManifestHash": dataset_manifest["datasetHash"],
        "recordSchema": dataset_manifest["recordSchema"],
    }
    fingerprint = config_fingerprint(config_dict)

    evaluated = [_evaluate_row(dataset_root, row, config) for row in rows]
    samples = [item["sample"] for item in evaluated]
    failures = [failure for item in evaluated for failure in item["failures"]]
    metric_values = _aggregate_metrics(evaluated, config)
    status = "warn" if failures else "pass"

    manifest = build_manifest(
        run_id=config.run_id,
        suite="text-recall",
        mode=config.mode,
        timestamp=datetime.now(timezone.utc).isoformat(),
        git_branch=config.git_branch,
        git_sha=config.git_sha,
        dataset_id=dataset_manifest["datasetId"],
        dataset_hash=dataset_manifest["datasetHash"],
        config=config_dict,
        config_fingerprint=fingerprint,
        artifact_files=ARTIFACT_FILES,
    )
    metrics = {
        "status": status,
        "parser": {
            "phraseRecall": metric_values["textRecall.parserPhraseRecall"],
            "fileSuccessRate": metric_values["textRecall.parserFileSuccessRate"],
        },
        "chunk": {
            "spanRecall": metric_values["textRecall.chunkSpanRecall"],
            "chunkSize": config.chunk_size,
            "chunkOverlap": config.chunk_overlap,
        },
        "retrieval": {
            "contextPhraseRecall": metric_values["textRecall.retrievalContextPhraseRecall"],
            "topK": config.top_k,
        },
        "citation": {
            "supportRecall": metric_values["textRecall.citationSupportRecall"],
        },
        "tableCells": {
            "recall": metric_values["textRecall.tableCellRecall"],
            "sampleCount": metric_values["textRecall.tableCellSampleCount"],
        },
        "merged": metric_values,
    }
    report = build_report(
        run_id=config.run_id,
        suite="text-recall",
        mode=config.mode,
        status=status,
        dataset_id=dataset_manifest["datasetId"],
        dataset_hash=dataset_manifest["datasetHash"],
        config_fingerprint=fingerprint,
        metrics=metric_values,
        threshold_results=[],
    )
    run_dir = write_run_artifacts(output_root, manifest, metrics, samples, failures)
    write_json_artifact(run_dir / "report.json", report)
    return run_dir


def _select_rows(rows: Sequence[Mapping[str, Any]], config: TextRecallConfig) -> list[Mapping[str, Any]]:
    selected = [row for row in rows if not config.splits or str(row["split"]) in config.splits]
    if config.max_samples is not None:
        selected = selected[: config.max_samples]
    if not selected:
        raise ValueError("text-recall selection produced no rows")
    return selected


def _evaluate_row(dataset_root: Path, row: Mapping[str, Any], config: TextRecallConfig) -> dict[str, Any]:
    source_path = _resolve_source_file(dataset_root, str(row["sourceFile"]))
    raw_text = source_path.read_text(encoding="utf-8", errors="replace")
    parsed_text = _parse_text(raw_text, str(row["mediaType"]), config.parser)
    required_phrases = list(row.get("requiredPhrases", []))
    required_table_cells = list((row.get("metadata") or {}).get("requiredTableCells", []))
    chunks = _chunk_text(parsed_text, config.chunk_size, config.chunk_overlap)
    contexts = _rank_contexts(
        chunks,
        query_terms=[*required_phrases, *required_table_cells],
        top_k=config.top_k,
        sample_id=str(row["sampleId"]),
        source_id=str(row["sourceUrl"]),
    )

    parser_missing = _missing_phrases([parsed_text], required_phrases)
    chunk_missing = _missing_phrases([chunk["text"] for chunk in chunks], required_phrases)
    retrieval_missing = _missing_phrases([context["text"] for context in contexts], required_phrases)
    citation_missing = _missing_phrases(
        [context["text"] for context in contexts if context["sourceId"] == row["sourceUrl"]],
        required_phrases,
    )
    table_cell_missing = _missing_phrases([parsed_text], required_table_cells)
    reference_context_ids = [chunk["id"] for chunk in chunks if _contains_any(chunk["text"], required_phrases)]

    parser_recall = phrase_recall([parsed_text], required_phrases)
    chunk_recall = phrase_recall([chunk["text"] for chunk in chunks], required_phrases)
    retrieval_recall = phrase_recall([context["text"] for context in contexts], required_phrases)
    citation_recall = phrase_recall([context["text"] for context in contexts if context["sourceId"] == row["sourceUrl"]], required_phrases)
    table_cell_recall = phrase_recall([parsed_text], required_table_cells) if required_table_cells else None

    sample = {
        "sampleId": row["sampleId"],
        "datasetId": row["datasetId"],
        "split": row["split"],
        "userInput": _recall_prompt(row),
        "retrievedContexts": contexts,
        "referenceContextIds": reference_context_ids,
        "metadata": {
            "sourceFile": row["sourceFile"],
            "sourceUrl": row["sourceUrl"],
            "mediaType": row["mediaType"],
            "requiredPhrases": required_phrases,
            "requiredTableCells": required_table_cells,
            "parser": {"recall": parser_recall, "missingPhrases": parser_missing},
            "chunk": {
                "recall": chunk_recall,
                "missingPhrases": chunk_missing,
                "chunkCount": len(chunks),
                "chunkSize": config.chunk_size,
                "chunkOverlap": config.chunk_overlap,
            },
            "retrieval": {
                "recall": retrieval_recall,
                "missingPhrases": retrieval_missing,
                "topK": config.top_k,
            },
            "citation": {"recall": citation_recall, "missingPhrases": citation_missing},
            "tableCells": {"recall": table_cell_recall, "missingCells": table_cell_missing},
        },
    }
    failures = _row_failures(
        row=row,
        parser_missing=parser_missing,
        chunk_missing=chunk_missing,
        retrieval_missing=retrieval_missing,
        citation_missing=citation_missing,
        table_cell_missing=table_cell_missing,
        contexts=contexts,
    )
    return {
        "sample": sample,
        "failures": failures,
        "metrics": {
            "parserPhraseRecall": parser_recall,
            "parserFileSuccess": 1.0 if parsed_text.strip() else 0.0,
            "chunkSpanRecall": chunk_recall,
            "retrievalContextPhraseRecall": retrieval_recall,
            "citationSupportRecall": citation_recall,
            "tableCellRecall": table_cell_recall,
        },
    }


def _row_failures(
    *,
    row: Mapping[str, Any],
    parser_missing: Sequence[str],
    chunk_missing: Sequence[str],
    retrieval_missing: Sequence[str],
    citation_missing: Sequence[str],
    table_cell_missing: Sequence[str],
    contexts: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    failures: list[dict[str, Any]] = []
    if parser_missing:
        failures.append(_failure(row, "parserPhraseRecall", "missing_parser_phrases", parser_missing, contexts))
    if chunk_missing:
        failures.append(_failure(row, "chunkSpanRecall", "missing_chunk_spans", chunk_missing, contexts))
    if retrieval_missing:
        failures.append(_failure(row, "retrievalContextPhraseRecall", "missing_retrieval_context_phrases", retrieval_missing, contexts))
    if citation_missing:
        failures.append(_failure(row, "citationSupportRecall", "missing_citation_support", citation_missing, contexts))
    if table_cell_missing:
        failures.append(_failure(row, "tableCellRecall", "missing_table_cells", table_cell_missing, contexts))
    return failures


def _failure(
    row: Mapping[str, Any],
    metric: str,
    category: str,
    missing: Sequence[str],
    contexts: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    return {
        "sampleId": row["sampleId"],
        "metric": metric,
        "errorCategory": category,
        "missingPhrases": list(missing),
        "sourceFile": row["sourceFile"],
        "sourceUrl": row["sourceUrl"],
        "topKContexts": [
            {
                "id": context["id"],
                "text": context["text"],
                "sourceId": context["sourceId"],
                "score": context["score"],
            }
            for context in contexts
        ],
    }


def _aggregate_metrics(evaluated: Sequence[Mapping[str, Any]], config: TextRecallConfig) -> dict[str, float | None]:
    rows = [item["metrics"] for item in evaluated]
    table_cell_values = [row["tableCellRecall"] for row in rows if row["tableCellRecall"] is not None]
    return {
        "textRecall.sampleCount": float(len(rows)),
        "textRecall.requiredPhraseCount": float(sum(len(item["sample"]["metadata"]["requiredPhrases"]) for item in evaluated)),
        "textRecall.tableCellSampleCount": float(len(table_cell_values)),
        "textRecall.parserPhraseRecall": _mean(row["parserPhraseRecall"] for row in rows),
        "textRecall.parserFileSuccessRate": _mean(row["parserFileSuccess"] for row in rows),
        "textRecall.chunkSpanRecall": _mean(row["chunkSpanRecall"] for row in rows),
        "textRecall.retrievalContextPhraseRecall": _mean(row["retrievalContextPhraseRecall"] for row in rows),
        "textRecall.citationSupportRecall": _mean(row["citationSupportRecall"] for row in rows),
        "textRecall.tableCellRecall": _mean(table_cell_values) if table_cell_values else None,
        "textRecall.chunkSize": float(config.chunk_size),
        "textRecall.chunkOverlap": float(config.chunk_overlap),
        "textRecall.topK": float(config.top_k),
    }


def _rank_contexts(
    chunks: Sequence[Mapping[str, Any]],
    *,
    query_terms: Sequence[str],
    top_k: int,
    sample_id: str,
    source_id: str,
) -> list[dict[str, Any]]:
    scored = []
    query_tokens = _tokens(" ".join(query_terms))
    for index, chunk in enumerate(chunks):
        text = str(chunk["text"])
        phrase_hits = sum(1 for term in query_terms if _contains_phrase(text, term))
        token_hits = len(query_tokens & _tokens(text))
        score = float(phrase_hits * 1000 + token_hits)
        scored.append((score, index, chunk))
    selected = sorted(scored, key=lambda item: (-item[0], item[1]))[:top_k]
    return [
        {
            "id": f"{sample_id}:chunk:{item[1]}",
            "text": item[2]["text"],
            "sourceId": source_id,
            "score": item[0],
        }
        for item in selected
    ]


def _chunk_text(text: str, chunk_size: int, chunk_overlap: int) -> list[dict[str, Any]]:
    if not text:
        return [{"id": "chunk:0", "text": ""}]
    chunks: list[dict[str, Any]] = []
    start = 0
    index = 0
    step = chunk_size - chunk_overlap
    while start < len(text):
        end = min(start + chunk_size, len(text))
        chunks.append({"id": f"chunk:{index}", "text": text[start:end], "start": start, "end": end})
        if end == len(text):
            break
        start += step
        index += 1
    return chunks


def _parse_text(raw_text: str, media_type: str, parser: str) -> str:
    if parser != "html-visible-text":
        raise ValueError(f"unsupported text-recall parser: {parser}")
    if "html" not in media_type.lower():
        return _normalize_spaces(raw_text)
    extractor = _VisibleTextParser()
    extractor.feed(raw_text)
    return _normalize_spaces(" ".join(extractor.text))


class _VisibleTextParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.text: list[str] = []
        self._ignored_depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in {"script", "style", "ix:hidden"}:
            self._ignored_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "ix:hidden"} and self._ignored_depth:
            self._ignored_depth -= 1

    def handle_data(self, data: str) -> None:
        if not self._ignored_depth:
            self.text.append(data)


def _resolve_source_file(dataset_root: Path, source_file: str) -> Path:
    root = dataset_root.resolve()
    path = (root / source_file).resolve()
    if not path.is_relative_to(root):
        raise ValueError(f"text-recall source file escapes dataset root: {source_file}")
    if not path.is_file():
        raise FileNotFoundError(f"text-recall source file does not exist: {source_file}")
    return path


def _missing_phrases(texts: Sequence[str], required_phrases: Iterable[str]) -> list[str]:
    searchable = _normalize_spaces(" ".join(texts)).casefold()
    return [phrase for phrase in required_phrases if _normalize_spaces(str(phrase)).casefold() not in searchable]


def _contains_any(text: str, phrases: Sequence[str]) -> bool:
    return any(_contains_phrase(text, phrase) for phrase in phrases)


def _contains_phrase(text: str, phrase: str) -> bool:
    return _normalize_spaces(phrase).casefold() in _normalize_spaces(text).casefold()


def _tokens(text: str) -> set[str]:
    return {token.casefold() for token in re.findall(r"[A-Za-z0-9_:-]+", text) if token}


def _normalize_spaces(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def _recall_prompt(row: Mapping[str, Any]) -> str:
    metadata = row.get("metadata") or {}
    entity = metadata.get("entityName", "the source document")
    form = metadata.get("form", "filing")
    return f"Recall required evidence phrases from {entity} {form}."


def _mean(values: Iterable[float | None]) -> float | None:
    numeric = [value for value in values if value is not None and not math.isnan(value)]
    return sum(numeric) / len(numeric) if numeric else None


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]
