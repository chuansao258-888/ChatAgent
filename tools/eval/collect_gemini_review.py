"""Collect Gemini CLI review responses into a completed, replayable review receipt."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from chatagent_eval.question_set import parse_gemini_output, read_json, write_jsonl
from prepare_question_review import REVIEW_KEYS, build_review_rows


OUTPUT_KEYS = REVIEW_KEYS - {
    "evidenceId", "question", "referenceNeedle", "referenceAnswer", "referenceContent"
}
REVIEW_BOOLEAN_KEYS = {
    "questionAnswerableFromEvidence",
    "referenceAnswerSupported",
    "referenceAnswerAddressesQuestion",
    "standaloneAndUnambiguous",
    "noSourceOrReferenceLeak",
}


def validate_output_row(row: dict, path: Path) -> None:
    if set(row) != OUTPUT_KEYS:
        raise ValueError(f"Gemini review output must contain exactly {sorted(OUTPUT_KEYS)}: {path}")
    if row.get("decision") not in {"llm-reviewed", "failed-review"}:
        raise ValueError(f"Gemini review output decision must be final: {path}")
    for field in ("id", "reviewerModel", "reviewerTool", "reviewerPromptVersion", "reviewNotes"):
        if not isinstance(row.get(field), str):
            raise ValueError(f"Gemini review output {field} must be a string: {path}")
    for field in ("id", "reviewerModel", "reviewerTool", "reviewerPromptVersion"):
        if not row[field].strip():
            raise ValueError(f"Gemini review output {field} must be non-empty: {path}")
    if not all(isinstance(row.get(field), bool) for field in REVIEW_BOOLEAN_KEYS):
        raise ValueError(f"Gemini review output booleans must be explicit: {path}")
    if (row["decision"] == "llm-reviewed") != all(row[field] for field in REVIEW_BOOLEAN_KEYS):
        raise ValueError(f"Gemini review output decision does not match booleans: {path}")


def collect_review_rows(candidates: dict, evidence_index: dict, response_dirs: list[Path]) -> list[dict]:
    prepared = build_review_rows(candidates, evidence_index)
    prepared_by_id = {row["id"]: row for row in prepared}
    latest: dict[str, dict] = {}
    for directory in response_dirs:
        for path in sorted(directory.glob("*.json")):
            if path.stat().st_size == 0:
                continue
            rows = parse_gemini_output(json.loads(path.read_text(encoding="utf-8-sig")))
            seen_in_file = set()
            for row in rows:
                validate_output_row(row, path)
                item_id = str(row.get("id") or "")
                if item_id not in prepared_by_id or item_id in seen_in_file:
                    raise ValueError(f"Gemini review output has unknown/duplicate id: {item_id}")
                seen_in_file.add(item_id)
                latest[item_id] = row
    if set(latest) != set(prepared_by_id):
        missing = sorted(set(prepared_by_id) - set(latest))
        raise ValueError(f"Gemini review output does not cover every candidate: {missing[:5]}")
    result = []
    for prepared_row in prepared:
        completed = dict(prepared_row)
        completed.update(latest[prepared_row["id"]])
        result.append(completed)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--evidence-index", type=Path, required=True)
    parser.add_argument("--responses-dir", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    write_jsonl(
        args.output,
        collect_review_rows(read_json(args.candidates), read_json(args.evidence_index), args.responses_dir),
    )


if __name__ == "__main__":
    main()
