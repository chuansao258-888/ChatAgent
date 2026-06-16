"""Validate B3.3 Gemini candidates or a post-freeze question overlay."""

from __future__ import annotations

import argparse
import json
from collections.abc import Sequence
from pathlib import Path

from chatagent_eval.question_set import (
    build_candidates,
    load_evidence_export,
    local_semantic_verifier,
    local_semantic_verifier_provenance,
    parse_gemini_output,
    parse_overlay,
    read_json,
    validate_overlay,
    write_json,
)

MANUAL_REPLACEMENT_KEYS = {
    "version", "evidenceId", "question", "referenceNeedle", "referenceAnswer",
    "generatorModel", "generatorTool", "authorizedBy", "authorizedAt", "reason",
}


def validate_manual_replacement_receipt(receipt: dict, expected_ids: set[str]) -> dict:
    if set(receipt) != MANUAL_REPLACEMENT_KEYS:
        raise ValueError(f"manual replacement receipt must contain exactly {sorted(MANUAL_REPLACEMENT_KEYS)}")
    if receipt.get("version") != "b3.3-codex-manual-replacement-v1":
        raise ValueError("manual replacement receipt has wrong version")
    evidence_id = str(receipt.get("evidenceId") or "")
    if evidence_id not in expected_ids:
        raise ValueError("manual replacement receipt references unknown evidenceId")
    for field in ("question", "referenceNeedle", "referenceAnswer", "generatorModel", "generatorTool",
                  "authorizedBy", "authorizedAt", "reason"):
        if not str(receipt.get(field) or "").strip():
            raise ValueError(f"manual replacement receipt {field} must be non-empty")
    replacement = {
        "evidenceId": evidence_id,
        "question": receipt["question"],
        "referenceNeedle": receipt["referenceNeedle"],
        "referenceAnswer": receipt["referenceAnswer"],
    }
    return replacement


def apply_manual_replacement(rows: list[dict], receipt: dict, expected_ids: set[str]) -> list[dict]:
    replacement = validate_manual_replacement_receipt(receipt, expected_ids)
    evidence_id = replacement["evidenceId"]
    by_id = {str(row["evidenceId"]): dict(row) for row in rows}
    by_id[evidence_id] = replacement
    return list(by_id.values())


def load_latest_generated_rows(
    responses_dir: Path | Sequence[Path],
    expected_evidence_ids: set[str] | None = None,
) -> list[dict]:
    response_dirs = [responses_dir] if isinstance(responses_dir, Path) else list(responses_dir)
    latest_by_evidence_id = {}
    for directory in response_dirs:
        for path in sorted(directory.glob("*.json")):
            seen_in_file = set()
            for item in parse_gemini_output(json.loads(path.read_text(encoding="utf-8-sig"))):
                evidence_id = str(item.get("evidenceId") or "")
                if not evidence_id or evidence_id in seen_in_file:
                    raise ValueError(f"response file has missing/duplicate evidenceId: {path.name}/{evidence_id}")
                if expected_evidence_ids is not None and evidence_id not in expected_evidence_ids:
                    raise ValueError(f"response file has unknown evidenceId: {path.name}/{evidence_id}")
                seen_in_file.add(evidence_id)
                latest_by_evidence_id[evidence_id] = item
    if expected_evidence_ids is not None and set(latest_by_evidence_id) != expected_evidence_ids:
        missing = sorted(expected_evidence_ids - set(latest_by_evidence_id))
        raise ValueError(f"responses do not cover every expected evidenceId: {missing[:5]}")
    return list(latest_by_evidence_id.values())


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="mode", required=True)

    candidates = subparsers.add_parser("candidates")
    candidates.add_argument("--evidence-index", type=Path, required=True)
    candidates.add_argument("--responses-dir", type=Path, action="append", required=True)
    candidates.add_argument("--output", type=Path, required=True)
    candidates.add_argument("--generator-model", required=True)
    candidates.add_argument("--manual-replacement", type=Path)

    overlay = subparsers.add_parser("overlay")
    overlay.add_argument("--base", type=Path, required=True)
    overlay.add_argument("--overlay", type=Path, required=True)
    overlay.add_argument("--manifest", type=Path, required=True)
    overlay.add_argument("--output", type=Path, required=True)

    args = parser.parse_args()
    if args.mode == "candidates":
        evidence_index = read_json(args.evidence_index)
        expected_ids = {str(item["evidenceId"]) for item in evidence_index.get("records") or []}
        generated_rows = load_latest_generated_rows(args.responses_dir, expected_ids)
        manual_replacement = read_json(args.manual_replacement) if args.manual_replacement else None
        if manual_replacement is not None:
            generated_rows = apply_manual_replacement(generated_rows, manual_replacement, expected_ids)
        result = build_candidates(
            generated_rows,
            evidence_index,
            generator_model=args.generator_model,
            manual_replacement=manual_replacement,
        )
        write_json(args.output, result)
        return

    base = read_json(args.base)
    manifest = read_json(args.manifest)
    evidence_root = Path(str(manifest["evidenceExportManifestPath"])).resolve().parent
    evidence_index = load_evidence_export(evidence_root)
    rows = parse_overlay(args.overlay, base)
    result = validate_overlay(
        base=base,
        overlay_rows=rows,
        evidence_index=evidence_index,
        verifier=local_semantic_verifier,
        verifier_provenance=local_semantic_verifier_provenance(),
    )
    if result["evidenceExportDatasetHash"] != manifest["evidenceExportDatasetHash"]:
        raise ValueError("overlay validation evidence hash does not match question-set manifest")
    write_json(args.output, result)


if __name__ == "__main__":
    main()
