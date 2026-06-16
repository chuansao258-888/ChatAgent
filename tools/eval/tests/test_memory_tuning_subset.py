from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from chatagent_eval.memory_tuning_subset import MemoryTuningSubsetConfig, run_memory_tuning_subset


class MemoryTuningSubsetTest(unittest.TestCase):
    def test_builds_deterministic_caldev_subset_with_all_supported_reference_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.jsonl"
            rows = [_source("positive", "calibration", "I am a student.")]
            for index in range(180):
                rows.append(_source(f"negative-{index:03}", "development", f"How do I solve task {index}?"))
            rows.append(_source("sealed", "holdout", "I am a manager."))
            _write_jsonl(source, rows)
            semantic = root / "semantic"
            semantic.mkdir()
            _write_json(semantic / "manifest.json", {"runId": "v3", "datasetId": "memory-v2-dialogues"})
            _write_jsonl(
                semantic / "samples.jsonl",
                [
                    _reference("positive", "calibration", "User is a student.", matched=False),
                    _reference("sealed", "holdout", "User is a manager.", matched=False),
                ],
            )

            run_dir = run_memory_tuning_subset(
                source_export_samples=source,
                semantic_run_dir=semantic,
                output_root=root / "out",
                config=MemoryTuningSubsetConfig(run_id="subset", hard_negative_target=120),
            )

            selection = _read_json(run_dir / "selection.json")
            analysis = _read_json(run_dir / "missed-reference-analysis.json")
            self.assertEqual(121, len(selection["sampleIds"]))
            self.assertEqual("positive", selection["sampleIds"][0])
            self.assertNotIn("sealed", selection["sampleIds"])
            self.assertEqual(1, selection["counts"]["supportedReferenceCount"])
            self.assertEqual(120, selection["counts"]["hardNegativeCount"])
            self.assertFalse(analysis["holdoutAccessed"])
            self.assertNotIn("sealed", (run_dir / "samples.jsonl").read_text(encoding="utf-8"))

            second = run_memory_tuning_subset(
                source_export_samples=source,
                semantic_run_dir=semantic,
                output_root=root / "out2",
                config=MemoryTuningSubsetConfig(run_id="subset", hard_negative_target=120),
            )
            self.assertEqual(selection["sampleIds"], _read_json(second / "selection.json")["sampleIds"])

    def test_rejects_hard_negative_target_outside_approved_budget(self) -> None:
        with self.assertRaisesRegex(ValueError, "between 100 and 150"):
            MemoryTuningSubsetConfig(run_id="bad", hard_negative_target=99)


def _source(sample_id: str, split: str, text: str) -> dict:
    return {
        "sampleId": sample_id,
        "datasetId": "memory-v2-dialogues",
        "sourceGroupId": sample_id,
        "split": split,
        "turns": [{"speaker": "user", "text": text}],
        "moduleOutputs": {"l3Extraction": {"memories": []}},
    }


def _reference(sample_id: str, split: str, fact: str, *, matched: bool) -> dict:
    return {
        "sampleId": f"{sample_id}:l3ref:1",
        "split": split,
        "metadata": {
            "sourceSampleId": sample_id,
            "role": "l3-reference",
            "judgeLabel": "supported",
            "claim": fact,
            "referenceClusterId": f"ref-{sample_id}",
            "memoryType": "fact",
            "l3ExtractedMatch": {"matched": matched},
        },
    }


def _write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value), encoding="utf-8")


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


def _read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))
