"""Merge validated Gemini rewrites into the B3.3 candidate root."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from chatagent_eval.question_set import (
    build_candidates,
    parse_gemini_output,
    read_json,
    read_jsonl,
    validate_question_collection,
    write_json,
)


def merge_rewrites(
    candidates: dict,
    evidence_index: dict,
    review_rows: list[dict],
    response_dirs: list[Path],
    generator_model: str,
) -> dict:
    failed_ids = {str(row["id"]) for row in review_rows if row.get("decision") == "failed-review"}
    candidate_by_id = {str(item["id"]): item for item in candidates.get("questions") or []}
    expected_evidence_ids = {str(candidate_by_id[item_id]["evidenceId"]) for item_id in failed_ids}
    latest: dict[str, dict] = {}
    for responses_dir in response_dirs:
        for path in sorted(responses_dir.glob("*.json")):
            if path.stat().st_size == 0:
                continue
            seen = set()
            for row in parse_gemini_output(json.loads(path.read_text(encoding="utf-8-sig"))):
                evidence_id = str(row.get("evidenceId") or "")
                if evidence_id not in expected_evidence_ids or evidence_id in seen:
                    raise ValueError(f"rewrite response has unknown/duplicate evidenceId: {path.name}/{evidence_id}")
                seen.add(evidence_id)
                latest[evidence_id] = row
    if set(latest) != expected_evidence_ids:
        missing = sorted(expected_evidence_ids - set(latest))
        raise ValueError(f"rewrite responses do not cover every failed item: {missing[:5]}")
    rewritten = build_candidates(
        list(latest.values()), evidence_index, generator_model=generator_model, enforce_quotas=False
    )
    replacement_by_id = {item["id"]: item for item in rewritten["questions"]}
    merged_questions = [
        replacement_by_id.get(str(item["id"]), item)
        for item in candidates.get("questions") or []
    ]
    validate_question_collection(merged_questions, enforce_quotas=True)
    result = dict(candidates)
    result["questions"] = merged_questions
    result["generatedBy"] = {"model": generator_model, "tool": "gemini-cli"}
    result.pop("reviewReceiptHash", None)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--evidence-index", type=Path, required=True)
    parser.add_argument("--review-receipt", type=Path, required=True)
    parser.add_argument("--responses-dir", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--generator-model", default="gemini-3.1-pro-preview")
    args = parser.parse_args()
    write_json(
        args.output,
        merge_rewrites(
            read_json(args.candidates),
            read_json(args.evidence_index),
            read_jsonl(args.review_receipt),
            args.responses_dir,
            args.generator_model,
        ),
    )


if __name__ == "__main__":
    main()
