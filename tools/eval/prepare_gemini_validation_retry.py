"""Prepare evidence-only Gemini retries for deterministic B3.3 validation failures."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

from chatagent_eval.question_set import (
    _chunk_key,
    _corpus_terms,
    build_candidates,
    corpus_anchor_plan,
    parse_gemini_output,
    question_collection_retry_feedback,
    read_json,
    write_json,
)
from validate_gemini_questions import apply_manual_replacement

NEUTRAL_RETRY_TERMS = (
    "accompanies",
    "amount",
    "answer",
    "category",
    "corresponds",
    "designation",
    "detail",
    "duration",
    "entry",
    "label",
    "location",
    "name",
    "pertains",
    "postal",
    "postcode",
    "quantity",
    "requested",
    "role",
    "sought",
    "status",
    "title",
    "type",
    "value",
    "wording",
)


def load_expected_latest_rows(response_dirs: list[Path], expected_ids: set[str]) -> dict[str, dict]:
    latest: dict[str, dict] = {}
    for directory in response_dirs:
        for path in sorted(directory.glob("*.json")):
            if path.stat().st_size == 0:
                continue
            seen: set[str] = set()
            for row in parse_gemini_output(json.loads(path.read_text(encoding="utf-8-sig"))):
                evidence_id = str(row.get("evidenceId") or "")
                if not evidence_id or evidence_id in seen:
                    raise ValueError(f"response file has missing/duplicate evidenceId: {path.name}/{evidence_id}")
                if evidence_id not in expected_ids:
                    raise ValueError(f"response file has unknown evidenceId: {path.name}/{evidence_id}")
                seen.add(evidence_id)
                latest[evidence_id] = row
    return latest


def write_validation_retry_batches(
    evidence_index: dict,
    response_dirs: list[Path],
    output_dir: Path,
    batch_size: int,
    generator_model: str,
    manual_replacement: dict | None = None,
) -> dict:
    evidence_by_id = {
        str(item["evidenceId"]): dict(item)
        for item in evidence_index.get("records") or []
    }
    frozen_corpus_terms = {
        term
        for evidence in evidence_by_id.values()
        for term in _corpus_terms(str(evidence.get("referenceContent") or ""))
    }
    latest = load_expected_latest_rows(response_dirs, set(evidence_by_id))
    if manual_replacement is not None:
        latest = {
            str(row["evidenceId"]): row
            for row in apply_manual_replacement(list(latest.values()), manual_replacement, set(evidence_by_id))
        }
    failures = []
    replacement_required = []
    reason_counts: Counter[str] = Counter()
    feedback_by_id: dict[str, str] = {}
    individually_valid = []
    for evidence_id, evidence in evidence_by_id.items():
        previous = latest.get(evidence_id)
        if previous is None:
            feedback_by_id[evidence_id] = "missing response"
            continue
        try:
            result = build_candidates(
                [previous],
                evidence_index,
                generator_model=generator_model,
                enforce_quotas=False,
                manual_replacement=(
                    manual_replacement
                    if manual_replacement is not None
                    and evidence_id == str(manual_replacement.get("evidenceId") or "")
                    else None
                ),
            )
            individually_valid.extend(result["questions"])
        except ValueError as exc:
            feedback_by_id[evidence_id] = str(exc).split(": q-", 1)[0]
    for item_id, feedback in question_collection_retry_feedback(individually_valid).items():
        feedback_by_id.setdefault(item_id.removeprefix("q-"), feedback)
    reserved_spans_by_id: dict[str, list[str]] = {}
    seen_spans_by_chunk: dict[tuple[str, int], list[str]] = {}
    for item in individually_valid:
        key = _chunk_key(item)
        needle = str(item.get("referenceNeedle") or "")
        item_id = str(item.get("id") or "").removeprefix("q-")
        seen_spans = seen_spans_by_chunk.setdefault(key, [])
        if needle in seen_spans:
            reserved_spans_by_id[item_id] = list(seen_spans)
        else:
            seen_spans.append(needle)

    for evidence_id, evidence in evidence_by_id.items():
        feedback = feedback_by_id.get(evidence_id)
        if feedback is None:
            continue
        previous = latest.get(evidence_id)
        reason_counts[feedback] += 1
        excluded_texts = []
        if previous is not None:
            excluded_texts.extend([
                str(previous.get("referenceNeedle") or ""),
                str(previous.get("referenceAnswer") or ""),
            ])
        reserved_answer_spans = reserved_spans_by_id.get(evidence_id, [])
        excluded_texts.extend(reserved_answer_spans)
        anchor_plan = corpus_anchor_plan(
            evidence,
            evidence_index,
            excluded_texts=excluded_texts,
        )
        if feedback == "question is not uniquely grounded in the frozen evidence corpus" and not anchor_plan[
            "coversEveryCompetingChunk"
        ]:
            replacement_required.append(evidence_id)
            continue
        avoid_content_terms = [
            term
            for term in anchor_plan["candidateAnchors"]
            if term not in anchor_plan["requiredAnchors"]
        ]
        if feedback == "question is not uniquely grounded in the frozen evidence corpus" and previous is not None:
            avoid_content_terms.extend(
                term
                for term in _corpus_terms(str(previous.get("question") or ""))
                if term not in anchor_plan["requiredAnchors"]
            )
        failures.append({
            "evidenceId": evidence_id,
            "previousResponse": previous,
            "validationFeedback": feedback,
            "reservedAnswerSpans": reserved_answer_spans,
            "requiredDistinguishingAnchors": anchor_plan["requiredAnchors"],
            "distinguishingAnchorCandidates": anchor_plan["candidateAnchors"],
            "avoidContentTerms": (
                list(dict.fromkeys(avoid_content_terms))
                if feedback == "question is not uniquely grounded in the frozen evidence corpus"
                else []
            ),
            "safeNeutralTerms": (
                [
                    term
                    for term in NEUTRAL_RETRY_TERMS
                    if term not in frozen_corpus_terms and term not in avoid_content_terms
                ]
                if feedback == "question is not uniquely grounded in the frozen evidence corpus"
                else []
            ),
            "referenceContent": evidence["referenceContent"],
        })

    output_dir.mkdir(parents=True, exist_ok=True)
    files = []
    for offset in range(0, len(failures), batch_size):
        path = output_dir / f"retry-batch-{offset // batch_size + 1:02d}.md"
        path.write_text(
            "Generate a corrected result for every item using only its referenceContent and validationFeedback. "
            "Return ONLY a JSON array in the same order with exact keys evidenceId, question, referenceNeedle, "
            "referenceAnswer. Do not omit items or invent IDs. referenceNeedle must be copied character-for-character "
            "from referenceContent. The question must be natural, standalone, answerable only from referenceContent, "
            "must not copy/reveal referenceNeedle or referenceAnswer, and must not mention a source, file, document, "
            "format, URL, evidence, excerpt, table, or identifier. Include at least two meaningful non-answer anchors "
            "from referenceContent that uniquely distinguish the requested fact from similar records. Avoid generic "
            "boilerplate, generic dates, and missing-context phrases such as 'current report', 'provided document', "
            "'this filing', or 'the table'. distinguishingAnchorCandidates are high-distinctiveness terms computed "
            "only from the frozen evidence corpus after excluding the previous answer, needle, and source identity. "
            "The question MUST include every exact term in requiredDistinguishingAnchors naturally and must not "
            "mention either anchor list itself. Required anchors must remain non-answer context: choose a fact whose "
            "referenceNeedle and referenceAnswer do not contain them. When validationFeedback says the question is "
            "not uniquely grounded, keep the question concise and avoid other content-specific terms unless they are "
            "needed for natural grammar; extra common terms can make a competing chunk score higher. Additional "
            "distinguishingAnchorCandidates may also be used only when required anchors are insufficient. Do not use "
            "any exact term in avoidContentTerms; those optional corpus terms made a competing chunk score too highly. "
            "Do not use any digits unless an exact four-digit year appears in requiredDistinguishingAnchors. For a "
            "not-uniquely-grounded retry, every non-stopword content term in the question must be either a required "
            "anchor or an exact safeNeutralTerms value; safeNeutralTerms are verified absent from the frozen corpus. "
            "When validationFeedback says questions from one chunk must use distinct answer spans, choose a different "
            "referenceNeedle and referenceAnswer fact from referenceContent than every exact reservedAnswerSpans value "
            "and previousResponse; rewording an already reserved fact is not a correction.\n\n"
            + json.dumps(failures[offset:offset + batch_size], ensure_ascii=False, indent=2)
            + "\n",
            encoding="utf-8",
        )
        files.append(str(path.resolve()))
    manifest = {
        "failedCount": len(failures) + len(replacement_required),
        "retryableCount": len(failures),
        "replacementRequiredCount": len(replacement_required),
        "replacementRequiredEvidenceIds": replacement_required,
        "missingCount": reason_counts["missing response"],
        "validationFeedbackCounts": dict(reason_counts),
        "batchSize": batch_size,
        "files": files,
    }
    write_json(output_dir / "manifest.json", manifest)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-index", type=Path, required=True)
    parser.add_argument("--responses-dir", type=Path, action="append", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=5)
    parser.add_argument("--generator-model", default="gemini-2.5-flash")
    parser.add_argument("--manual-replacement", type=Path)
    args = parser.parse_args()
    write_validation_retry_batches(
        read_json(args.evidence_index),
        args.responses_dir,
        args.output_dir,
        args.batch_size,
        args.generator_model,
        read_json(args.manual_replacement) if args.manual_replacement else None,
    )


if __name__ == "__main__":
    main()
