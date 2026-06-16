from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import run_eval
from chatagent_eval.memory_reference_runner import (
    MemoryReferenceConfig,
    run_memory_reference_clusters,
)
from chatagent_eval.schemas import load_json, validate

ROOT = Path(__file__).resolve().parents[3]
RESOURCE_ROOT = ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"


class MemoryReferenceRunnerTest(unittest.TestCase):
    def test_generates_independent_hash_bound_reference_clusters(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            _write_rows(input_path, [_row("sample-1", "calibration")])
            seen = []

            def extract(item, _config):
                seen.append(item)
                return _clusters()

            run_dir = run_memory_reference_clusters(
                input_path=input_path,
                output_root=Path(directory) / "out",
                config=MemoryReferenceConfig(run_id="memory-reference"),
                extractor=extract,
            )

            report = _read_json(run_dir / "report.json")
            sample = _read_jsonl(run_dir / "samples.jsonl")[0]
            validate(sample, load_json(RESOURCE_ROOT / "schemas" / "eval-sample.schema.json"))
            self.assertEqual("pass", report["status"])
            self.assertEqual(1.0, report["metrics"]["memory.referenceClusterCompletionRate"])
            self.assertEqual(1.0, report["metrics"]["memory.referenceClusterCount"])
            self.assertEqual({"sourceTurns"}, set(seen[0]))
            self.assertNotIn("moduleOutputs", json.dumps(seen[0]))
            cluster = sample["metadata"]["referenceClusters"][0]
            self.assertTrue(cluster["clusterId"].startswith("l3ref-"))
            self.assertEqual(["turn-1"], cluster["evidenceTurnIds"])
            self.assertTrue(sample["metadata"]["sourceInputHash"].startswith("sha256:"))
            self.assertTrue(
                _read_json(run_dir / "manifest.json")["config"]["sourceInputSetHash"].startswith("sha256:")
            )

    def test_default_split_filter_does_not_access_holdout(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            _write_rows(input_path, [_row("cal", "calibration"), _row("hold", "holdout")])
            seen = []
            run_dir = run_memory_reference_clusters(
                input_path=input_path,
                output_root=Path(directory) / "out",
                config=MemoryReferenceConfig(run_id="memory-reference-splits"),
                extractor=lambda item, _config: seen.append(item) or _clusters(),
            )

            samples = _read_jsonl(run_dir / "samples.jsonl")
            self.assertEqual(["cal"], [sample["sampleId"] for sample in samples])
            self.assertEqual(1, len(seen))

    def test_malformed_extraction_retries_and_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            _write_rows(input_path, [_row("sample-1", "calibration")])
            run_dir = run_memory_reference_clusters(
                input_path=input_path,
                output_root=Path(directory) / "out",
                config=MemoryReferenceConfig(run_id="memory-reference-bad", judge_max_attempts=2),
                extractor=lambda _item, _config: {"clusters": [{"canonicalFact": "missing fields"}]},
            )

            report = _read_json(run_dir / "report.json")
            sample = _read_jsonl(run_dir / "samples.jsonl")[0]
            failures = _read_jsonl(run_dir / "failures.jsonl")
            self.assertEqual("warn", report["status"])
            self.assertEqual(0.0, report["metrics"]["memory.referenceClusterCompletionRate"])
            self.assertEqual(2, len(sample["metadata"]["judgeAttemptFailures"]))
            self.assertEqual("reference_cluster_judge_error", failures[0]["errorCategory"])

    def test_transient_information_need_is_rejected(self) -> None:
        transient = {
            "clusters": [
                {
                    "canonicalFact": "The user wants to know which bank to choose.",
                    "evidenceTurnIds": ["turn-1"],
                    "memoryType": "decision",
                    "rationale": "The user asked a question.",
                }
            ]
        }
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            _write_rows(input_path, [_row("sample-1", "calibration")])
            run_dir = run_memory_reference_clusters(
                input_path=input_path,
                output_root=Path(directory) / "out",
                config=MemoryReferenceConfig(run_id="memory-reference-transient", judge_max_attempts=1),
                extractor=lambda _item, _config: transient,
            )

            report = _read_json(run_dir / "report.json")
            sample = _read_jsonl(run_dir / "samples.jsonl")[0]
            self.assertEqual("pass", report["status"])
            self.assertEqual(1.0, report["metrics"]["memory.referenceClusterCompletionRate"])
            self.assertEqual(1.0, report["metrics"]["memory.referenceClusterRejectedCount"])
            self.assertEqual([], sample["metadata"]["referenceClusters"])
            self.assertEqual(
                "transient_information_need",
                sample["metadata"]["rejectedReferenceClusters"][0]["reason"],
            )

    def test_question_conditional_and_other_person_references_are_rejected(self) -> None:
        value = {
            "clusters": [
                {
                    "canonicalFact": "User wants to pay debt quickly.",
                    "evidenceTurnIds": ["turn-1"],
                    "memoryType": "preference",
                    "rationale": "The user asks for help.",
                },
                {
                    "canonicalFact": "User has a lot of debt.",
                    "evidenceTurnIds": ["turn-2"],
                    "memoryType": "fact",
                    "rationale": "The user mentions debt conditionally.",
                },
                {
                    "canonicalFact": "User's son plays hockey.",
                    "evidenceTurnIds": ["turn-3"],
                    "memoryType": "fact",
                    "rationale": "The user mentions their son.",
                },
                {
                    "canonicalFact": "User likes hockey.",
                    "evidenceTurnIds": ["turn-3"],
                    "memoryType": "preference",
                    "rationale": "The user explicitly states a preference.",
                },
            ]
        }
        row = _row("sample-1", "calibration")
        row["turns"] = [
            {"speaker": "user", "text": "How can I pay debt quickly?"},
            {"speaker": "user", "text": "If I have a lot of debt, should I rent?"},
            {"speaker": "user", "text": "I like hockey because my son plays it."},
        ]
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            _write_rows(input_path, [row])
            run_dir = run_memory_reference_clusters(
                input_path=input_path,
                output_root=Path(directory) / "out",
                config=MemoryReferenceConfig(run_id="memory-reference-policy"),
                extractor=lambda _item, _config: value,
            )

            sample = _read_jsonl(run_dir / "samples.jsonl")[0]
            self.assertEqual(["User likes hockey."], [
                cluster["canonicalFact"] for cluster in sample["metadata"]["referenceClusters"]
            ])
            self.assertEqual(
                {
                    "evidence_only_information_need",
                    "conditional_or_hypothetical_evidence",
                    "about_other_person",
                },
                {row["reason"] for row in sample["metadata"]["rejectedReferenceClusters"]},
            )

    def test_resumes_hash_bound_checkpoint(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            _write_rows(input_path, [_row("sample-1", "calibration"), _row("sample-2", "development")])
            output_root = Path(directory) / "out"
            calls = 0

            def interrupt(_item, _config):
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise KeyboardInterrupt()
                return _clusters()

            config = MemoryReferenceConfig(run_id="memory-reference-resume")
            with self.assertRaises(KeyboardInterrupt):
                run_memory_reference_clusters(
                    input_path=input_path,
                    output_root=output_root,
                    config=config,
                    extractor=interrupt,
                )

            checkpoint = output_root / ".memory-reference-resume.checkpoint" / "rows.jsonl"
            self.assertEqual(1, len(_read_jsonl(checkpoint)))
            resumed_calls = 0

            def resume(_item, _config):
                nonlocal resumed_calls
                resumed_calls += 1
                return _clusters()

            run_dir = run_memory_reference_clusters(
                input_path=input_path,
                output_root=output_root,
                config=config,
                extractor=resume,
            )
            self.assertEqual(1, resumed_calls)
            self.assertFalse(checkpoint.parent.exists())
            self.assertEqual(2.0, _read_json(run_dir / "report.json")["metrics"]["memory.referenceClusterRowCount"])

    def test_cli_uses_explicit_reference_cluster_command(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            _write_rows(input_path, [_row("sample-1", "calibration")])
            with patch(
                "chatagent_eval.memory_reference_runner._call_extractor",
                return_value=_clusters(),
            ), contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_eval.main(
                    [
                        "memory-reference-clusters",
                        "--input-path",
                        str(input_path),
                        "--output-root",
                        str(Path(directory) / "out"),
                        "--run-id",
                        "memory-reference-cli",
                    ]
                )

            self.assertEqual(0, exit_code)
            self.assertEqual(
                "pass",
                _read_json(Path(directory) / "out" / "memory-reference-cli" / "report.json")["status"],
            )

    def test_config_requires_explicit_splits_and_positive_attempts(self) -> None:
        with self.assertRaisesRegex(ValueError, "splits"):
            MemoryReferenceConfig(run_id="bad", splits=())
        with self.assertRaisesRegex(ValueError, "judge_max_attempts"):
            MemoryReferenceConfig(run_id="bad", judge_max_attempts=0)

    def test_rejects_missing_or_mismatched_source_export_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            input_path.write_text(json.dumps(_row("sample-1", "calibration")) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "source export manifest"):
                run_memory_reference_clusters(
                    input_path=input_path,
                    output_root=Path(directory) / "out",
                    config=MemoryReferenceConfig(run_id="memory-reference-no-manifest"),
                    extractor=lambda _item, _config: _clusters(),
                )

            _write_manifest(input_path.parent, dataset_id="wrong")
            with self.assertRaisesRegex(ValueError, "datasetId mismatch"):
                run_memory_reference_clusters(
                    input_path=input_path,
                    output_root=Path(directory) / "out",
                    config=MemoryReferenceConfig(run_id="memory-reference-wrong-manifest"),
                    extractor=lambda _item, _config: _clusters(),
                )


def _row(sample_id: str, split: str) -> dict:
    return {
        "sampleId": sample_id,
        "datasetId": "memory-v2-dialogues",
        "sourceGroupId": "conversation-1",
        "split": split,
        "turns": [{"speaker": "user", "text": "I am a freelancer."}],
        "moduleOutputs": {
            "provider": {
                "summaryModel": "deepseek-chat",
                "extractorModel": "deepseek-chat",
                "embeddingModel": "bge-m3",
            }
        },
    }


def _clusters() -> dict:
    return {
        "clusters": [
            {
                "canonicalFact": "The user is a freelancer.",
                "evidenceTurnIds": ["turn-1"],
                "memoryType": "profile",
                "rationale": "The user states a durable occupation.",
            }
        ]
    }


def _write_rows(path: Path, rows: list[dict]) -> None:
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )
    _write_manifest(path.parent)


def _write_manifest(directory: Path, dataset_id: str = "memory-v2-dialogues") -> None:
    (directory / "manifest.json").write_text(
        json.dumps(
            {
                "runId": "source-memory-export",
                "datasetId": dataset_id,
                "datasetHash": "sha256:source-dataset",
            }
        )
        + "\n",
        encoding="utf-8",
    )


def _read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


if __name__ == "__main__":
    unittest.main()
