"""Apply completed B3.3 Gemini-review decisions without permitting content edits."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from chatagent_eval.question_set import canonical_hash, read_json, read_jsonl, write_json
from prepare_question_review import REVIEW_KEYS


def apply_review_decisions(candidates: dict, evidence_index: dict, review_rows: list[dict]) -> dict:
    if candidates.get("evidenceExportDatasetHash") != evidence_index.get("evidenceExportDatasetHash"):
        raise ValueError("candidate and evidence-index dataset hashes do not match")
    candidate_by_id = {str(item["id"]): item for item in candidates.get("questions") or []}
    if len(candidate_by_id) != len(candidates.get("questions") or []):
        raise ValueError("candidate IDs must be unique")
    evidence_by_id = {str(item["evidenceId"]): item for item in evidence_index.get("records") or []}
    reviewed_by_id = {}
    immutable_fields = ("id", "evidenceId", "question", "referenceNeedle", "referenceAnswer")
    review_booleans = (
        "questionAnswerableFromEvidence",
        "referenceAnswerSupported",
        "referenceAnswerAddressesQuestion",
        "standaloneAndUnambiguous",
        "noSourceOrReferenceLeak",
    )
    for row in review_rows:
        if set(row) != REVIEW_KEYS:
            raise ValueError(f"review row must contain exactly {sorted(REVIEW_KEYS)}")
        item_id = str(row.get("id") or "")
        candidate = candidate_by_id.get(item_id)
        if candidate is None or item_id in reviewed_by_id:
            raise ValueError(f"review row has unknown/duplicate id: {item_id}")
        evidence = evidence_by_id.get(str(candidate.get("evidenceId") or ""))
        if evidence is None:
            raise ValueError(f"candidate has no frozen evidence binding: {item_id}")
        for field in immutable_fields:
            if row.get(field) != candidate.get(field):
                raise ValueError(f"review row changed immutable field: {item_id}/{field}")
        if row.get("referenceContent") != evidence.get("referenceContent"):
            raise ValueError(f"review row changed frozen evidence: {item_id}")
        if row.get("decision") not in {"llm-reviewed", "failed-review"}:
            raise ValueError(f"review row decision must be final: {item_id}")
        for field in ("reviewerModel", "reviewerTool", "reviewerPromptVersion"):
            if not str(row.get(field) or "").strip():
                raise ValueError(f"review row {field} is required: {item_id}")
        if not all(isinstance(row.get(field), bool) for field in review_booleans):
            raise ValueError(f"review row booleans must be explicit: {item_id}")
        if (row["decision"] == "llm-reviewed") != all(row[field] for field in review_booleans):
            raise ValueError(f"review row decision does not match booleans: {item_id}")
        if not isinstance(row.get("reviewNotes"), str):
            raise ValueError(f"review row reviewNotes must be a string: {item_id}")
        reviewed_by_id[item_id] = row
    if set(reviewed_by_id) != set(candidate_by_id):
        missing = sorted(set(candidate_by_id) - set(reviewed_by_id))
        raise ValueError(f"every candidate requires a Gemini review decision: {missing[:5]}")

    result = json.loads(json.dumps(candidates, ensure_ascii=False))
    for item in result["questions"]:
        review = reviewed_by_id[item["id"]]
        item["auditStatus"] = review["decision"]
        if item.get("generationMethod") not in {
            "llm-generated-llm-reviewed-v1",
            "codex-manual-assisted-llm-reviewed-v1",
        }:
            raise ValueError(f"candidate has invalid reviewed generationMethod: {item['id']}")
        item["llmProvenance"].update({
            "reviewerModel": review["reviewerModel"].strip(),
            "reviewerTool": review["reviewerTool"].strip(),
            "reviewerPromptVersion": review["reviewerPromptVersion"].strip(),
        })
    result["reviewReceiptHash"] = canonical_hash(review_rows)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--evidence-index", type=Path, required=True)
    parser.add_argument("--review", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = apply_review_decisions(
        read_json(args.candidates),
        read_json(args.evidence_index),
        read_jsonl(args.review),
    )
    write_json(args.output, result)


if __name__ == "__main__":
    main()
