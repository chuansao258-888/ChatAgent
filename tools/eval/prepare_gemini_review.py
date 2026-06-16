"""Write blinded Gemini CLI review prompts for B3.3 candidate rows."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from chatagent_eval.question_set import read_json, read_jsonl, write_json
from prepare_question_review import build_review_rows


PROMPT_VERSION = "b3.3-gemini-review-v1"


def write_review_batches(
    candidates: dict,
    evidence_index: dict,
    output_dir: Path,
    batch_size: int,
    only_ids: set[str] | None = None,
    reviewer_model: str = "gemini-3.1-pro-preview",
) -> dict:
    rows = build_review_rows(candidates, evidence_index)
    if only_ids is not None:
        rows = [row for row in rows if row["id"] in only_ids]
    output_dir.mkdir(parents=True, exist_ok=True)
    files = []
    for offset in range(0, len(rows), batch_size):
        batch = rows[offset:offset + batch_size]
        path = output_dir / f"review-batch-{offset // batch_size + 1:02d}.md"
        payload = [
            {key: row[key] for key in (
                "id", "evidenceId", "question", "referenceNeedle", "referenceAnswer", "referenceContent"
            )}
            for row in batch
        ]
        path.write_text(
            "Review each item using only its supplied evidence. Return ONLY a JSON array, one object per "
            "input item, in the same order. Copy every id exactly, character for character; never shorten, "
            "normalize, or otherwise alter an id. Do not add markdown. Required exact keys: id, decision, "
            "reviewerModel, reviewerTool, reviewerPromptVersion, questionAnswerableFromEvidence, "
            "referenceAnswerSupported, referenceAnswerAddressesQuestion, standaloneAndUnambiguous, "
            f"noSourceOrReferenceLeak, reviewNotes. Set reviewerModel to {reviewer_model}, reviewerTool "
            "to gemini-cli, reviewerPromptVersion to b3.3-gemini-review-v1. decision is llm-reviewed only "
            "when all five booleans are true; otherwise failed-review. Be strict about answerability, factual "
            "support, ambiguity, and any source/reference-answer leakage. A standaloneAndUnambiguous=true question "
            "must contain enough non-answer context to identify the requested fact without phrases such as "
            "'current report', 'provided document', 'this filing', or 'the table'.\n\n"
            + json.dumps(payload, ensure_ascii=False, indent=2)
            + "\n",
            encoding="utf-8",
        )
        files.append(str(path.resolve()))
    manifest = {
        "promptVersion": PROMPT_VERSION,
        "reviewerModel": reviewer_model,
        "totalRows": len(rows),
        "batchSize": batch_size,
        "files": files,
    }
    write_json(output_dir / "manifest.json", manifest)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--evidence-index", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=25)
    parser.add_argument("--only-failed-from", type=Path)
    parser.add_argument("--only-id", action="append")
    parser.add_argument("--reviewer-model", default="gemini-3.1-pro-preview")
    args = parser.parse_args()
    only_ids = None
    if args.only_failed_from:
        only_ids = {
            str(row["id"]) for row in read_jsonl(args.only_failed_from)
            if row.get("decision") == "failed-review"
        }
    if args.only_id:
        requested = set(args.only_id)
        only_ids = requested if only_ids is None else only_ids & requested
    write_review_batches(
        read_json(args.candidates),
        read_json(args.evidence_index),
        args.output_dir,
        args.batch_size,
        only_ids,
        args.reviewer_model,
    )


if __name__ == "__main__":
    main()
