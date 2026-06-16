"""Prepare evidence-only Gemini rewrite prompts for failed B3.3 review rows."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from chatagent_eval.question_set import read_json, read_jsonl, write_json


def write_rewrite_batches(
    candidates: dict,
    evidence_index: dict,
    review_rows: list[dict],
    output_dir: Path,
    batch_size: int,
    only_evidence_ids: set[str] | None = None,
) -> dict:
    candidate_by_id = {item["id"]: item for item in candidates.get("questions") or []}
    evidence_by_id = {item["evidenceId"]: item for item in evidence_index.get("records") or []}
    failed = []
    for review in review_rows:
        if review.get("decision") != "failed-review":
            continue
        candidate = candidate_by_id[str(review["id"])]
        if only_evidence_ids is not None and candidate["evidenceId"] not in only_evidence_ids:
            continue
        evidence = evidence_by_id[str(candidate["evidenceId"])]
        failed.append({
            "evidenceId": candidate["evidenceId"],
            "originalQuestion": candidate["question"],
            "referenceNeedle": candidate["referenceNeedle"],
            "referenceAnswer": candidate["referenceAnswer"],
            "referenceContent": evidence["referenceContent"],
            "reviewNotes": review["reviewNotes"],
        })
    output_dir.mkdir(parents=True, exist_ok=True)
    files = []
    for offset in range(0, len(failed), batch_size):
        path = output_dir / f"rewrite-batch-{offset // batch_size + 1:02d}.md"
        path.write_text(
            "Rewrite every failed question so it is answerable solely from the supplied referenceContent, "
            "stands alone without referring to a report/document/table/current filing/registrant or other "
            "missing context, and does not reveal or copy the referenceAnswer or referenceNeedle. Do not copy "
            "the answer, but include at least two meaningful non-answer anchors from referenceContent that make "
            "the question uniquely identifiable among similar records. Avoid generic boilerplate and dates unless "
            "other non-answer anchors uniquely identify the event. Do not copy "
            "any distinctive phrase of four or more characters from referenceNeedle/referenceAnswer into the "
            "question. Do not name organizations, publishers, companies, pharmacies, filenames, source groups, "
            "or source-specific titles; describe only the table/record type and the dimensions needed to locate "
            "the answer. If referenceNeedle/referenceAnswer contains a short numeric value, avoid every digit in "
            "the question and identify the row using non-numeric attributes instead. Preserve "
            "the supplied referenceNeedle and referenceAnswer unless they are unsupported. Return ONLY a JSON "
            "array with exact keys evidenceId, question, referenceNeedle, referenceAnswer. Do not add markdown "
            "or omit items.\n\n"
            + json.dumps(failed[offset:offset + batch_size], ensure_ascii=False, indent=2)
            + "\n",
            encoding="utf-8",
        )
        files.append(str(path.resolve()))
    manifest = {"failedCount": len(failed), "batchSize": batch_size, "files": files}
    write_json(output_dir / "manifest.json", manifest)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--evidence-index", type=Path, required=True)
    parser.add_argument("--review-receipt", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=5)
    parser.add_argument("--only-evidence-id", action="append")
    args = parser.parse_args()
    write_rewrite_batches(
        read_json(args.candidates),
        read_json(args.evidence_index),
        read_jsonl(args.review_receipt),
        args.output_dir,
        args.batch_size,
        set(args.only_evidence_id) if args.only_evidence_id else None,
    )


if __name__ == "__main__":
    main()
