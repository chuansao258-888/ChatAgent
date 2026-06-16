"""B3.3 accepted-question generation, validation, overlay, and hash contracts."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import urllib.request
from collections import Counter, defaultdict
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

FORMATS = ("SEC_HTML", "PDF", "DOCX", "XLSX", "WEB_MD")
SPLITS = ("calibration", "development", "holdout")
MIN_TOTAL = 500
MIN_PER_FORMAT = 50
MIN_PER_FORMAT_SPLIT = {"calibration": 20, "development": 10, "holdout": 10}
MAX_QUESTIONS_PER_CHUNK = 3
MIN_UNIQUE_CHUNK_RATIO = 0.60
OVERLAY_KEYS = {"id", "field", "oldValue", "newValue", "reason", "reviewer", "tuningAllowed"}
OVERLAY_FIELDS = {"question", "referenceAnswer"}
ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]+$")
LOWER_HEX_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
CORPUS_GROUNDING_MIN_ANCHORS = 2
CORPUS_GROUNDING_STOP_WORDS = {
    "a", "an", "and", "are", "as", "at", "be", "been", "by", "can", "could",
    "current", "did", "do", "document", "does", "evidence", "excerpt", "file",
    "filing", "for", "form", "from", "given", "had", "has", "have", "how", "if",
    "in", "indicated", "information", "into", "is", "it", "item", "its", "listed",
    "may", "might", "must", "of", "on", "or", "provided", "report", "reported",
    "should", "source", "specific", "table", "that", "the", "their", "them", "then",
    "there", "these", "they", "this", "those", "to", "under", "value", "was", "were",
    "what", "when", "where", "which", "who", "why", "will", "with", "would",
}


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n"


def canonical_hash(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def text_hash(value: str) -> str:
    normalized = str(value).replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return f"sha256:{digest.hexdigest()}"


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(canonical_json(value), encoding="utf-8", newline="\n")


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_jsonl(path: Path, rows: Sequence[Mapping[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(dict(row), ensure_ascii=False, sort_keys=True) + "\n")


def load_evidence_export(dataset_root: Path, *, minimum_chars: int = 120) -> dict[str, Any]:
    dataset_root = dataset_root.resolve()
    b3_manifest_path = dataset_root / "b3-export-manifest.json"
    dataset_manifest_path = dataset_root / "manifests" / "datasets" / "doc-ingestion-retrieval-v1.json"
    source_manifest_path = dataset_root / "source-manifest.json"
    chunk_inventory_path = dataset_root / "chunk-inventory.json"
    chunk_evidence_path = dataset_root / "chunk-evidence.json"
    rebind_receipt_path = dataset_root / "reference-rebind-receipt.json"
    if not b3_manifest_path.exists():
        raise ValueError(f"B3.2 export manifest not found: {b3_manifest_path}")
    if not dataset_manifest_path.exists():
        raise ValueError(f"B3.2 dataset manifest not found: {dataset_manifest_path}")
    for path in (source_manifest_path, chunk_inventory_path, chunk_evidence_path, rebind_receipt_path):
        if not path.exists():
            raise ValueError(f"B3.2 evidence identity artifact not found: {path}")
    b3_manifest = read_json(b3_manifest_path)
    dataset_manifest = read_json(dataset_manifest_path)
    rebind_receipt = read_json(rebind_receipt_path)
    dataset_hash = str(dataset_manifest.get("datasetHash") or "")
    if not dataset_hash or b3_manifest.get("datasetHash") != dataset_hash:
        raise ValueError("B3.2 export and dataset manifest hashes do not match")
    if b3_manifest.get("rebindReceiptHash") != file_hash(rebind_receipt_path):
        raise ValueError("B3.2 rebind receipt bytes do not match the export manifest")
    if b3_manifest.get("sourceManifestHash") != file_hash(source_manifest_path):
        raise ValueError("B3.2 source manifest bytes do not match the export manifest")
    if b3_manifest.get("chunkInventoryHash") != file_hash(chunk_inventory_path):
        raise ValueError("B3.2 chunk inventory bytes do not match the export manifest")
    if b3_manifest.get("chunkEvidenceHash") != file_hash(chunk_evidence_path):
        raise ValueError("B3.2 chunk evidence bytes do not match the export manifest")
    if not str(rebind_receipt.get("sourceManifestHash") or ""):
        raise ValueError("B3.2 rebind receipt is missing the frozen source-manifest hash")
    if rebind_receipt.get("chunkInventoryHash") != file_hash(chunk_inventory_path):
        raise ValueError("B3.2 chunk inventory bytes do not match the rebind receipt")
    dataset_path = (dataset_root / str(dataset_manifest.get("localPath") or "")).resolve()
    try:
        dataset_path.relative_to(dataset_root)
    except ValueError as exc:
        raise ValueError("B3.2 dataset path escapes the frozen dataset root") from exc
    if not dataset_path.exists() or file_hash(dataset_path) != dataset_hash:
        raise ValueError("B3.2 dataset bytes do not match the frozen dataset hash")

    dataset_rows = read_jsonl(dataset_path)
    dataset_sources: dict[str, dict[str, str]] = {}
    for row in dataset_rows:
        metadata = dict(row.get("metadata") or {})
        source_sha256 = str(metadata.get("sourceSha256") or "")
        identity = {
            "sourceUrl": str(row.get("sourceUrl") or metadata.get("sourceUrl") or ""),
            "filename": str(metadata.get("referenceDocFilename") or ""),
            "sourceGroup": str(metadata.get("sourceGroup") or ""),
            "format": str(row.get("fileFormat") or metadata.get("format") or ""),
            "split": str(row.get("split") or ""),
        }
        if (
            not source_sha256
            or not all(identity.values())
            or identity["format"] not in FORMATS
            or identity["split"] not in SPLITS
        ):
            raise ValueError(f"B3.2 dataset row has incomplete source identity: {row.get('sampleId')}")
        previous = dataset_sources.setdefault(source_sha256, identity)
        if previous != identity:
            raise ValueError(f"B3.2 dataset has conflicting identity for sourceSha256: {source_sha256}")

    sources_by_sha: dict[str, dict[str, Any]] = {}
    for source in read_json(source_manifest_path).get("sources") or []:
        source_sha256 = str(source.get("sha256") or "")
        if not source_sha256 or source_sha256 in sources_by_sha:
            raise ValueError(f"B3.2 source manifest has missing/duplicate sha256: {source_sha256}")
        expected = dataset_sources.get(source_sha256)
        actual = {
            "sourceUrl": str(source.get("sourceUrl") or ""),
            "filename": str(source.get("filename") or ""),
            "sourceGroup": str(source.get("sourceGroup") or ""),
            "format": str(source.get("format") or ""),
            "split": str(source.get("split") or ""),
        }
        if expected != actual:
            raise ValueError(f"B3.2 source manifest conflicts with frozen dataset identity: {source_sha256}")
        sources_by_sha[source_sha256] = dict(source)
    if set(sources_by_sha) != set(dataset_sources):
        raise ValueError("B3.2 source manifest and frozen dataset contain different sources")
    inventory_by_chunk_id: dict[str, dict[str, Any]] = {}
    for item in read_json(chunk_inventory_path):
        chunk_id = str(item.get("chunkId") or "")
        source_sha256 = str(item.get("sourceSha256") or "")
        source = sources_by_sha.get(source_sha256)
        if not chunk_id or chunk_id in inventory_by_chunk_id or source is None:
            raise ValueError(f"B3.2 chunk inventory has invalid identity: {chunk_id}")
        if (
            item.get("filename") != source.get("filename")
            or item.get("sourceUrl") != source.get("sourceUrl")
            or not isinstance(item.get("chunkIndex"), int)
            or not str(item.get("contentHash") or "")
        ):
            raise ValueError(f"B3.2 chunk inventory/source manifest mismatch: {chunk_id}")
        inventory_by_chunk_id[chunk_id] = dict(item)

    allowed_evidence_fields = {
        "sourceUrl", "sourceSha256", "filename", "sourceGroup", "format", "split",
        "chunkId", "chunkIndex", "contentLength", "contentHash", "content", "parser", "chunker",
    }
    evidence_by_key: dict[tuple[str, int, str], dict[str, Any]] = {}
    legacy_base_id_owner: dict[str, str] = {}
    seen_chunk_ids: set[str] = set()
    for item in read_json(chunk_evidence_path):
        unknown_fields = set(item) - allowed_evidence_fields
        if unknown_fields:
            raise ValueError(f"B3.2 chunk evidence contains forbidden fields: {sorted(unknown_fields)}")
        chunk_id = str(item.get("chunkId") or "")
        inventory = inventory_by_chunk_id.get(chunk_id)
        source_sha256 = str(item.get("sourceSha256") or "")
        source = sources_by_sha.get(source_sha256)
        content = str(item.get("content") or "")
        content_hash = f"sha256:{hashlib.sha256(content.encode('utf-8')).hexdigest()}"
        if not chunk_id or chunk_id in seen_chunk_ids or inventory is None or source is None:
            raise ValueError(f"B3.2 chunk evidence has invalid identity: {chunk_id}")
        seen_chunk_ids.add(chunk_id)
        for field in ("sourceUrl", "sourceSha256", "filename", "chunkId", "chunkIndex", "contentLength", "contentHash", "parser", "chunker"):
            if item.get(field) != inventory.get(field):
                raise ValueError(f"B3.2 chunk evidence/inventory mismatch for {field}: {chunk_id}")
        for field, source_field in (
            ("sourceUrl", "sourceUrl"),
            ("filename", "filename"),
            ("sourceGroup", "sourceGroup"),
            ("format", "format"),
            ("split", "split"),
        ):
            if item.get(field) != source.get(source_field):
                raise ValueError(f"B3.2 chunk evidence/source manifest mismatch for {field}: {chunk_id}")
        if content_hash != item.get("contentHash"):
            raise ValueError(f"B3.2 chunk evidence content bytes do not match the inventory: {chunk_id}")
        fmt = str(item.get("format") or "")
        split = str(item.get("split") or "")
        if fmt not in FORMATS or split not in SPLITS or len(content.strip()) < minimum_chars:
            continue
        chunk_index = int(item["chunkIndex"])
        key = (source_sha256, chunk_index, content_hash)
        base_evidence_id = _evidence_id(fmt, str(item["filename"]), chunk_index, content)
        legacy_base_id_owner.setdefault(base_evidence_id, source_sha256)
        evidence_by_key.setdefault(
            key,
            {
                "_baseEvidenceId": base_evidence_id,
                "filename": str(item["filename"]),
                "format": fmt,
                "split": split,
                "chunkIndex": chunk_index,
                "referenceChunkId": chunk_id,
                "referenceContent": content,
                "sourceGroup": str(item["sourceGroup"]),
                "sourceUrl": str(item["sourceUrl"]),
                "sourceSha256": source_sha256,
            },
        )
    if seen_chunk_ids != set(inventory_by_chunk_id):
        raise ValueError("B3.2 chunk evidence and inventory contain different chunks")
    for item in evidence_by_key.values():
        base_id = item.pop("_baseEvidenceId")
        item["evidenceId"] = (
            base_id
            if legacy_base_id_owner[base_id] == item["sourceSha256"]
            else f"{base_id}-{text_hash(item['sourceSha256'])[:20]}"
        )
    evidence_ids = [item["evidenceId"] for item in evidence_by_key.values()]
    if len(evidence_ids) != len(set(evidence_ids)):
        raise ValueError("B3.2 evidence IDs are not unique after source-hash disambiguation")
    evidence = sorted(
        evidence_by_key.values(),
        key=lambda item: (FORMATS.index(item["format"]), SPLITS.index(item["split"]), item["filename"], item["chunkIndex"]),
    )
    return {
        "version": "b3.3-evidence-index-v1",
        "evidenceExportManifestPath": str(b3_manifest_path),
        "evidenceExportDatasetHash": dataset_hash,
        "records": evidence,
    }


def select_generation_evidence(evidence_index: Mapping[str, Any], *, target_total: int = MIN_TOTAL) -> list[dict[str, Any]]:
    raw_records = [dict(item) for item in evidence_index.get("records") or []]
    anchor_plans = _corpus_anchor_plans({"records": raw_records}, max_required=None)
    records = _rank_generation_evidence([
        item | {
            "requiredDistinguishingAnchors": plan["requiredAnchors"],
            "distinguishingAnchorCandidates": plan["candidateAnchors"],
        }
        for item in raw_records
        if (
            (plan := anchor_plans[_chunk_key(item)])["coversEveryCompetingChunk"]
            and len(plan["requiredAnchors"]) >= CORPUS_GROUNDING_MIN_ANCHORS
        )
    ])
    selected_by_format: dict[str, list[dict[str, Any]]] = {fmt: [] for fmt in FORMATS}
    used_ids: set[str] = set()
    chunk_counts: Counter[tuple[str, int]] = Counter()

    def variants(items: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
        return [
            dict(item) | {"evidenceId": f"{item['evidenceId']}-q{variant}", "baseEvidenceId": item["evidenceId"]}
            for variant in (2, 3)
            for item in items
        ]

    def add(fmt: str, item: Mapping[str, Any]) -> bool:
        evidence_id = str(item["evidenceId"])
        key = _chunk_key(item)
        if evidence_id in used_ids or chunk_counts[key] >= MAX_QUESTIONS_PER_CHUNK:
            return False
        selected_chunks = {_chunk_key(selected) for selected in selected_by_format[fmt]}
        projected_unique = len(selected_chunks | {key})
        if projected_unique / (len(selected_by_format[fmt]) + 1) < MIN_UNIQUE_CHUNK_RATIO:
            return False
        selected_by_format[fmt].append(dict(item))
        used_ids.add(evidence_id)
        chunk_counts[key] += 1
        return True

    for fmt in FORMATS:
        available = [item for item in records if item["format"] == fmt]
        expanded = _rank_generation_evidence([*available, *variants(available)])
        for split in SPLITS:
            needed = MIN_PER_FORMAT_SPLIT[split]
            for item in expanded:
                if item["split"] == split and add(fmt, item):
                    needed -= 1
                    if needed == 0:
                        break
            if needed:
                raise ValueError(f"insufficient {fmt}/{split} evidence after max-3 expansion: missing {needed}")
        for item in expanded:
            if len(selected_by_format[fmt]) >= MIN_PER_FORMAT:
                break
            add(fmt, item)
        if len(selected_by_format[fmt]) < MIN_PER_FORMAT:
            raise ValueError(f"insufficient {fmt} evidence after max-3 expansion")

    # Fill the remaining global target in balanced rounds, preferring unused chunks.
    pools = {
        fmt: _rank_generation_evidence([
            *[item for item in records if item["format"] == fmt],
            *variants([item for item in records if item["format"] == fmt]),
        ])
        for fmt in FORMATS
    }
    selected_total = sum(len(items) for items in selected_by_format.values())
    while selected_total < target_total:
        progress = False
        for fmt in FORMATS:
            for item in pools[fmt]:
                if add(fmt, item):
                    selected_total += 1
                    progress = True
                    break
            if selected_total >= target_total:
                break
        if not progress:
            break
    if selected_total < target_total:
        raise ValueError(f"insufficient evidence after max-3 expansion: need {target_total}, found {selected_total}")
    for fmt, selected in selected_by_format.items():
        unique = len({_chunk_key(item) for item in selected})
        if unique / len(selected) < MIN_UNIQUE_CHUNK_RATIO:
            raise ValueError(f"{fmt} unique-chunk ratio is below {MIN_UNIQUE_CHUNK_RATIO}")
    return [item for fmt in FORMATS for item in selected_by_format[fmt]][:target_total]


def build_prompt(records: Sequence[Mapping[str, Any]]) -> str:
    payload = [
        {
            "evidenceId": item["evidenceId"],
            "referenceContent": str(item["referenceContent"])[:6000],
            "requiredDistinguishingAnchors": list(item.get("requiredDistinguishingAnchors") or []),
            "distinguishingAnchorCandidates": list(item.get("distinguishingAnchorCandidates") or []),
        }
        for item in records
    ]
    return (
        "Generate exactly one natural, standalone English question for each evidence item below.\n"
        "The question must be answerable only from the supplied evidence, must not mention a source, file, document, "
        "format, URL, evidence, excerpt, table, or identifier, and must not copy the answer or referenceNeedle verbatim.\n"
        "Choose a concise referenceNeedle copied character-for-character verbatim from referenceContent and a concise "
        "factual referenceAnswer. The question must include at least two meaningful non-answer anchors found in the "
        "referenceContent that distinguish this evidence from similar records. Include every requiredDistinguishingAnchors "
        "term naturally in the question and use distinguishingAnchorCandidates for additional context. Prefer distinctive substantive facts "
        "over boilerplate, report dates, addresses, phone numbers, generic ratings, or repeated question templates. "
        "Do not use missing-context phrases such as 'current report', 'provided document', 'this filing', or "
        "'the table'. Vary question wording across items.\n"
        "If multiple evidenceIds share the same referenceContent, target a distinct fact and distinct referenceNeedle for each.\n"
        "Return JSON only: an array of objects with exactly evidenceId, question, referenceNeedle, referenceAnswer.\n"
        "Do not omit items and do not add fields.\n\n"
        + json.dumps(payload, ensure_ascii=False, indent=2)
    )


def parse_gemini_output(value: Any) -> list[dict[str, Any]]:
    if isinstance(value, list):
        return [dict(item) for item in value]
    if isinstance(value, Mapping):
        for key in ("response", "text", "content", "output"):
            if key in value:
                return parse_gemini_output(value[key])
    if not isinstance(value, str):
        raise ValueError("Gemini output must contain a JSON array")
    text = value.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end < start:
        raise ValueError("Gemini output does not contain a JSON array")
    return parse_gemini_output(json.loads(text[start : end + 1]))


def build_candidates(
    generated_rows: Sequence[Mapping[str, Any]],
    evidence_index: Mapping[str, Any],
    *,
    generator_model: str,
    generator_tool: str = "gemini-cli",
    enforce_quotas: bool = True,
    manual_replacement: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    evidence_by_id = {str(item["evidenceId"]): dict(item) for item in evidence_index.get("records") or []}
    questions: list[dict[str, Any]] = []
    seen_evidence: set[str] = set()
    for generated in generated_rows:
        unknown = set(generated) - {"evidenceId", "question", "referenceNeedle", "referenceAnswer"}
        if unknown:
            raise ValueError(f"generated row contains forbidden fields: {sorted(unknown)}")
        evidence_id = str(generated.get("evidenceId") or "")
        evidence = evidence_by_id.get(evidence_id)
        if evidence is None:
            raise ValueError(f"generated row references unknown evidenceId: {evidence_id}")
        if evidence_id in seen_evidence:
            raise ValueError(f"duplicate generated evidenceId: {evidence_id}")
        seen_evidence.add(evidence_id)
        is_manual_replacement = (
            manual_replacement is not None
            and evidence_id == str(manual_replacement.get("evidenceId") or "")
        )
        if is_manual_replacement:
            for field in ("question", "referenceNeedle", "referenceAnswer"):
                if generated.get(field) != manual_replacement.get(field):
                    raise ValueError(f"manual replacement receipt does not reproduce generated field: {field}")
        question = {
            "id": f"q-{evidence_id}",
            "evidenceId": evidence_id,
            "filename": evidence["filename"],
            "format": evidence["format"],
            "split": evidence["split"],
            "chunkIndex": evidence["chunkIndex"],
            "referenceChunkId": evidence["referenceChunkId"],
            "sourceSha256": evidence["sourceSha256"],
            "question": str(generated.get("question") or "").strip(),
            "referenceNeedle": resolve_reference_needle(
                str(generated.get("referenceNeedle") or "").strip(),
                str(evidence["referenceContent"]),
            ),
            "referenceAnswer": str(generated.get("referenceAnswer") or "").strip(),
            "generationMethod": (
                "codex-manual-assisted-llm-reviewed-v1"
                if is_manual_replacement
                else "llm-generated-llm-reviewed-v1"
            ),
            "auditStatus": "pending",
            "llmProvenance": {
                "generatorModel": (
                    str(manual_replacement["generatorModel"])
                    if is_manual_replacement
                    else generator_model
                ),
                "generatorTool": (
                    str(manual_replacement["generatorTool"])
                    if is_manual_replacement
                    else generator_tool
                ),
            },
        }
        validate_question(question, evidence)
        questions.append(question)
    if manual_replacement is not None and str(manual_replacement.get("evidenceId") or "") not in seen_evidence:
        raise ValueError("manual replacement receipt does not match a generated evidenceId")
    validate_question_collection(questions, enforce_quotas=enforce_quotas)
    validate_question_corpus_grounding(questions, evidence_index)
    result = {
        "version": "manual-accepted-questions-v1-candidates",
        "evidenceExportManifestPath": evidence_index["evidenceExportManifestPath"],
        "evidenceExportDatasetHash": evidence_index["evidenceExportDatasetHash"],
        "generatedBy": {"model": generator_model, "tool": generator_tool},
        "generationSourceCounts": {
            "llm-generated-llm-reviewed-v1": len(questions) - (1 if manual_replacement is not None else 0),
            "codex-manual-assisted-llm-reviewed-v1": 1 if manual_replacement is not None else 0,
        },
        "questions": questions,
    }
    if manual_replacement is not None:
        result["generatedBy"]["scope"] = "primary-provider-responses-only"
        result["manualReplacementReceiptHash"] = canonical_hash(manual_replacement)
        result["manualReplacementCount"] = 1
    return result


def validate_question(question: Mapping[str, Any], evidence: Mapping[str, Any]) -> None:
    question_text = str(question.get("question") or "").strip()
    needle = str(question.get("referenceNeedle") or "").strip()
    answer = str(question.get("referenceAnswer") or "").strip()
    content = str(evidence.get("referenceContent") or "")
    if not question_text or not needle or not answer:
        raise ValueError(f"question fields must be non-empty: {question.get('id')}")
    if needle not in content:
        raise ValueError(f"referenceNeedle is not verbatim evidence: {question.get('id')}")
    folded_question = _fold(question_text)
    source_leak_values = [
        evidence.get("filename"),
        Path(str(evidence.get("filename") or "")).stem,
        evidence.get("sourceGroup"),
        evidence.get("sourceUrl"),
    ]
    for value in source_leak_values:
        folded = _fold(str(value or ""))
        if len(folded) >= 4 and folded in folded_question:
            raise ValueError(f"question leaks source/reference value: {question.get('id')}")
    for value in (needle, answer):
        folded = _fold(value)
        if folded and folded in folded_question:
            raise ValueError(f"question leaks source/reference value: {question.get('id')}")
    source_sha256 = str(evidence.get("sourceSha256") or "").casefold()
    if any(
        len(token) >= 8 and token.casefold() in source_sha256
        for token in re.findall(r"[0-9a-fA-F]{8,}", question_text)
    ):
        raise ValueError(f"question leaks source/reference value: {question.get('id')}")
    if any(token in question_text.lower() for token in ("http://", "https://", "\\")) or re.search(
        r"(?:^|\s)/[A-Za-z0-9_.-]+", question_text
    ):
        raise ValueError(f"question leaks a URL or path: {question.get('id')}")
    meaningful_answer = _meaningful_words(answer)
    meaningful_content = _meaningful_words(content)
    answer_tokens = set(re.findall(r"[a-z0-9]+", answer.casefold()))
    evidence_tokens = set(re.findall(r"[a-z0-9]+", (content + "\n" + needle).casefold()))
    numeric_overlap = any(token.isdigit() and token in evidence_tokens for token in answer_tokens)
    meaningful_overlap = len(meaningful_answer & meaningful_content) / len(meaningful_answer) if meaningful_answer else 1.0
    if not numeric_overlap and meaningful_answer and meaningful_overlap < 0.20:
        raise ValueError(f"referenceAnswer is not supported by evidence: {question.get('id')}")


def resolve_reference_needle(needle: str, content: str) -> str:
    if needle in content:
        return needle
    needle_tokens = [match.group(0).casefold() for match in re.finditer(r"[A-Za-z0-9]+", needle)]
    content_matches = list(re.finditer(r"[A-Za-z0-9]+", content))
    content_tokens = [match.group(0).casefold() for match in content_matches]
    if not needle_tokens:
        return needle
    width = len(needle_tokens)
    for start in range(0, len(content_tokens) - width + 1):
        if content_tokens[start : start + width] == needle_tokens:
            return content[content_matches[start].start() : content_matches[start + width - 1].end()]
    return needle


def validate_question_collection(
    questions: Sequence[Mapping[str, Any]],
    *,
    enforce_quotas: bool = True,
    check_near_duplicates: bool = True,
) -> None:
    ids = [str(item.get("id") or "") for item in questions]
    if any(not ID_PATTERN.fullmatch(item) for item in ids) or len(set(ids)) != len(ids):
        raise ValueError("question IDs must be unique non-empty ASCII identifiers")
    chunk_counts = Counter(_chunk_key(item) for item in questions)
    if any(count > MAX_QUESTIONS_PER_CHUNK for count in chunk_counts.values()):
        raise ValueError("a chunk may produce at most 3 questions")
    conflicts = question_collection_retry_feedback(questions, check_near_duplicates=check_near_duplicates)
    if conflicts:
        item_id, feedback = next(iter(conflicts.items()))
        raise ValueError(f"{feedback}: {item_id}")
    if not enforce_quotas:
        return
    if len(questions) < MIN_TOTAL:
        raise ValueError(f"accepted-size candidates need at least {MIN_TOTAL} questions")
    per_format = Counter(str(item.get("format") or "") for item in questions)
    for fmt in FORMATS:
        if per_format[fmt] < MIN_PER_FORMAT:
            raise ValueError(f"{fmt} needs at least {MIN_PER_FORMAT} questions")
        unique = len({_chunk_key(item) for item in questions if item.get("format") == fmt})
        if unique / per_format[fmt] < MIN_UNIQUE_CHUNK_RATIO:
            raise ValueError(f"{fmt} unique-chunk ratio is below {MIN_UNIQUE_CHUNK_RATIO}")
        per_split = Counter(str(item.get("split") or "") for item in questions if item.get("format") == fmt)
        for split, minimum in MIN_PER_FORMAT_SPLIT.items():
            if per_split[split] < minimum:
                raise ValueError(f"{fmt}/{split} needs at least {minimum} questions")


def question_collection_retry_feedback(
    questions: Sequence[Mapping[str, Any]],
    *,
    check_near_duplicates: bool = True,
) -> dict[str, str]:
    """Return retry feedback for later rows that conflict with an earlier valid row."""
    conflicts: dict[str, str] = {}
    needles_by_chunk: dict[tuple[str, int], set[str]] = defaultdict(set)
    for item in questions:
        key = _chunk_key(item)
        needle = str(item.get("referenceNeedle") or "")
        item_id = str(item.get("id") or "")
        if needle in needles_by_chunk[key]:
            conflicts[item_id] = "questions from one chunk must use distinct answer spans"
        else:
            needles_by_chunk[key].add(needle)
    if check_near_duplicates:
        for index, left in enumerate(questions):
            for right in questions[index + 1 :]:
                right_id = str(right.get("id") or "")
                if right_id in conflicts:
                    continue
                if _is_near_duplicate(str(left.get("question") or ""), str(right.get("question") or ""), 0.85):
                    conflicts[right_id] = f"near-duplicate question with {left.get('id')}"
    return conflicts


def validate_question_corpus_grounding(
    questions: Sequence[Mapping[str, Any]],
    evidence_index: Mapping[str, Any],
) -> None:
    """Reject questions that cannot uniquely identify their bound evidence without source hints."""
    evidence_by_id = {str(item["evidenceId"]): item for item in evidence_index.get("records") or []}
    evidence_chunks: dict[tuple[str, int], dict[str, Any]] = {}
    for item in evidence_index.get("records") or []:
        evidence_chunks.setdefault(_chunk_key(item), dict(item))
    chunk_terms = {
        key: set(_corpus_terms(str(item.get("referenceContent") or "")))
        for key, item in evidence_chunks.items()
    }
    document_frequency = Counter(term for terms in chunk_terms.values() for term in terms)
    corpus_size = len(chunk_terms)
    for question in questions:
        evidence = evidence_by_id.get(str(question.get("evidenceId") or ""))
        if evidence is None:
            raise ValueError(f"question has no corpus evidence binding: {question.get('id')}")
        target_key = _chunk_key(evidence)
        target_terms = chunk_terms[target_key]
        excluded = set(_corpus_terms(str(question.get("referenceAnswer") or "")))
        query_terms = set(_corpus_terms(str(question.get("question") or ""))) - excluded
        anchored_terms = query_terms & target_terms
        if len(anchored_terms) < CORPUS_GROUNDING_MIN_ANCHORS:
            raise ValueError(
                f"question lacks {CORPUS_GROUNDING_MIN_ANCHORS} non-answer corpus anchors: {question.get('id')}"
            )
        scores = {
            key: sum(
                math.log((corpus_size + 1) / (document_frequency[term] + 0.5)) + 1.0
                for term in query_terms & terms
            )
            for key, terms in chunk_terms.items()
        }
        target_score = scores[target_key]
        competing_score = max((score for key, score in scores.items() if key != target_key), default=-1.0)
        if target_score <= competing_score:
            raise ValueError(f"question is not uniquely grounded in the frozen evidence corpus: {question.get('id')}")


def _corpus_anchor_plans(
    evidence_index: Mapping[str, Any],
    *,
    candidate_limit: int = 12,
    max_required: int | None = 6,
    extra_excluded_by_key: Mapping[tuple[str, int], Sequence[str]] | None = None,
) -> dict[tuple[str, int], dict[str, Any]]:
    unique_chunks: dict[tuple[str, int], Mapping[str, Any]] = {}
    for item in evidence_index.get("records") or []:
        unique_chunks.setdefault(_chunk_key(item), item)
    terms_by_chunk = {
        key: set(_corpus_terms(str(item.get("referenceContent") or "")))
        for key, item in unique_chunks.items()
    }
    postings: dict[str, set[tuple[str, int]]] = defaultdict(set)
    for key, terms in terms_by_chunk.items():
        for term in terms:
            postings[term].add(key)

    plans: dict[tuple[str, int], dict[str, Any]] = {}
    all_keys = set(unique_chunks)
    for key, item in unique_chunks.items():
        excluded = set(_corpus_terms(" ".join([
            str(item.get("filename") or ""),
            str(item.get("sourceGroup") or ""),
            str(item.get("sourceUrl") or ""),
            str(item.get("sourceSha256") or ""),
            *(extra_excluded_by_key or {}).get(key, ()),
        ])))
        candidates = sorted(
            (
                term
                for term in terms_by_chunk[key] - excluded
                if _is_safe_anchor_term(term)
            ),
            key=lambda term: (len(postings[term]), -len(term), term),
        )
        uncovered = all_keys - {key}
        required: list[str] = []
        while uncovered and (max_required is None or len(required) < max_required):
            choices = [term for term in candidates if term not in required]
            if not choices:
                break
            selected = choices[0] if not required else max(
                enumerate(choices),
                key=lambda choice: (len(uncovered) - len(uncovered & postings[choice[1]]), -choice[0]),
            )[1]
            if not (uncovered - postings[selected]):
                break
            required.append(selected)
            uncovered &= postings[selected]
        for term in candidates:
            if len(required) >= CORPUS_GROUNDING_MIN_ANCHORS:
                break
            if term not in required:
                required.append(term)
        plans[key] = {
            "requiredAnchors": required,
            "candidateAnchors": candidates[:candidate_limit],
            "coversEveryCompetingChunk": not uncovered,
        }
    return plans


def distinctive_corpus_anchor_terms(
    evidence: Mapping[str, Any],
    evidence_index: Mapping[str, Any],
    *,
    excluded_texts: Sequence[str] = (),
    limit: int | None = 12,
) -> list[str]:
    """Return frozen-corpus terms most likely to distinguish the target chunk."""
    unique_chunks: dict[tuple[str, int], Mapping[str, Any]] = {}
    for item in evidence_index.get("records") or []:
        unique_chunks.setdefault(_chunk_key(item), item)
    terms_by_chunk = {
        key: set(_corpus_terms(str(item.get("referenceContent") or "")))
        for key, item in unique_chunks.items()
    }
    target_terms = terms_by_chunk.get(_chunk_key(evidence), set())
    document_frequency = Counter(term for terms in terms_by_chunk.values() for term in terms)
    excluded = set(_corpus_terms(" ".join([
        *excluded_texts,
        str(evidence.get("filename") or ""),
        str(evidence.get("sourceGroup") or ""),
        str(evidence.get("sourceUrl") or ""),
        str(evidence.get("sourceSha256") or ""),
    ])))
    ranked = sorted(
        (
            term
            for term in target_terms - excluded
            if _is_safe_anchor_term(term)
        ),
        key=lambda term: (document_frequency[term], -len(term), term),
    )
    return ranked if limit is None else ranked[:limit]


def corpus_anchor_plan(
    evidence: Mapping[str, Any],
    evidence_index: Mapping[str, Any],
    *,
    excluded_texts: Sequence[str] = (),
    candidate_limit: int = 12,
    max_required: int = 6,
) -> dict[str, Any]:
    """Build a minimal frozen-corpus anchor set that distinguishes the target when possible."""
    target_key = _chunk_key(evidence)
    return _corpus_anchor_plans(
        evidence_index,
        candidate_limit=candidate_limit,
        max_required=max_required,
        extra_excluded_by_key={target_key: excluded_texts},
    )[target_key]


def levenshtein_similarity(left: str, right: str) -> float:
    left, right = _fold(left), _fold(right)
    if not left and not right:
        return 1.0
    if not left or not right:
        return 0.0
    previous = list(range(len(right) + 1))
    for i, left_char in enumerate(left, 1):
        current = [i]
        for j, right_char in enumerate(right, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (left_char != right_char)))
        previous = current
    return 1.0 - previous[-1] / max(len(left), len(right))


def _is_near_duplicate(left: str, right: str, threshold: float) -> bool:
    left, right = _fold(left), _fold(right)
    max_length = max(len(left), len(right))
    if max_length == 0:
        return True
    max_distance = int((1.0 - threshold) * max_length)
    if abs(len(left) - len(right)) > max_distance:
        return False
    if len(left) > len(right):
        left, right = right, left
    previous = list(range(len(right) + 1))
    for i, left_char in enumerate(left, 1):
        start = max(1, i - max_distance)
        end = min(len(right), i + max_distance)
        current = [max_distance + 1] * (len(right) + 1)
        current[0] = i
        row_min = max_distance + 1
        for j in range(start, end + 1):
            current[j] = min(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + (left_char != right[j - 1]))
            row_min = min(row_min, current[j])
        if row_min > max_distance:
            return False
        previous = current
    return previous[len(right)] <= max_distance


def split_hashes(base: Mapping[str, Any]) -> dict[str, str]:
    questions = list(base.get("questions") or [])
    return {f"{split}Hash": canonical_hash([item for item in questions if item.get("split") == split]) for split in SPLITS}


def question_counts(questions: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    per_format = Counter(str(item.get("format") or "") for item in questions)
    per_split = Counter(str(item.get("split") or "") for item in questions)
    per_format_split = {
        fmt: dict(Counter(str(item.get("split") or "") for item in questions if item.get("format") == fmt))
        for fmt in FORMATS
    }
    return {
        "totalQuestions": len(questions),
        "perFormatCounts": dict(per_format),
        "perSplitCounts": dict(per_split),
        "perFormatSplitCounts": per_format_split,
    }


def parse_overlay(path: Path, base: Mapping[str, Any]) -> list[dict[str, Any]]:
    rows = read_jsonl(path)
    base_by_id = {str(item["id"]): item for item in base.get("questions") or []}
    seen: set[tuple[str, str]] = set()
    for row in rows:
        if set(row) != OVERLAY_KEYS:
            raise ValueError(f"overlay entry must contain exactly {sorted(OVERLAY_KEYS)}")
        item_id, field = str(row["id"]), str(row["field"])
        if field not in OVERLAY_FIELDS:
            raise ValueError(f"overlay field is forbidden: {field}")
        base_item = base_by_id.get(item_id)
        if base_item is None:
            raise ValueError(f"overlay references unknown id: {item_id}")
        if base_item.get("split") == "holdout":
            raise ValueError(f"overlay cannot modify holdout id: {item_id}")
        key = (item_id, field)
        if key in seen:
            raise ValueError(f"duplicate overlay entry: {key}")
        seen.add(key)
        if not str(row.get("reviewer") or "").strip() or row.get("tuningAllowed") is not True:
            raise ValueError(f"overlay reviewer/tuningAllowed contract failed: {item_id}")
        if row.get("oldValue") != base_item.get(field):
            raise ValueError(f"overlay oldValue mismatch: {item_id}/{field}")
        if not str(row.get("newValue") or "").strip():
            raise ValueError(f"overlay newValue must be non-empty: {item_id}/{field}")
    return sorted(rows, key=lambda item: (str(item["id"]), str(item["field"])))


def overlay_hash(rows: Sequence[Mapping[str, Any]]) -> str:
    return canonical_hash(sorted((dict(item) for item in rows), key=lambda item: (str(item["id"]), str(item["field"]))))


def apply_overlay(base: Mapping[str, Any], rows: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    result = json.loads(json.dumps(base, ensure_ascii=False))
    by_id = {str(item["id"]): item for item in result.get("questions") or []}
    for row in rows:
        by_id[str(row["id"])][str(row["field"])] = row["newValue"]
    return result


def validate_overlay(
    *,
    base: Mapping[str, Any],
    overlay_rows: Sequence[Mapping[str, Any]],
    evidence_index: Mapping[str, Any],
    verifier: Callable[[str, str, str], Mapping[str, Any]],
    verifier_provenance: Mapping[str, str],
    enforce_quotas: bool = True,
) -> dict[str, Any]:
    effective = apply_overlay(base, overlay_rows)
    validate_question_collection(list(effective.get("questions") or []), enforce_quotas=enforce_quotas)
    evidence_by_id = {str(item["evidenceId"]): item for item in evidence_index.get("records") or []}
    modified_ids = sorted({str(item["id"]) for item in overlay_rows})
    effective_by_id = {str(item["id"]): item for item in effective.get("questions") or []}
    receipt_rows = []
    for item_id in modified_ids:
        item = effective_by_id[item_id]
        evidence = evidence_by_id.get(str(item.get("evidenceId") or ""))
        if evidence is None:
            raise ValueError(f"no frozen evidence binding for overlay id: {item_id}")
        validate_question(item, evidence)
        result = dict(verifier(str(item["question"]), str(item["referenceAnswer"]), str(evidence["referenceContent"])))
        required = {"questionAnswerableFromEvidence", "referenceAnswerSupported", "referenceAnswerAddressesQuestion"}
        if set(result) != required or not all(result.get(key) is True for key in required):
            raise ValueError(f"semantic verifier rejected overlay id: {item_id}")
        receipt_rows.append(
            {
                "id": item_id,
                "questionHash": text_hash(str(item["question"])),
                "referenceAnswerHash": text_hash(str(item["referenceAnswer"])),
                "referenceContentHash": text_hash(str(evidence["referenceContent"])),
                **result,
                "status": "pass",
            }
        )
    return {
        "baseHash": canonical_hash(base),
        "overlayHash": overlay_hash(overlay_rows),
        "evidenceExportDatasetHash": evidence_index["evidenceExportDatasetHash"],
        "verifier": dict(verifier_provenance),
        "rows": receipt_rows,
    }


def local_semantic_verifier(question: str, reference_answer: str, reference_content: str) -> Mapping[str, Any]:
    base_url, model = _local_semantic_verifier_config()
    schema = {
        "type": "object",
        "properties": {
            "questionAnswerableFromEvidence": {"type": "boolean"},
            "referenceAnswerSupported": {"type": "boolean"},
            "referenceAnswerAddressesQuestion": {"type": "boolean"},
        },
        "required": ["questionAnswerableFromEvidence", "referenceAnswerSupported", "referenceAnswerAddressesQuestion"],
        "additionalProperties": False,
    }
    payload = {
        "model": model,
        "temperature": 0,
        "messages": [
            {"role": "system", "content": "Judge only the supplied question, reference answer, and evidence."},
            {
                "role": "user",
                "content": json.dumps(
                    {"question": question, "referenceAnswer": reference_answer, "referenceContent": reference_content},
                    ensure_ascii=False,
                ),
            },
        ],
        "response_format": {"type": "json_schema", "json_schema": {"name": "alignment", "strict": True, "schema": schema}},
    }
    request = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer local"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            body = json.loads(response.read().decode("utf-8"))
        content = body["choices"][0]["message"]["content"]
        return json.loads(content)
    except Exception as exc:
        raise ValueError(f"local semantic verifier unavailable or malformed: {exc}") from exc


def local_semantic_verifier_provenance() -> dict[str, str]:
    _, model = _local_semantic_verifier_config()
    return {
        "provider": "llamacpp",
        "model": model,
        "profile": "native-json-schema-temperature-0",
    }


def _local_semantic_verifier_config() -> tuple[str, str]:
    return (
        os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL") or "http://127.0.0.1:8080/v1",
        os.getenv("CHATAGENT_EVAL_RAGAS_LLM_MODEL") or "qwen3.5-9b-ragas",
    )


def _evidence_id(fmt: str, filename: str, chunk_index: int, content: str) -> str:
    digest = text_hash(f"{fmt}\n{filename}\n{chunk_index}\n{content}")[:20]
    return f"{fmt.lower()}-{digest}"


def _fold(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", value.casefold()).strip()


def _meaningful_words(value: str) -> set[str]:
    return {word for word in _fold(value).split() if len(word) >= 4}


def _corpus_terms(value: str) -> list[str]:
    return [
        term
        for term in re.findall(r"[a-z0-9]+", value.casefold())
        if len(term) >= 3 and term not in CORPUS_GROUNDING_STOP_WORDS
    ]


def _is_safe_anchor_term(term: str) -> bool:
    if term.isdigit():
        return len(term) == 4 and 1900 <= int(term) <= 2100
    return not re.fullmatch(r"[0-9a-f]{8,}", term) and not (
        re.search(r"[a-z]", term) and re.search(r"[0-9]", term)
    )


def _rank_generation_evidence(records: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    unique_chunks: dict[tuple[str, int], Mapping[str, Any]] = {}
    for item in records:
        unique_chunks.setdefault(_chunk_key(item), item)
    terms_by_chunk = {
        key: set(_corpus_terms(str(item.get("referenceContent") or "")))
        for key, item in unique_chunks.items()
    }
    document_frequency = Counter(term for terms in terms_by_chunk.values() for term in terms)
    corpus_size = len(terms_by_chunk)

    def quality(item: Mapping[str, Any]) -> tuple[float, int]:
        weights = sorted(
            (
                math.log((corpus_size + 1) / (document_frequency[term] + 0.5)) + 1.0
                for term in terms_by_chunk[_chunk_key(item)]
            ),
            reverse=True,
        )
        return sum(weights[:12]), len(weights)

    return sorted(
        (dict(item) for item in records),
        key=lambda item: (
            len(item.get("requiredDistinguishingAnchors") or []),
            -quality(item)[0],
            -quality(item)[1],
            FORMATS.index(str(item["format"])),
            SPLITS.index(str(item["split"])),
            str(item["filename"]),
            int(item["chunkIndex"]),
        ),
    )


def _chunk_key(item: Mapping[str, Any]) -> tuple[str, int]:
    source_identity = (
        str(item.get("sourceSha256") or "")
        or str(item.get("referenceChunkId") or "")
        or str(item.get("evidenceId") or "")
        or str(item.get("filename") or "")
    )
    return source_identity, int(item.get("chunkIndex") or 0)
