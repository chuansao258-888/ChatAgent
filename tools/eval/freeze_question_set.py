"""Freeze fully Gemini-reviewed B3.3 questions and write the immutable manifest."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
from pathlib import Path

from chatagent_eval.question_set import (
    LOWER_HEX_SHA256_PATTERN,
    canonical_hash,
    load_evidence_export,
    question_counts,
    read_json,
    read_jsonl,
    split_hashes,
    validate_question,
    validate_question_collection,
    validate_question_corpus_grounding,
    write_json,
)
from apply_question_review import apply_review_decisions
from validate_gemini_questions import validate_manual_replacement_receipt


def freeze_question_set(
    candidates: dict,
    *,
    generated_by: str,
    evidence_index: dict | None = None,
    review_rows: list[dict] | None = None,
    manual_replacement: dict | None = None,
) -> tuple[dict, dict, dict]:
    evidence_manifest_path = Path(str(candidates.get("evidenceExportManifestPath") or ""))
    evidence_hash = str(candidates.get("evidenceExportDatasetHash") or "")
    if not evidence_manifest_path.exists() or not evidence_hash:
        raise ValueError("frozen evidence-export identity is required")
    questions = list(candidates.get("questions") or [])
    pending_holdout = [
        item.get("id") for item in questions
        if item.get("split") == "holdout" and item.get("auditStatus") == "pending"
    ]
    if pending_holdout:
        raise ValueError(f"pending holdout questions block freeze: {pending_holdout[:5]}")
    review_receipt_hash = str(candidates.get("reviewReceiptHash") or "")
    if not LOWER_HEX_SHA256_PATTERN.fullmatch(review_receipt_hash):
        raise ValueError("valid reviewReceiptHash is required before freeze")
    if review_rows is None:
        raise ValueError("Gemini review receipt rows are required before freeze")
    if canonical_hash(review_rows) != review_receipt_hash:
        raise ValueError("Gemini review receipt rows do not match reviewReceiptHash")
    if evidence_index is None:
        evidence_index = load_evidence_export(evidence_manifest_path.resolve().parent)
    replayed_candidates = apply_review_decisions(candidates, evidence_index, review_rows)
    if canonical_hash(replayed_candidates) != canonical_hash(candidates):
        raise ValueError("Gemini review receipt does not reproduce the reviewed candidates")
    accepted = [dict(item) for item in questions if item.get("auditStatus") == "llm-reviewed"]
    drafts = [dict(item) for item in questions if item.get("auditStatus") != "llm-reviewed"]
    for item in accepted:
        provenance = item.get("llmProvenance") or {}
        for field in (
            "generatorModel", "generatorTool", "reviewerModel", "reviewerTool", "reviewerPromptVersion"
        ):
            if not str(provenance.get(field) or "").strip():
                raise ValueError(f"llm-reviewed question missing {field}: {item.get('id')}")
    if evidence_index.get("evidenceExportDatasetHash") != evidence_hash:
        raise ValueError("frozen evidence-export dataset hash does not match candidates")
    evidence_by_id = {str(item["evidenceId"]): item for item in evidence_index.get("records") or []}
    for item in accepted:
        evidence = evidence_by_id.get(str(item.get("evidenceId") or ""))
        if evidence is None:
            raise ValueError(f"accepted question has no frozen evidence binding: {item.get('id')}")
        for field in (
            "evidenceId", "filename", "sourceSha256", "format", "split",
            "chunkIndex", "referenceChunkId",
        ):
            if item.get(field) != evidence.get(field):
                raise ValueError(f"accepted question changed frozen evidence binding: {item.get('id')}/{field}")
        if item.get("generationMethod") not in {
            "llm-generated-llm-reviewed-v1",
            "codex-manual-assisted-llm-reviewed-v1",
        }:
            raise ValueError(f"accepted question has invalid generationMethod: {item.get('id')}")
        if not all(str(provenance.get(field) or "").strip() for field in ("generatorModel", "generatorTool")):
            raise ValueError(f"accepted question has incomplete llmProvenance: {item.get('id')}")
        validate_question(item, evidence)
    validate_question_collection(accepted, enforce_quotas=True)
    validate_question_corpus_grounding(accepted, evidence_index)
    base = {"version": "manual-accepted-questions-v1", "questions": accepted}
    draft_root = {"version": "manual-accepted-questions-v1-drafts", "questions": drafts}
    manifest = {
        "version": "manual-accepted-questions-v1.manifest",
        "baseHash": canonical_hash(base),
        **split_hashes(base),
        "evidenceExportManifestPath": str(evidence_manifest_path.resolve()),
        "evidenceExportDatasetHash": evidence_hash,
        **question_counts(accepted),
        "generatedBy": generated_by,
        "frozenAt": datetime.now(timezone.utc).isoformat(),
    }
    manual_replacements = [
        item for item in accepted
        if item.get("generationMethod") == "codex-manual-assisted-llm-reviewed-v1"
    ]
    expected_manual_count = int(candidates.get("manualReplacementCount") or 0)
    receipt_hash = str(candidates.get("manualReplacementReceiptHash") or "")
    if len(manual_replacements) != expected_manual_count or expected_manual_count not in {0, 1}:
        raise ValueError("accepted manual replacement count does not match candidate contract")
    if expected_manual_count == 1:
        if not LOWER_HEX_SHA256_PATTERN.fullmatch(receipt_hash):
            raise ValueError("valid manualReplacementReceiptHash is required")
        if manual_replacement is None:
            raise ValueError("manual replacement receipt is required before freeze")
        if canonical_hash(manual_replacement) != receipt_hash:
            raise ValueError("manual replacement receipt does not match manualReplacementReceiptHash")
        expected_row = validate_manual_replacement_receipt(manual_replacement, set(evidence_by_id))
        actual_row = manual_replacements[0]
        for field in ("evidenceId", "question", "referenceNeedle", "referenceAnswer"):
            if actual_row.get(field) != expected_row.get(field):
                raise ValueError(f"manual replacement receipt does not reproduce accepted field: {field}")
        provenance = actual_row.get("llmProvenance") or {}
        for field in ("generatorModel", "generatorTool"):
            if provenance.get(field) != manual_replacement.get(field):
                raise ValueError(f"manual replacement receipt does not reproduce accepted provenance: {field}")
        manifest["manualReplacementCount"] = 1
        manifest["manualReplacementReceiptHash"] = receipt_hash
    elif manual_replacement is not None:
        raise ValueError("manual replacement receipt supplied without an accepted replacement")
    manifest["reviewReceiptHash"] = review_receipt_hash
    return base, draft_root, manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--accepted", type=Path, required=True)
    parser.add_argument("--drafts", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--review-receipt", type=Path)
    parser.add_argument("--manual-replacement-receipt", type=Path)
    parser.add_argument("--evidence-index", type=Path)
    parser.add_argument("--generated-by", default="freeze_question_set.py")
    args = parser.parse_args()
    base, drafts, manifest = freeze_question_set(
        read_json(args.candidates),
        generated_by=args.generated_by,
        evidence_index=read_json(args.evidence_index) if args.evidence_index else None,
        review_rows=read_jsonl(args.review_receipt) if args.review_receipt else None,
        manual_replacement=read_json(args.manual_replacement_receipt) if args.manual_replacement_receipt else None,
    )
    write_json(args.accepted, base)
    write_json(args.drafts, drafts)
    write_json(args.manifest, manifest)


if __name__ == "__main__":
    main()
