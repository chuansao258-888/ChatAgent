"""Prepare an evidence-only Gemini-review worksheet for B3.3 candidates."""

from __future__ import annotations

import argparse
from pathlib import Path

from chatagent_eval.question_set import read_json, write_jsonl


REVIEW_KEYS = {
    "id",
    "evidenceId",
    "question",
    "referenceNeedle",
    "referenceAnswer",
    "referenceContent",
    "decision",
    "reviewerModel",
    "reviewerTool",
    "reviewerPromptVersion",
    "questionAnswerableFromEvidence",
    "referenceAnswerSupported",
    "referenceAnswerAddressesQuestion",
    "standaloneAndUnambiguous",
    "noSourceOrReferenceLeak",
    "reviewNotes",
}


def build_review_rows(candidates: dict, evidence_index: dict) -> list[dict]:
    if candidates.get("evidenceExportDatasetHash") != evidence_index.get("evidenceExportDatasetHash"):
        raise ValueError("candidate and evidence-index dataset hashes do not match")
    evidence_by_id = {str(item["evidenceId"]): item for item in evidence_index.get("records") or []}
    rows = []
    seen = set()
    for item in candidates.get("questions") or []:
        item_id = str(item.get("id") or "")
        if not item_id or item_id in seen:
            raise ValueError(f"candidate IDs must be unique and non-empty: {item_id}")
        seen.add(item_id)
        evidence = evidence_by_id.get(str(item.get("evidenceId") or ""))
        if evidence is None:
            raise ValueError(f"candidate has no frozen evidence binding: {item_id}")
        rows.append({
            "id": item_id,
            "evidenceId": item["evidenceId"],
            "question": item["question"],
            "referenceNeedle": item["referenceNeedle"],
            "referenceAnswer": item["referenceAnswer"],
            "referenceContent": evidence["referenceContent"],
            "decision": "pending",
            "reviewerModel": "",
            "reviewerTool": "",
            "reviewerPromptVersion": "",
            "questionAnswerableFromEvidence": None,
            "referenceAnswerSupported": None,
            "referenceAnswerAddressesQuestion": None,
            "standaloneAndUnambiguous": None,
            "noSourceOrReferenceLeak": None,
            "reviewNotes": "",
        })
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--evidence-index", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    write_jsonl(args.output, build_review_rows(read_json(args.candidates), read_json(args.evidence_index)))


if __name__ == "__main__":
    main()
