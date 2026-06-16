"""Prepare Gemini prompts that diversify near-duplicate B3.3 rewrite questions."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from chatagent_eval.question_set import (
    build_candidates,
    levenshtein_similarity,
    parse_gemini_output,
    read_json,
    read_jsonl,
    write_json,
)


def latest_rewrites(response_dirs: list[Path]) -> dict[str, dict]:
    latest = {}
    for directory in response_dirs:
        for path in sorted(directory.glob("*.json")):
            if path.stat().st_size == 0:
                continue
            for row in parse_gemini_output(json.loads(path.read_text(encoding="utf-8-sig"))):
                latest[str(row["evidenceId"])] = row
    return latest


def write_diversify_batches(
    candidates: dict,
    evidence_index: dict,
    review_rows: list[dict],
    response_dirs: list[Path],
    output_dir: Path,
) -> dict:
    failed_ids = {str(row["id"]) for row in review_rows if row.get("decision") == "failed-review"}
    rewrites = latest_rewrites(response_dirs)
    replacements = {}
    for row in rewrites.values():
        question = build_candidates([row], evidence_index, generator_model="gemini-3.1-pro-preview", enforce_quotas=False)
        replacements[question["questions"][0]["id"]] = question["questions"][0]
    merged = [replacements.get(str(item["id"]), item) for item in candidates.get("questions") or []]
    targets: dict[str, str] = {}
    for index, left in enumerate(merged):
        for right in merged[index + 1:]:
            if levenshtein_similarity(str(left["question"]), str(right["question"])) < 0.85:
                continue
            target = right if str(right["id"]) in failed_ids else left
            conflict = left if target is right else right
            targets.setdefault(str(target["id"]), str(conflict["question"]))
    evidence_by_id = {str(item["evidenceId"]): item for item in evidence_index.get("records") or []}
    question_by_id = {str(item["id"]): item for item in merged}
    output_dir.mkdir(parents=True, exist_ok=True)
    files = []
    for index, (item_id, conflict_question) in enumerate(sorted(targets.items()), 1):
        item = question_by_id[item_id]
        evidence = evidence_by_id[str(item["evidenceId"])]
        payload = {
            "evidenceId": item["evidenceId"],
            "currentQuestion": item["question"],
            "conflictingQuestion": conflict_question,
            "referenceNeedle": item["referenceNeedle"],
            "referenceAnswer": item["referenceAnswer"],
            "referenceContent": evidence["referenceContent"],
        }
        path = output_dir / f"diversify-{index:02d}.md"
        path.write_text(
            "Rewrite currentQuestion into a substantially different standalone question that remains answerable "
            "only from referenceContent. Its wording and sentence structure must have less than 0.85 similarity "
            "to conflictingQuestion. Do not name organizations, publishers, companies, filenames, source groups, "
            "or source-specific titles. Do not reveal/copy referenceNeedle or referenceAnswer. Return ONLY a JSON "
            "array with one object and exact keys evidenceId, question, referenceNeedle, referenceAnswer.\n\n"
            + json.dumps(payload, ensure_ascii=False, indent=2)
            + "\n",
            encoding="utf-8",
        )
        files.append(str(path.resolve()))
    manifest = {"targetCount": len(targets), "files": files}
    write_json(output_dir / "manifest.json", manifest)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--evidence-index", type=Path, required=True)
    parser.add_argument("--review-receipt", type=Path, required=True)
    parser.add_argument("--responses-dir", type=Path, action="append", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    write_diversify_batches(
        read_json(args.candidates),
        read_json(args.evidence_index),
        read_jsonl(args.review_receipt),
        args.responses_dir,
        args.output_dir,
    )


if __name__ == "__main__":
    main()
