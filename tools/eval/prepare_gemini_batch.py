"""Prepare deterministic evidence-only Gemini CLI prompt batches for B3.3."""

from __future__ import annotations

import argparse
from pathlib import Path

from chatagent_eval.question_set import build_prompt, load_evidence_export, select_generation_evidence, write_json


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("gemini-batch-inputs"))
    parser.add_argument("--target-total", type=int, default=500)
    parser.add_argument("--batch-size", type=int, default=5)
    args = parser.parse_args()
    if args.batch_size < 1:
        raise ValueError("batch-size must be positive")
    if args.batch_size > 5:
        raise ValueError("batch-size must not exceed the low-memory limit of 5")

    evidence_index = load_evidence_export(args.dataset_root)
    selected = select_generation_evidence(evidence_index, target_total=args.target_total)
    selected_index = dict(evidence_index)
    selected_index["records"] = selected
    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_json(args.output_dir / "evidence-index.json", selected_index)
    for index, start in enumerate(range(0, len(selected), args.batch_size), 1):
        prompt_path = args.output_dir / f"batch-{index:02d}.md"
        prompt_path.write_text(build_prompt(selected[start : start + args.batch_size]), encoding="utf-8", newline="\n")
    write_json(
        args.output_dir / "batch-manifest.json",
        {
            "batchCount": (len(selected) + args.batch_size - 1) // args.batch_size,
            "batchSize": args.batch_size,
            "evidenceCount": len(selected),
            "evidenceExportManifestPath": evidence_index["evidenceExportManifestPath"],
            "evidenceExportDatasetHash": evidence_index["evidenceExportDatasetHash"],
        },
    )


if __name__ == "__main__":
    main()
