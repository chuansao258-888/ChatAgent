from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest.mock import patch

import run_eval
from chatagent_eval.memory_reference_runner import MemoryReferenceConfig, run_memory_reference_clusters
from chatagent_eval.memory_semantic_runner import (
    JUDGE_GUIDANCE,
    LABELS,
    MemorySemanticConfig,
    run_memory_semantic,
    validate_calibration_fixture,
)
from chatagent_eval.schemas import load_json, validate

ROOT = Path(__file__).resolve().parents[3]
RESOURCE_ROOT = ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"
CALIBRATION = RESOURCE_ROOT / "datasets" / "memory" / "memory-semantic-calibration-v1.json"


class MemorySemanticRunnerTest(unittest.TestCase):
    def test_calibration_fixture_covers_every_label_and_memory_level(self) -> None:
        fixture = json.loads(CALIBRATION.read_text(encoding="utf-8"))

        validate_calibration_fixture(fixture)

        labels = Counter(item["expectedLabel"] for item in fixture["examples"])
        targets = Counter(item["target"] for item in fixture["examples"])
        self.assertEqual(20, len(fixture["examples"]))
        self.assertEqual({label: 5 for label in LABELS}, dict(labels))
        self.assertEqual({"l2": 10, "l3": 10}, dict(targets))
        self.assertFalse(fixture["review"]["humanReviewed"])
        self.assertIn("neither establish nor make the claim false", JUDGE_GUIDANCE)
        self.assertIn("omission changes a required scope", JUDGE_GUIDANCE)

    def test_calibration_run_writes_repeatable_explicit_semantic_metrics(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run_dir = run_memory_semantic(
                input_path=CALIBRATION,
                output_root=Path(directory),
                config=MemorySemanticConfig(run_id="memory-semantic-calibration"),
                judge=_expected_judge,
            )

            report = _read_json(run_dir / "report.json")
            metrics = _read_json(run_dir / "metrics.json")
            samples = _read_jsonl(run_dir / "samples.jsonl")
            failures = _read_jsonl(run_dir / "failures.jsonl")
            validate(report, load_json(RESOURCE_ROOT / "schemas" / "eval-report.schema.json"))
            validate(samples[0], load_json(RESOURCE_ROOT / "schemas" / "eval-sample.schema.json"))
            self.assertEqual("pass", report["status"])
            self.assertEqual(1.0, report["metrics"]["memory.semanticCalibrationAccuracy"])
            self.assertEqual(1.0, report["metrics"]["memory.semanticJudgeRepeatAgreement"])
            self.assertEqual(1.0, report["metrics"]["memory.semanticCompletionRate"])
            self.assertEqual(20.0, report["metrics"]["memory.semanticCompletedItemCount"])
            self.assertEqual(0.9, report["metrics"]["memory.semanticL3SubjectActionObjectCoverage"])
            self.assertEqual(1.0, report["metrics"]["memory.semanticL3UserSpecificityRate"])
            self.assertEqual(1.0, report["metrics"]["memory.semanticL3TemporalStabilityRate"])
            self.assertEqual({label: 5 for label in LABELS}, metrics["labelCounts"])
            self.assertEqual([], failures)
            self.assertEqual("deepseek", samples[0]["metadata"]["judgeProvenance"]["provider"])

    def test_repeat_disagreement_and_calibration_mismatch_warn(self) -> None:
        calls = Counter()

        def disagree(item, _config):
            calls[item["id"]] += 1
            label = item["expectedLabel"] if calls[item["id"]] == 1 else "unsupported"
            return _judgment(label)

        with tempfile.TemporaryDirectory() as directory:
            run_dir = run_memory_semantic(
                input_path=CALIBRATION,
                output_root=Path(directory),
                config=MemorySemanticConfig(run_id="memory-semantic-disagree", max_samples=1),
                judge=disagree,
            )

            report = _read_json(run_dir / "report.json")
            failures = _read_jsonl(run_dir / "failures.jsonl")
            self.assertEqual("warn", report["status"])
            self.assertEqual(0.0, report["metrics"]["memory.semanticJudgeRepeatAgreement"])
            self.assertTrue(any(row["errorCategory"] == "repeat_disagreement" for row in failures))

    def test_malformed_judge_fails_closed_without_exact_match_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run_dir = run_memory_semantic(
                input_path=CALIBRATION,
                output_root=Path(directory),
                config=MemorySemanticConfig(run_id="memory-semantic-malformed", max_samples=1),
                judge=lambda _item, _config: {"label": "supported"},
            )

            report = _read_json(run_dir / "report.json")
            failures = _read_jsonl(run_dir / "failures.jsonl")
            self.assertEqual("warn", report["status"])
            self.assertEqual(0.0, report["metrics"]["memory.semanticCompletedItemCount"])
            self.assertEqual(0.0, report["metrics"]["memory.semanticCompletionRate"])
            self.assertEqual(0.0, report["metrics"]["memory.semanticCalibrationAccuracy"])
            self.assertEqual(0.0, report["metrics"]["memory.semanticJudgeRepeatAgreement"])
            self.assertTrue(all(row["errorCategory"] == "judge_error" for row in failures))
            self.assertEqual("deepseek-chat", failures[0]["judgeProvenance"]["model"])

    def test_malformed_judge_retries_and_records_recovered_attempt(self) -> None:
        calls = Counter()

        def transient(item, _config):
            calls[item["id"]] += 1
            if calls[item["id"]] == 1:
                return {"label": "supported"}
            return _judgment(item["expectedLabel"])

        with tempfile.TemporaryDirectory() as directory:
            run_dir = run_memory_semantic(
                input_path=CALIBRATION,
                output_root=Path(directory),
                config=MemorySemanticConfig(run_id="memory-semantic-retry", max_samples=1),
                judge=transient,
            )

            report = _read_json(run_dir / "report.json")
            sample = _read_jsonl(run_dir / "samples.jsonl")[0]
            self.assertEqual("pass", report["status"])
            self.assertEqual(1.0, report["metrics"]["memory.semanticCompletionRate"])
            self.assertEqual(3, calls["supported-l2-01"])
            self.assertEqual(1, len(sample["metadata"]["judgeAttemptFailures"]))
            self.assertEqual(2, sample["metadata"]["judgments"][0]["attempt"])

    def test_full_export_extracts_l2_and_l3_claims_with_source_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            input_path.write_text(json.dumps(_full_export_row()) + "\n", encoding="utf-8")
            run_dir = run_memory_semantic(
                input_path=input_path,
                output_root=Path(directory) / "out",
                config=MemorySemanticConfig(
                    run_id="memory-semantic-full",
                    input_kind="full-export",
                    splits=("development",),
                ),
                judge=lambda _item, _config: _judgment("supported"),
            )

            report = _read_json(run_dir / "report.json")
            samples = _read_jsonl(run_dir / "samples.jsonl")
            self.assertEqual(3.0, report["metrics"]["memory.semanticItemCount"])
            self.assertEqual({"l2", "l3"}, {sample["metadata"]["target"] for sample in samples})
            self.assertEqual(2, sum(sample["metadata"]["target"] == "l2" for sample in samples))
            self.assertEqual("deepseek-chat", samples[0]["metadata"]["sourceModelProvenance"]["summaryModel"])
            self.assertEqual(["turn-1", "turn-2"], samples[0]["referenceContextIds"])
            self.assertIsNone(report["metrics"]["memory.semanticL3ExtractionF1"])

    def test_full_export_allows_one_judgment_without_claiming_repeat_agreement(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            input_path.write_text(json.dumps(_full_export_row()) + "\n", encoding="utf-8")
            run_dir = run_memory_semantic(
                input_path=input_path,
                output_root=Path(directory) / "out",
                config=MemorySemanticConfig(
                    run_id="memory-semantic-once",
                    input_kind="full-export",
                    repeats=1,
                    splits=("development",),
                ),
                judge=lambda _item, _config: _judgment("supported"),
            )

            report = _read_json(run_dir / "report.json")
            self.assertIsNone(report["metrics"]["memory.semanticJudgeRepeatAgreement"])
            self.assertEqual(1.0, report["metrics"]["memory.semanticCompletionRate"])

    def test_full_export_can_score_l3_only_for_extractor_tuning(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            input_path.write_text(json.dumps(_full_export_row()) + "\n", encoding="utf-8")
            run_dir = run_memory_semantic(
                input_path=input_path,
                output_root=Path(directory) / "out",
                config=MemorySemanticConfig(
                    run_id="memory-semantic-l3-only",
                    input_kind="full-export",
                    repeats=1,
                    splits=("development",),
                    targets=("l3",),
                ),
                judge=lambda _item, _config: _judgment("supported"),
            )

            report = _read_json(run_dir / "report.json")
            samples = _read_jsonl(run_dir / "samples.jsonl")
            self.assertEqual(1.0, report["metrics"]["memory.semanticItemCount"])
            self.assertIsNone(report["metrics"]["memory.semanticL2SupportRate"])
            self.assertEqual({"l3"}, {sample["metadata"]["target"] for sample in samples})

    def test_full_export_reuses_only_exact_frozen_reference_judgments(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "source" / "samples.jsonl"
            input_path.parent.mkdir()
            input_path.write_text(json.dumps(_full_export_row()) + "\n", encoding="utf-8")
            _write_source_manifest(input_path.parent)
            reference_run = run_memory_reference_clusters(
                input_path=input_path,
                output_root=root / "references",
                config=MemoryReferenceConfig(run_id="memory-reference", splits=("development",)),
                extractor=lambda _item, _config: _reference_clusters("The user prefers concise answers."),
            )
            prior_calls = 0

            def prior_judge(_item, _config):
                nonlocal prior_calls
                prior_calls += 1
                return _judgment("supported")

            prior = run_memory_semantic(
                input_path=input_path,
                output_root=root / "semantic-prior",
                config=MemorySemanticConfig(
                    run_id="memory-semantic-prior",
                    input_kind="full-export",
                    repeats=1,
                    splits=("development",),
                    targets=("l3",),
                ),
                judge=prior_judge,
                reference_cluster_run_dir=reference_run,
                embedder=lambda texts, _config: [[1.0, 0.0] for _text in texts],
                match_judge=lambda _actual, _reference, _config: {"equivalent": True, "rationale": "same"},
            )
            candidate_calls = 0

            def candidate_judge(_item, _config):
                nonlocal candidate_calls
                candidate_calls += 1
                return _judgment("supported")

            candidate = run_memory_semantic(
                input_path=input_path,
                output_root=root / "semantic-candidate",
                config=MemorySemanticConfig(
                    run_id="memory-semantic-candidate",
                    input_kind="full-export",
                    repeats=1,
                    splits=("development",),
                    targets=("l3",),
                ),
                judge=candidate_judge,
                reference_cluster_run_dir=reference_run,
                reuse_reference_semantic_run_dir=prior,
                embedder=lambda texts, _config: [[1.0, 0.0] for _text in texts],
                match_judge=lambda _actual, _reference, _config: {"equivalent": True, "rationale": "same"},
            )

            self.assertEqual(2, prior_calls)
            self.assertEqual(1, candidate_calls)
            manifest = _read_json(candidate / "manifest.json")
            self.assertEqual(1, manifest["config"]["reuseReferenceSemanticRun"]["reusedReferenceCount"])
            reference = next(
                row for row in _read_jsonl(candidate / "samples.jsonl")
                if row["metadata"]["role"] == "l3-reference"
            )
            self.assertEqual("memory-semantic-prior", reference["metadata"]["judgeReuse"]["runId"])

    def test_full_export_resumes_hash_bound_item_checkpoint(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            input_path.write_text(json.dumps(_full_export_row()) + "\n", encoding="utf-8")
            output_root = Path(directory) / "out"
            calls = 0

            def interrupt_after_first(_item, _config):
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise KeyboardInterrupt()
                return _judgment("supported")

            config = MemorySemanticConfig(
                run_id="memory-semantic-resume",
                input_kind="full-export",
                repeats=1,
                splits=("development",),
            )
            with self.assertRaises(KeyboardInterrupt):
                run_memory_semantic(
                    input_path=input_path,
                    output_root=output_root,
                    config=config,
                    judge=interrupt_after_first,
                )

            checkpoint = output_root / ".memory-semantic-resume.checkpoint" / "items.jsonl"
            self.assertEqual(1, len(_read_jsonl(checkpoint)))
            resumed_calls = 0

            def resume(_item, _config):
                nonlocal resumed_calls
                resumed_calls += 1
                return _judgment("supported")

            run_dir = run_memory_semantic(
                input_path=input_path,
                output_root=output_root,
                config=config,
                judge=resume,
            )
            self.assertEqual(2, resumed_calls)
            self.assertFalse(checkpoint.parent.exists())
            self.assertEqual(3.0, _read_json(run_dir / "report.json")["metrics"]["memory.semanticCompletedItemCount"])

    def test_full_export_rejects_checkpoint_from_different_config(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            input_path.write_text(json.dumps(_full_export_row()) + "\n", encoding="utf-8")
            output_root = Path(directory) / "out"
            with self.assertRaises(KeyboardInterrupt):
                run_memory_semantic(
                    input_path=input_path,
                    output_root=output_root,
                    config=MemorySemanticConfig(
                        run_id="memory-semantic-mismatch",
                        input_kind="full-export",
                        repeats=1,
                        splits=("development",),
                    ),
                    judge=lambda _item, _config: (_ for _ in ()).throw(KeyboardInterrupt()),
                )

            with self.assertRaisesRegex(ValueError, "checkpoint config mismatch"):
                run_memory_semantic(
                    input_path=input_path,
                    output_root=output_root,
                    config=MemorySemanticConfig(
                        run_id="memory-semantic-mismatch",
                        input_kind="full-export",
                        repeats=2,
                        splits=("development",),
                    ),
                    judge=lambda _item, _config: _judgment("supported"),
                )

    def test_full_export_rejects_missing_real_module_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "samples.jsonl"
            row = _full_export_row()
            row.pop("moduleOutputs")
            input_path.write_text(json.dumps(row) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "lacks moduleOutputs"):
                run_memory_semantic(
                    input_path=input_path,
                    output_root=Path(directory) / "out",
                    config=MemorySemanticConfig(
                        run_id="memory-semantic-bad",
                        input_kind="full-export",
                        splits=("development",),
                    ),
                    judge=_expected_judge,
                )

    def test_full_export_matches_judge_supported_l3_against_reference_clusters(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "source" / "samples.jsonl"
            input_path.parent.mkdir()
            input_path.write_text(json.dumps(_full_export_row()) + "\n", encoding="utf-8")
            _write_source_manifest(input_path.parent)
            reference_run = run_memory_reference_clusters(
                input_path=input_path,
                output_root=Path(directory) / "references",
                config=MemoryReferenceConfig(run_id="memory-reference", splits=("development",)),
                extractor=lambda _item, _config: _reference_clusters("The user prefers concise answers."),
            )
            run_dir = run_memory_semantic(
                input_path=input_path,
                output_root=Path(directory) / "semantic",
                config=MemorySemanticConfig(
                    run_id="memory-semantic-match",
                    input_kind="full-export",
                    repeats=1,
                    splits=("development",),
                ),
                judge=lambda _item, _config: _judgment("supported"),
                reference_cluster_run_dir=reference_run,
                embedder=lambda texts, _config: [
                    [1.0, 0.0] if "concise answers" in text else [0.0, 1.0]
                    for text in texts
                ],
                match_judge=lambda _actual, _reference, _config: {
                    "equivalent": True,
                    "rationale": "The claims express the same preference.",
                },
            )

            report = _read_json(run_dir / "report.json")
            samples = _read_jsonl(run_dir / "samples.jsonl")
            extracted = next(sample for sample in samples if sample["metadata"]["role"] == "l3-extracted")
            self.assertEqual(1.0, report["metrics"]["memory.semanticL3ExtractionPrecision"])
            self.assertEqual(1.0, report["metrics"]["memory.semanticL3ExtractionRecall"])
            self.assertEqual(1.0, report["metrics"]["memory.semanticL3ExtractionF1"])
            self.assertEqual(1.0, report["metrics"]["memory.semanticL3MatchedTypeAccuracy"])
            self.assertEqual(0.0, report["metrics"]["memory.semanticL3PolicyRejectedReferenceCount"])
            self.assertTrue(extracted["metadata"]["l3ReferenceMatch"]["matched"])

    def test_reference_clusters_rebind_to_same_source_inputs_with_changed_model_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "source" / "samples.jsonl"
            input_path.parent.mkdir()
            row = _full_export_row()
            input_path.write_text(json.dumps(row) + "\n", encoding="utf-8")
            _write_source_manifest(input_path.parent)
            reference_run = run_memory_reference_clusters(
                input_path=input_path,
                output_root=Path(directory) / "references",
                config=MemoryReferenceConfig(run_id="memory-reference", splits=("development",)),
                extractor=lambda _item, _config: _reference_clusters("The user prefers concise answers."),
            )
            row["moduleOutputs"]["l3Extraction"]["memories"][0]["content"] = "The user prefers brief answers."
            input_path.write_text(json.dumps(row) + "\n", encoding="utf-8")

            run_dir = run_memory_semantic(
                input_path=input_path,
                output_root=Path(directory) / "semantic",
                config=MemorySemanticConfig(
                    run_id="memory-semantic-rebind",
                    input_kind="full-export",
                    repeats=1,
                    splits=("development",),
                ),
                judge=lambda _item, _config: _judgment("supported"),
                reference_cluster_run_dir=reference_run,
                embedder=lambda texts, _config: [[1.0, 0.0] for _text in texts],
                match_judge=lambda _actual, _reference, _config: {
                    "equivalent": True,
                    "rationale": "The claims express the same preference.",
                },
            )

            manifest = _read_json(run_dir / "manifest.json")
            self.assertEqual(1.0, _read_json(run_dir / "report.json")["metrics"]["memory.semanticL3ExtractionF1"])
            self.assertTrue(manifest["config"]["referenceClusterRun"]["samplesHash"].startswith("sha256:"))

    def test_reference_policy_rejection_count_is_scoped_to_selected_splits(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "source" / "samples.jsonl"
            input_path.parent.mkdir()
            development = _full_export_row()
            holdout = _full_export_row()
            holdout["sampleId"] = "memory-row-2"
            holdout["sourceGroupId"] = "conversation-2"
            holdout["split"] = "holdout"
            input_path.write_text(
                "\n".join(json.dumps(row) for row in (development, holdout)) + "\n",
                encoding="utf-8",
            )
            _write_source_manifest(input_path.parent)
            reference_run = run_memory_reference_clusters(
                input_path=input_path,
                output_root=Path(directory) / "references",
                config=MemoryReferenceConfig(
                    run_id="memory-reference",
                    splits=("development", "holdout"),
                ),
                extractor=lambda _item, _config: _reference_clusters("The user prefers concise answers."),
            )
            reference_samples = _read_jsonl(reference_run / "samples.jsonl")
            for sample in reference_samples:
                sample["metadata"]["referenceClusters"][0]["canonicalFact"] = (
                    "The user's son prefers concise answers."
                )
            (reference_run / "samples.jsonl").write_text(
                "\n".join(json.dumps(sample) for sample in reference_samples) + "\n",
                encoding="utf-8",
            )

            run_dir = run_memory_semantic(
                input_path=input_path,
                output_root=Path(directory) / "semantic",
                config=MemorySemanticConfig(
                    run_id="memory-semantic-selected-policy-count",
                    input_kind="full-export",
                    repeats=1,
                    splits=("development",),
                ),
                judge=lambda _item, _config: _judgment("supported"),
                reference_cluster_run_dir=reference_run,
            )

            report = _read_json(run_dir / "report.json")
            manifest = _read_json(run_dir / "manifest.json")
            self.assertEqual(
                1.0,
                report["metrics"]["memory.semanticL3PolicyRejectedReferenceCount"],
            )
            self.assertEqual(
                1,
                manifest["config"]["referenceClusterRun"]["policyRejectedReferenceCount"],
            )

    def test_reference_clusters_reject_changed_source_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "source" / "samples.jsonl"
            input_path.parent.mkdir()
            row = _full_export_row()
            input_path.write_text(json.dumps(row) + "\n", encoding="utf-8")
            _write_source_manifest(input_path.parent)
            reference_run = run_memory_reference_clusters(
                input_path=input_path,
                output_root=Path(directory) / "references",
                config=MemoryReferenceConfig(run_id="memory-reference", splits=("development",)),
                extractor=lambda _item, _config: _reference_clusters("The user prefers concise answers."),
            )
            row["turns"][0]["text"] = "The user changed the source input."
            input_path.write_text(json.dumps(row) + "\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "source input mismatch"):
                run_memory_semantic(
                    input_path=input_path,
                    output_root=Path(directory) / "semantic",
                    config=MemorySemanticConfig(
                        run_id="memory-semantic-source-drift",
                        input_kind="full-export",
                        repeats=1,
                        splits=("development",),
                    ),
                    judge=lambda _item, _config: _judgment("supported"),
                    reference_cluster_run_dir=reference_run,
                )

    def test_full_export_unmatched_reference_clusters_emit_precision_and_recall_failures(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "source" / "samples.jsonl"
            input_path.parent.mkdir()
            input_path.write_text(json.dumps(_full_export_row()) + "\n", encoding="utf-8")
            _write_source_manifest(input_path.parent)
            reference_run = run_memory_reference_clusters(
                input_path=input_path,
                output_root=Path(directory) / "references",
                config=MemoryReferenceConfig(run_id="memory-reference", splits=("development",)),
                extractor=lambda _item, _config: _reference_clusters("The user is a freelancer."),
            )
            run_dir = run_memory_semantic(
                input_path=input_path,
                output_root=Path(directory) / "semantic",
                config=MemorySemanticConfig(
                    run_id="memory-semantic-unmatched",
                    input_kind="full-export",
                    repeats=1,
                    splits=("development",),
                ),
                judge=lambda _item, _config: _judgment("supported"),
                reference_cluster_run_dir=reference_run,
                embedder=lambda texts, _config: [
                    [1.0, 0.0] if "freelancer" in text else [0.0, 1.0]
                    for text in texts
                ],
            )

            report = _read_json(run_dir / "report.json")
            failures = _read_jsonl(run_dir / "failures.jsonl")
            self.assertEqual(0.0, report["metrics"]["memory.semanticL3ExtractionF1"])
            self.assertTrue(any(row["metric"] == "memory.semanticL3ExtractionPrecision" for row in failures))
            self.assertTrue(any(row["metric"] == "memory.semanticL3ExtractionRecall" for row in failures))

    def test_pairwise_judge_rejects_high_similarity_status_difference(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            row = _full_export_row()
            row["moduleOutputs"]["l3Extraction"]["memories"][0]["content"] = (
                "The user is considering opening a business account."
            )
            input_path = Path(directory) / "source" / "samples.jsonl"
            input_path.parent.mkdir()
            input_path.write_text(json.dumps(row) + "\n", encoding="utf-8")
            _write_source_manifest(input_path.parent)
            reference_run = run_memory_reference_clusters(
                input_path=input_path,
                output_root=Path(directory) / "references",
                config=MemoryReferenceConfig(run_id="memory-reference", splits=("development",)),
                extractor=lambda _item, _config: _reference_clusters(
                    "The user decided to open a business account."
                ),
            )
            run_dir = run_memory_semantic(
                input_path=input_path,
                output_root=Path(directory) / "semantic",
                config=MemorySemanticConfig(
                    run_id="memory-semantic-pairwise",
                    input_kind="full-export",
                    repeats=1,
                    splits=("development",),
                ),
                judge=lambda _item, _config: _judgment("supported"),
                reference_cluster_run_dir=reference_run,
                embedder=lambda texts, _config: [[1.0, 0.0] for _text in texts],
                match_judge=lambda _actual, _reference, _config: {
                    "equivalent": False,
                    "rationale": "Considering an action is not the same as deciding it.",
                },
            )

            report = _read_json(run_dir / "report.json")
            extracted = next(
                sample
                for sample in _read_jsonl(run_dir / "samples.jsonl")
                if sample["metadata"]["role"] == "l3-extracted"
            )
            self.assertEqual(0.0, report["metrics"]["memory.semanticL3ExtractionF1"])
            self.assertFalse(extracted["metadata"]["l3ReferenceMatch"]["matched"])
            self.assertFalse(extracted["metadata"]["l3MatchConfirmations"][0]["equivalent"])

    def test_cli_memory_semantic_uses_explicit_command(self) -> None:
        with tempfile.TemporaryDirectory() as directory, patch(
            "chatagent_eval.memory_semantic_runner._call_judge",
            side_effect=_expected_judge,
        ):
            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_eval.main(
                    [
                        "memory-semantic",
                        "--input-path",
                        str(CALIBRATION),
                        "--output-root",
                        directory,
                        "--run-id",
                        "memory-semantic-cli",
                        "--max-samples",
                        "1",
                        "--targets",
                        "l3",
                    ]
                )

            report = _read_json(Path(directory) / "memory-semantic-cli" / "report.json")
            self.assertEqual(0, exit_code)
            self.assertEqual(1.0, report["metrics"]["memory.semanticCalibrationAccuracy"])
            self.assertEqual(
                "l3",
                _read_jsonl(Path(directory) / "memory-semantic-cli" / "samples.jsonl")[0]["metadata"]["target"],
            )

    def test_config_requires_repeat_agreement_and_known_input_kind(self) -> None:
        self.assertEqual((), MemorySemanticConfig(run_id="safe-default").splits)
        with self.assertRaisesRegex(ValueError, "full-export splits"):
            MemorySemanticConfig(run_id="bad", input_kind="full-export")
        with self.assertRaisesRegex(ValueError, "repeats"):
            MemorySemanticConfig(run_id="bad", repeats=1)
        with self.assertRaisesRegex(ValueError, "input_kind"):
            MemorySemanticConfig(run_id="bad", input_kind="unknown")
        with self.assertRaisesRegex(ValueError, "judge_max_attempts_per_repeat"):
            MemorySemanticConfig(run_id="bad", judge_max_attempts_per_repeat=0)
        with self.assertRaisesRegex(ValueError, "embedding_match_threshold"):
            MemorySemanticConfig(run_id="bad", embedding_match_threshold=1.1)
        with self.assertRaisesRegex(ValueError, "targets"):
            MemorySemanticConfig(run_id="bad", targets=("unknown",))


def _expected_judge(item, _config):
    return _judgment(item["expectedLabel"])


def _judgment(label: str) -> dict:
    return {
        "label": label,
        "rationale": f"The claim is {label}.",
        "usefulnessScore": 0.8,
        "userSpecificity": True,
        "temporalStability": True,
        "subjectActionObjectCoverage": 0.9,
    }


def _full_export_row() -> dict:
    return {
        "sampleId": "memory-row-1",
        "datasetId": "memory-v2-dialogues",
        "sourceGroupId": "conversation-1",
        "split": "development",
        "turns": [
            {"speaker": "user", "text": "I prefer concise answers."},
            {"speaker": "agent", "text": "The deadline is June 30."},
        ],
        "expectedResponse": "The deadline is June 30.",
        "referenceContextIds": [],
        "metadata": {},
        "moduleOutputs": {
            "l1Summary": {"synopsis": "The user prefers concise answers. The deadline is June 30."},
            "l3Extraction": {
                "memories": [{"type": "preference", "content": "The user prefers concise answers.", "tags": ["preference"]}]
            },
            "provider": {
                "summaryModel": "deepseek-chat",
                "extractorModel": "deepseek-chat",
                "embeddingModel": "bge-m3",
            },
        },
    }


def _reference_clusters(fact: str) -> dict:
    return {
        "clusters": [
            {
                "canonicalFact": fact,
                "evidenceTurnIds": ["turn-1"],
                "memoryType": "preference",
                "rationale": "The source turn establishes the memory.",
            }
        ]
    }


def _write_source_manifest(directory: Path) -> None:
    (directory / "manifest.json").write_text(
        json.dumps(
            {
                "runId": "source-memory-export",
                "datasetId": "memory-v2-dialogues",
                "datasetHash": "sha256:source-memory-export",
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
