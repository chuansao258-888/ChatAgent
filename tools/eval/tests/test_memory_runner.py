from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import run_eval
from chatagent_eval.datasets import sha256_file
from chatagent_eval.memory_runner import MemoryConfig, _contradiction_rate, run_memory
from chatagent_eval.schemas import load_json, validate

ROOT = Path(__file__).resolve().parents[3]
RESOURCE_ROOT = ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"


class MemoryRunnerTest(unittest.TestCase):
    def test_runs_memory_smoke_and_writes_v2_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            dataset_root = _write_dataset_root(Path(temp_dir) / "phase3")
            run_dir = run_memory(
                dataset_root=dataset_root,
                output_root=Path(temp_dir) / "out",
                config=MemoryConfig(run_id="memory-1", l1_window_turns=2, l3_top_k=2),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            manifest = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))
            samples = _read_jsonl(run_dir / "samples.jsonl")
            failures = _read_jsonl(run_dir / "failures.jsonl")
            validate(report, load_json(RESOURCE_ROOT / "schemas" / "eval-report.schema.json"))
            validate(manifest, load_json(RESOURCE_ROOT / "schemas" / "eval-run-manifest.schema.json"))
            validate(samples[0], load_json(RESOURCE_ROOT / "schemas" / "eval-sample.schema.json"))
            self.assertEqual("pass", report["status"])
            self.assertEqual(1.0, report["metrics"]["memory.l1CompleteTurnRecall"])
            self.assertEqual(1.0, report["metrics"]["memory.l1ToolResponseRecall"])
            self.assertEqual(1.0, report["metrics"]["memory.l2FactRecall"])
            self.assertEqual(1.0, report["metrics"]["memory.l2SegmentRangeCoverage"])
            self.assertEqual(1.0, report["metrics"]["memory.l3ExtractionF1"])
            self.assertEqual(1.0, report["metrics"]["memory.l3RecallHitAtK"])
            self.assertEqual([], failures)
            self.assertEqual("0001-memory-compaction-v2-l2-schema-reset", manifest["config"]["adr"])

    def test_low_l1_budget_reports_missing_complete_turns_and_contexts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            dataset_root = _write_dataset_root(Path(temp_dir) / "phase3")
            run_dir = run_memory(
                dataset_root=dataset_root,
                output_root=Path(temp_dir) / "out",
                config=MemoryConfig(run_id="memory-low-budget", l1_window_turns=2, l1_budget_chars=10),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            failures = _read_jsonl(run_dir / "failures.jsonl")
            self.assertEqual("warn", report["status"])
            self.assertLess(report["metrics"]["memory.l1CompleteTurnRecall"], 1.0)
            self.assertEqual("l1_turn_or_tool_response_not_preserved", failures[0]["errorCategory"])
            self.assertIn("topContexts", failures[0])

    def test_cli_memory_smoke_runs_without_provider(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_dataset_root(temp_path / "phase3")
            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_eval.main(
                    [
                        "memory-smoke",
                        "--dataset-root",
                        str(dataset_root),
                        "--output-root",
                        str(temp_path / "out"),
                        "--run-id",
                        "cli-memory",
                        "--l1-window-turns",
                        "2",
                        "--l3-top-k",
                        "2",
                    ]
                )

            report = json.loads((temp_path / "out" / "cli-memory" / "report.json").read_text(encoding="utf-8"))
            self.assertEqual(0, exit_code)
            self.assertEqual("pass", report["status"])
            self.assertEqual(2.0, report["metrics"]["memory.l3TopK"])

    def test_memory_config_validates_positive_parameters(self) -> None:
        with self.assertRaisesRegex(ValueError, "l1_window_turns"):
            MemoryConfig(run_id="bad", l1_window_turns=0)
        with self.assertRaisesRegex(ValueError, "l3_top_k"):
            MemoryConfig(run_id="bad", l3_top_k=0)

    def test_contradiction_rate_ignores_plain_negation_without_positive_pair(self) -> None:
        self.assertEqual(0.0, _contradiction_rate("I do not have specific information about EV batteries."))
        self.assertEqual(0.0, _contradiction_rate("If you are a citizen, file form A. If you are not a citizen, file form B."))
        self.assertEqual(1.0, _contradiction_rate("The plan is approved. The plan is not approved."))


def _write_dataset_root(path: Path) -> Path:
    row = {
        "sampleId": "memory-1",
        "datasetId": "memory-v2-dialogues",
        "sourceGroupId": "conversation-1",
        "split": "development",
        "turns": [
            {"speaker": "user", "text": "I prefer concise answers about football teams."},
            {"speaker": "agent", "text": "The Arizona Cardinals played in London at Twickenham Stadium in 2017."},
            {"speaker": "tool", "text": "Tool result: Twickenham Stadium, London."},
            {"speaker": "user", "text": "Did the Cardinals play in London?"},
        ],
        "expectedResponse": "Yes, the Cardinals played in London at Twickenham Stadium.",
        "referenceContextIds": ["doc-1"],
        "metadata": {
            "answerability": ["ANSWERABLE"],
            "domain": "mtrag-test",
            "multiTurn": ["Clarification"],
            "questionType": ["Explanation"],
            "sourceTaskId": "task-1",
        },
    }
    dataset_path = path / "datasets" / "memory" / "memory-v2-dialogues.jsonl"
    dataset_path.parent.mkdir(parents=True, exist_ok=True)
    dataset_path.write_text(json.dumps(row) + "\n", encoding="utf-8")
    manifest = {
        "schemaVersion": 1,
        "datasetId": "memory-v2-dialogues",
        "version": 2,
        "sourceIds": ["mtrag-human"],
        "recordSchema": "eval-memory-dataset-record.schema.json",
        "localPath": "datasets/memory/memory-v2-dialogues.jsonl",
        "datasetHash": sha256_file(dataset_path),
        "splitManifestPath": "manifests/splits/memory-v2-dialogues.json",
        "splitManifestHash": "sha256:split",
        "recordCount": 1,
        "groupCount": 1,
        "splits": {"development": {"recordCount": 1, "groupCount": 1, "groupHash": "sha256:group"}},
    }
    manifest_path = path / "manifests" / "datasets" / "memory-v2-dialogues.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return path


def _read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


if __name__ == "__main__":
    unittest.main()
