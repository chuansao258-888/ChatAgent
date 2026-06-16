from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import run_eval
from chatagent_eval.memory_semantic_analysis import (
    MemorySemanticAnalysisConfig,
    run_memory_semantic_analysis,
)


class MemorySemanticAnalysisTest(unittest.TestCase):
    def test_analysis_reports_split_and_source_group_variance(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = _write_source_run(root)
            run_dir = run_memory_semantic_analysis(
                input_run_dir=source,
                output_root=root / "out",
                config=MemorySemanticAnalysisConfig(run_id="analysis"),
            )

            analysis = _read_json(run_dir / "analysis.json")
            self.assertEqual({"calibration", "development"}, set(analysis["bySplit"]))
            self.assertEqual(2, len(analysis["bySourceGroup"]))
            self.assertTrue(analysis["variance"]["visibleBySplit"])
            self.assertTrue(analysis["variance"]["visibleBySourceGroup"])
            self.assertEqual(0.5, analysis["overall"]["l3"]["precision"])
            self.assertEqual(1.0, analysis["overall"]["l3"]["recall"])
            self.assertTrue((run_dir / "analysis.md").is_file())

    def test_analysis_rejects_manifest_report_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = _write_source_run(root)
            report = _read_json(source / "report.json")
            report["datasetHash"] = "sha256:different"
            (source / "report.json").write_text(json.dumps(report), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "mismatch"):
                run_memory_semantic_analysis(
                    input_run_dir=source,
                    output_root=root / "out",
                    config=MemorySemanticAnalysisConfig(run_id="analysis"),
                )

    def test_cli_memory_semantic_analysis(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = _write_source_run(root)
            with contextlib.redirect_stdout(io.StringIO()):
                code = run_eval.main(
                    [
                        "memory-semantic-analysis",
                        "--input-run-dir",
                        str(source),
                        "--output-root",
                        str(root / "out"),
                        "--run-id",
                        "analysis-cli",
                    ]
                )
            self.assertEqual(0, code)
            self.assertTrue((root / "out" / "analysis-cli" / "analysis.json").is_file())


def _write_source_run(root: Path) -> Path:
    run_dir = root / "source"
    run_dir.mkdir()
    common = {
        "runId": "semantic-source",
        "suite": "memory-semantic",
        "mode": "full-export",
        "datasetId": "memory-v2-dialogues",
        "datasetHash": "sha256:dataset",
        "configFingerprint": "fingerprint",
    }
    (run_dir / "manifest.json").write_text(json.dumps(common), encoding="utf-8")
    rows = [
        _sample("a:l2", "calibration", "group-a", "l2-summary", "supported"),
        _sample("a:l3:1", "calibration", "group-a", "l3-extracted", "supported", matched=True),
        _sample("a:ref:1", "calibration", "group-a", "l3-reference", "supported", matched=True),
        _sample("b:l2", "development", "group-b", "l2-summary", "unsupported"),
        _sample("b:l3:1", "development", "group-b", "l3-extracted", "unsupported", matched=False),
    ]
    (run_dir / "samples.jsonl").write_text(
        "".join(json.dumps(row) + "\n" for row in rows),
        encoding="utf-8",
    )
    report = common | {
        "metrics": {
            "memory.semanticCompletionRate": 1.0,
            "memory.semanticItemCount": 5.0,
            "memory.semanticL2SupportRate": 0.5,
            "memory.semanticL3ExtractionPrecision": 0.5,
            "memory.semanticL3ExtractionRecall": 1.0,
            "memory.semanticL3ExtractionF1": 2.0 / 3.0,
        }
    }
    (run_dir / "report.json").write_text(json.dumps(report), encoding="utf-8")
    return run_dir


def _sample(
    sample_id: str,
    split: str,
    group: str,
    role: str,
    label: str,
    *,
    matched: bool | None = None,
) -> dict:
    metadata = {
        "sourceSampleId": sample_id.split(":")[0],
        "sourceGroupId": group,
        "role": role,
        "judgments": [
            {
                "label": label,
                "usefulnessScore": 0.8 if label == "supported" else 0.2,
                "subjectActionObjectCoverage": 1.0,
                "userSpecificity": True,
                "temporalStability": label == "supported",
            }
        ],
    }
    if role == "l3-extracted" and matched is not None:
        metadata["l3ReferenceMatch"] = {"matched": matched}
    if role == "l3-reference" and matched is not None:
        metadata["l3ExtractedMatch"] = {"matched": matched}
    return {"sampleId": sample_id, "split": split, "metadata": metadata}


def _read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
