from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import run_eval
from chatagent_eval.agent_module_runner import AgentModuleConfig, run_agent_modules
from chatagent_eval.datasets import sha256_file
from chatagent_eval.parameters import validate_parameter_space
from chatagent_eval.schemas import load_json, validate

ROOT = Path(__file__).resolve().parents[3]
RESOURCE_ROOT = ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"


class AgentModuleRunnerTest(unittest.TestCase):
    def test_runs_agent_module_smoke_and_writes_v2_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            dataset_root = _write_dataset_root(Path(temp_dir) / "phase3")
            run_dir = run_agent_modules(
                dataset_root=dataset_root,
                output_root=Path(temp_dir) / "out",
                config=AgentModuleConfig(run_id="agent-modules-1"),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            manifest = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))
            metrics = json.loads((run_dir / "metrics.json").read_text(encoding="utf-8"))
            samples = _read_jsonl(run_dir / "samples.jsonl")
            failures = _read_jsonl(run_dir / "failures.jsonl")
            validate(report, load_json(RESOURCE_ROOT / "schemas" / "eval-report.schema.json"))
            validate(manifest, load_json(RESOURCE_ROOT / "schemas" / "eval-run-manifest.schema.json"))
            validate(samples[0], load_json(RESOURCE_ROOT / "schemas" / "eval-sample.schema.json"))
            self.assertEqual("pass", report["status"])
            self.assertEqual(2.0, report["metrics"]["agentModules.sampleCount"])
            self.assertEqual(1.0, report["metrics"]["agentModules.intentExactPathAccuracy"])
            self.assertEqual(1.0, report["metrics"]["agentModules.rewriteAnchorRecall"])
            self.assertEqual(1.0, report["metrics"]["agentModules.toolCallF1"])
            self.assertEqual(1.0, report["metrics"]["agentModules.coreferenceRecall"])
            self.assertIsNone(report["metrics"]["agentModules.ragasToolCallAccuracy"])
            self.assertFalse(metrics["ragasAgentMetrics"]["enabled"])
            self.assertEqual("agent-modules-v1", manifest["config"]["parameterSpaceId"])
            self.assertEqual([], failures)

    def test_disabled_rewrite_anchors_reports_parameter_sensitive_failures(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            dataset_root = _write_dataset_root(Path(temp_dir) / "phase3")
            run_dir = run_agent_modules(
                dataset_root=dataset_root,
                output_root=Path(temp_dir) / "out",
                config=AgentModuleConfig(run_id="agent-modules-low-rewrite", rewrite_max_anchors=0),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            failures = _read_jsonl(run_dir / "failures.jsonl")
            categories = {failure["errorCategory"] for failure in failures}
            self.assertEqual("warn", report["status"])
            self.assertLess(report["metrics"]["agentModules.rewriteAnchorRecall"], 1.0)
            self.assertIn("query_rewrite_missing_anchors", categories)
            self.assertIn("multiturn_coreference_missed", categories)

    def test_explicit_module_outputs_report_tool_and_topic_mismatches(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            dataset_root = _write_dataset_root(Path(temp_dir) / "phase3", include_bad_module_outputs=True)
            run_dir = run_agent_modules(
                dataset_root=dataset_root,
                output_root=Path(temp_dir) / "out",
                config=AgentModuleConfig(run_id="agent-modules-bad-outputs"),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            failures = _read_jsonl(run_dir / "failures.jsonl")
            categories = {failure["errorCategory"] for failure in failures}
            self.assertEqual("warn", report["status"])
            self.assertLess(report["metrics"]["agentModules.toolCallF1"], 1.0)
            self.assertLess(report["metrics"]["agentModules.topicSwitchAccuracy"], 1.0)
            self.assertIn("tool_call_mismatch", categories)
            self.assertIn("multiturn_topic_switch_mismatch", categories)

    def test_cli_agent_modules_smoke_runs_without_provider(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_dataset_root(temp_path / "phase3")
            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_eval.main(
                    [
                        "agent-modules-smoke",
                        "--dataset-root",
                        str(dataset_root),
                        "--output-root",
                        str(temp_path / "out"),
                        "--run-id",
                        "cli-agent-modules",
                        "--rewrite-max-anchors",
                        "3",
                    ]
                )

            report = json.loads((temp_path / "out" / "cli-agent-modules" / "report.json").read_text(encoding="utf-8"))
            self.assertEqual(0, exit_code)
            self.assertEqual("pass", report["status"])
            self.assertEqual(3.0, report["metrics"]["agentModules.rewriteMaxAnchors"])

    def test_config_and_parameter_space_validate_numeric_search_values(self) -> None:
        with self.assertRaisesRegex(ValueError, "rewrite_max_anchors"):
            AgentModuleConfig(run_id="bad", rewrite_max_anchors=-1)
        with self.assertRaisesRegex(ValueError, "tool_candidate_limit"):
            AgentModuleConfig(run_id="bad", tool_candidate_limit=-1)
        space = load_json(RESOURCE_ROOT / "parameter-spaces" / "agent-modules-v1.json")
        validate(space, load_json(RESOURCE_ROOT / "schemas" / "eval-parameter-space.schema.json"))
        validate_parameter_space(space)


def _write_dataset_root(path: Path, *, include_bad_module_outputs: bool = False) -> Path:
    rows = [
        {
            "sampleId": "agent-modules-1",
            "datasetId": "memory-v2-dialogues",
            "sourceGroupId": "conversation-1",
            "split": "development",
            "turns": [
                {"speaker": "user", "text": "Where do the Arizona Cardinals play this week?"},
                {"speaker": "agent", "text": "I do not have the answer yet."},
                {"speaker": "user", "text": "Do they play outside the US?"},
            ],
            "expectedResponse": "The Arizona Cardinals played in London and Mexico.",
            "referenceContextIds": ["doc-1"],
            "metadata": {
                "answerability": ["ANSWERABLE"],
                "domain": "mtrag-test",
                "multiTurn": ["Clarification"],
                "questionType": ["Explanation"],
                "sourceTaskId": "task-1",
            },
        },
        {
            "sampleId": "agent-modules-2",
            "datasetId": "memory-v2-dialogues",
            "sourceGroupId": "conversation-2",
            "split": "development",
            "turns": [{"speaker": "user", "text": "Tell me a joke."}],
            "expectedResponse": "I'm sorry, but I don't have the answer to your question.",
            "referenceContextIds": [],
            "metadata": {
                "answerability": ["UNANSWERABLE"],
                "domain": "mtrag-test",
                "multiTurn": ["N/A"],
                "questionType": ["Non-Question"],
                "sourceTaskId": "task-2",
            },
        },
    ]
    if include_bad_module_outputs:
        rows[0]["moduleOutputs"] = {
            "toolCalls": [],
            "multiTurn": {"topicSwitch": True},
        }
    dataset_path = path / "datasets" / "memory" / "memory-v2-dialogues.jsonl"
    dataset_path.parent.mkdir(parents=True, exist_ok=True)
    dataset_path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
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
        "recordCount": len(rows),
        "groupCount": len(rows),
        "splits": {"development": {"recordCount": len(rows), "groupCount": len(rows), "groupHash": "sha256:group"}},
    }
    manifest_path = path / "manifests" / "datasets" / "memory-v2-dialogues.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return path


def _read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


if __name__ == "__main__":
    unittest.main()
