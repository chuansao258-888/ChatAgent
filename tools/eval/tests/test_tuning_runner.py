from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import run_eval
from chatagent_eval.datasets import sha256_file
from chatagent_eval.parameters import validate_parameter_registry, validate_registry_coverage, validate_tuning_policy
from chatagent_eval.promotion import split_audit, validate_search_splits
from chatagent_eval.rag_retrieval_runner import RagRetrievalConfig, run_rag_retrieval
from chatagent_eval.schemas import load_json, validate
from chatagent_eval.tuning import bootstrap_confidence_interval, build_experiment_trials, pareto_frontier, select_champion
from chatagent_eval.tuning_runner import TuningConfig, run_tuning_experiment

ROOT = Path(__file__).resolve().parents[3]
RESOURCE_ROOT = ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"


class TuningRunnerTest(unittest.TestCase):
    def test_registry_and_all_committed_parameter_spaces_validate(self) -> None:
        registry = load_json(RESOURCE_ROOT / "parameter-registry-v1.json")
        spaces = [
            load_json(RESOURCE_ROOT / "parameter-spaces" / name)
            for name in (
                "rag-retrieval-v1.json",
                "text-recall-v1.json",
                "memory-v2-v1.json",
                "agent-modules-v1.json",
            )
        ]
        validate(registry, load_json(RESOURCE_ROOT / "schemas" / "eval-parameter-registry.schema.json"))
        validate_parameter_registry(registry)
        validate_registry_coverage(registry, spaces)
        for suite, resource_id in (
            ("agent-modules", "agent-modules-v1"),
            ("memory-v2", "memory-v2-v1"),
            ("rag-retrieval", "rag-retrieval-v1"),
            ("text-recall", "text-recall-v1"),
        ):
            space = load_json(RESOURCE_ROOT / "parameter-spaces" / f"{resource_id}.json")
            policy = load_json(RESOURCE_ROOT / "tuning-policies" / f"{resource_id}.json")
            validate_tuning_policy(policy, space, registry, suite)

    def test_search_split_policy_rejects_holdout_and_overlap(self) -> None:
        with self.assertRaisesRegex(ValueError, "cannot access"):
            validate_search_splits(("development", "holdout"))
        manifest = {
            "splits": {
                "development": {"groupHash": "sha256:dev", "groupIds": ["same"]},
                "holdout": {"groupHash": "sha256:holdout", "groupIds": ["same"]},
            }
        }
        with self.assertRaisesRegex(ValueError, "overlap"):
            split_audit(manifest, search_splits=("development",), holdout_split="holdout", challenge_split=None)

    def test_trials_confidence_and_pareto_are_reproducible(self) -> None:
        space = {
            "id": "space",
            "version": "1",
            "primaryMetric": "quality",
            "direction": "maximize",
            "parameters": [
                {"id": "topK", "classification": "quality-tunable", "type": "integer", "values": [1, 3]}
            ],
        }
        trials = build_experiment_trials(
            space,
            experiment_id="exp",
            baseline_parameters={"topK": 3},
            strategy="grid",
            combination_budget=2,
            random_seed=7,
        )
        self.assertEqual(["baseline", "sensitivity"], [trial["stage"] for trial in trials])
        first = bootstrap_confidence_interval([0.0, 1.0, 1.0], random_seed=7, resamples=50)
        second = bootstrap_confidence_interval([0.0, 1.0, 1.0], random_seed=7, resamples=50)
        self.assertEqual(first, second)
        completed = [
            {"status": "completed", "gateFailures": [], "configFingerprint": "a", "metrics": {"quality": 1.0}, "latencyP95Ms": 20.0, "costUsd": 0.0},
            {"status": "completed", "gateFailures": [], "configFingerprint": "b", "metrics": {"quality": 0.9}, "latencyP95Ms": 10.0, "costUsd": 0.0},
            {"status": "completed", "gateFailures": [], "configFingerprint": "c", "metrics": {"quality": 0.8}, "latencyP95Ms": 30.0, "costUsd": 0.0},
        ]
        frontier = pareto_frontier(
            completed,
            objectives=[("quality", "maximize"), ("latencyP95Ms", "minimize"), ("costUsd", "minimize")],
        )
        self.assertEqual(["a", "b"], [trial["configFingerprint"] for trial in frontier])

    def test_champion_prefers_baseline_when_quality_is_tied(self) -> None:
        trials = [
            {
                "status": "completed",
                "gateFailures": [],
                "configFingerprint": "candidate",
                "parameters": {"topK": 5},
                "metrics": {"quality": 1.0},
                "latencyP95Ms": None,
                "costUsd": 0.0,
            },
            {
                "status": "completed",
                "gateFailures": [],
                "configFingerprint": "baseline",
                "parameters": {"topK": 3},
                "metrics": {"quality": 1.0},
                "latencyP95Ms": None,
                "costUsd": 0.0,
            },
        ]

        champion = select_champion(
            trials,
            primary_metric="quality",
            direction="maximize",
            baseline_parameters={"topK": 3},
        )

        self.assertEqual("baseline", champion["configFingerprint"])

    def test_run_tuning_experiment_seals_holdout_and_writes_promotion_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_dataset_root(temp_path / "phase3")
            experiment_dir = run_tuning_experiment(
                dataset_root=dataset_root,
                output_root=temp_path / "out",
                parameter_space_path=RESOURCE_ROOT / "parameter-spaces" / "agent-modules-v1.json",
                policy_path=RESOURCE_ROOT / "tuning-policies" / "agent-modules-v1.json",
                registry_path=RESOURCE_ROOT / "parameter-registry-v1.json",
                config=TuningConfig(
                    experiment_id="agent-tuning-test",
                    suite="agent-modules",
                    combination_budget=2,
                    random_seed=9,
                    max_samples_per_trial=4,
                    holdout_max_samples=2,
                    confidence_resamples=30,
                    git_sha="test-sha",
                ),
            )

            expected_files = {
                "experiment-manifest.json",
                "parameter-space.yaml",
                "trials.jsonl",
                "leaderboard.csv",
                "pareto-frontier.json",
                "champion-candidate.yaml",
                "holdout-verification.json",
                "promotion-decision.md",
            }
            self.assertEqual(expected_files, {path.name for path in experiment_dir.iterdir() if path.is_file()})
            manifest = load_json(experiment_dir / "experiment-manifest.json")
            trials = _read_jsonl(experiment_dir / "trials.jsonl")
            holdout = load_json(experiment_dir / "holdout-verification.json")
            candidate = load_json(experiment_dir / "champion-candidate.yaml")
            self.assertFalse(manifest["sealedHoldoutHashIncludedInIterativeTrials"])
            self.assertTrue(manifest["championSelectedBeforeHoldoutAccess"])
            self.assertTrue(holdout["holdoutOpenedAfterChampionSelection"])
            self.assertEqual(0, holdout["overlapCount"])
            self.assertEqual([], holdout["verificationGateFailures"])
            self.assertFalse(candidate["productionDefaultsChanged"])
            self.assertTrue(candidate["requiresSeparateReviewedChange"])
            frontier = load_json(experiment_dir / "pareto-frontier.json")["trials"]
            self.assertLess(len(frontier), len(trials))
            for trial in trials:
                self.assertNotIn("holdout", trial.get("splitHashes", {}))
                self.assertIsNone(trial["latencyP95Ms"])
                self.assertGreater(trial["executionElapsedMs"], 0.0)
                validate(trial, load_json(RESOURCE_ROOT / "schemas" / "eval-trial.schema.json"))

    def test_cli_tune_suite_runs_without_provider(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_dataset_root(temp_path / "phase3")
            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_eval.main(
                    [
                        "tune-suite",
                        "--suite",
                        "agent-modules",
                        "--dataset-root",
                        str(dataset_root),
                        "--output-root",
                        str(temp_path / "out"),
                        "--experiment-id",
                        "cli-tuning",
                        "--combination-budget",
                        "1",
                        "--max-samples-per-trial",
                        "4",
                        "--holdout-max-samples",
                        "2",
                        "--confidence-resamples",
                        "20",
                    ]
                )

            self.assertEqual(0, exit_code)
            self.assertTrue((temp_path / "out" / "cli-tuning" / "promotion-decision.md").exists())

    def test_cli_tune_suite_runs_rag_retrieval_real_export_fixture(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_rag_retrieval_export_root(temp_path / "phase10")
            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_eval.main(
                    [
                        "tune-suite",
                        "--suite",
                        "rag-retrieval",
                        "--dataset-root",
                        str(dataset_root),
                        "--output-root",
                        str(temp_path / "out"),
                        "--experiment-id",
                        "rag-retrieval-tuning",
                        "--combination-budget",
                        "1",
                        "--max-samples-per-trial",
                        "4",
                        "--holdout-max-samples",
                        "1",
                        "--confidence-resamples",
                        "20",
                    ]
                )

            self.assertEqual(0, exit_code)
            experiment_dir = temp_path / "out" / "rag-retrieval-tuning"
            manifest = load_json(experiment_dir / "experiment-manifest.json")
            dataset_manifest = load_json(dataset_root / "manifests" / "datasets" / "beir-scifact-rag-v1.json")
            holdout = load_json(experiment_dir / "holdout-verification.json")
            trials = _read_jsonl(experiment_dir / "trials.jsonl")
            trial_report = load_json(experiment_dir / "search-runs" / "trial-0001" / "report.json")

            validate(dataset_manifest, load_json(RESOURCE_ROOT / "schemas" / "eval-dataset-manifest.schema.json"))
            self.assertEqual("beir-scifact-rag-v1", manifest["datasetId"])
            self.assertEqual(dataset_manifest["datasetHash"], manifest["datasetHash"])
            self.assertEqual(0, holdout["overlapCount"])
            self.assertTrue(all(trial["status"] == "completed" for trial in trials))
            self.assertIn("ragRetrieval.ndcgAtK", trial_report["metrics"])
            self.assertTrue((experiment_dir / "promotion-decision.md").exists())

    def test_rag_retrieval_replay_rejects_retrieved_only_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_rag_retrieval_export_root(
                temp_path / "phase10",
                candidate_mode="retrieved-only",
            )

            with self.assertRaisesRegex(ValueError, "candidate contexts"):
                run_rag_retrieval(
                    dataset_root=dataset_root,
                    output_root=temp_path / "out",
                    config=RagRetrievalConfig(
                        run_id="retrieved-only",
                        splits=("calibration",),
                        max_samples=1,
                    ),
                )

    def test_rag_retrieval_replay_rejects_candidates_without_score_or_rank_signal(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_rag_retrieval_export_root(
                temp_path / "phase10",
                candidate_mode="no-signals",
            )

            with self.assertRaisesRegex(ValueError, "denseRank/bm25Rank"):
                run_rag_retrieval(
                    dataset_root=dataset_root,
                    output_root=temp_path / "out",
                    config=RagRetrievalConfig(
                        run_id="no-signals",
                        splits=("calibration",),
                        max_samples=1,
                    ),
                )

    def test_rag_retrieval_replay_rejects_candidate_rank_only_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_rag_retrieval_export_root(
                temp_path / "phase10",
                candidate_mode="candidate-rank-only",
            )

            with self.assertRaisesRegex(ValueError, "denseRank/bm25Rank"):
                run_rag_retrieval(
                    dataset_root=dataset_root,
                    output_root=temp_path / "out",
                    config=RagRetrievalConfig(
                        run_id="candidate-rank-only",
                        splits=("calibration",),
                        max_samples=1,
                    ),
                )

    def test_rag_retrieval_replay_uses_raw_dense_bm25_ranks_for_rrf(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_rag_retrieval_export_root(
                temp_path / "phase10",
                candidate_mode="conflicting-ranks",
            )

            low_rrf_dir = run_rag_retrieval(
                dataset_root=dataset_root,
                output_root=temp_path / "out",
                config=RagRetrievalConfig(
                    run_id="rrf-low",
                    top_k=1,
                    candidate_k=2,
                    rrf_k=1,
                    splits=("calibration",),
                    max_samples=1,
                ),
            )
            high_rrf_dir = run_rag_retrieval(
                dataset_root=dataset_root,
                output_root=temp_path / "out",
                config=RagRetrievalConfig(
                    run_id="rrf-high",
                    top_k=1,
                    candidate_k=2,
                    rrf_k=100,
                    splits=("calibration",),
                    max_samples=1,
                ),
            )

            low_sample = _read_jsonl(low_rrf_dir / "samples.jsonl")[0]
            high_sample = _read_jsonl(high_rrf_dir / "samples.jsonl")[0]

            self.assertEqual("doc-calibration", low_sample["retrievedContexts"][0]["id"])
            self.assertEqual("doc-calibration-distractor-a", high_sample["retrievedContexts"][0]["id"])
            self.assertEqual(1.0, low_sample["metadata"]["hitAtK"])
            self.assertEqual(0.0, high_sample["metadata"]["hitAtK"])

    def test_memory_v2_policy_does_not_hard_gate_extraction_f1(self) -> None:
        """Regression: L3 extraction F1 must be a secondary metric, not a hard gate."""
        policy = load_json(RESOURCE_ROOT / "tuning-policies" / "memory-v2-v1.json")
        hard_gates = policy.get("hardGates", {})
        secondary_metrics = [item["metric"] for item in policy.get("secondaryMetrics", [])]
        self.assertNotIn("memory.l3ExtractionF1", hard_gates,
                         "L3 extraction F1 must not be a hard gate — real models are not perfect")
        self.assertIn("memory.l3ExtractionF1", secondary_metrics,
                      "L3 extraction F1 should be tracked as a secondary metric for ranking")
        self.assertNotIn("memory.l2ContradictionRate", hard_gates,
                         "L2 contradiction rate must not be a hard gate — real summaries may contain contradictions")
        self.assertIn("memory.l2ContradictionRate", secondary_metrics,
                      "L2 contradiction rate should be tracked as a secondary metric for ranking")

    def test_cli_tune_suite_runs_memory_v2(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_dataset_root(temp_path / "phase3")
            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_eval.main(
                    [
                        "tune-suite",
                        "--suite",
                        "memory-v2",
                        "--dataset-root",
                        str(dataset_root),
                        "--output-root",
                        str(temp_path / "out"),
                        "--experiment-id",
                        "memory-tuning-test",
                        "--combination-budget",
                        "1",
                        "--max-samples-per-trial",
                        "4",
                        "--holdout-max-samples",
                        "1",
                        "--confidence-resamples",
                        "20",
                    ]
                )

            self.assertEqual(0, exit_code)
            experiment_dir = temp_path / "out" / "memory-tuning-test"
            holdout = load_json(experiment_dir / "holdout-verification.json")
            candidate = load_json(experiment_dir / "champion-candidate.yaml")
            self.assertTrue((experiment_dir / "promotion-decision.md").exists())
            self.assertEqual(0, holdout["overlapCount"])
            self.assertIn(candidate["status"], ("proposed", "rejected"))
            trials = _read_jsonl(experiment_dir / "trials.jsonl")
            self.assertTrue(all(trial["status"] == "completed" for trial in trials))

    def test_agent_modules_policy_does_not_hard_gate_intent_accuracy(self) -> None:
        """Regression: intent accuracy and tool F1 must not be hard gates."""
        policy = load_json(RESOURCE_ROOT / "tuning-policies" / "agent-modules-v1.json")
        hard_gates = policy.get("hardGates", {})
        secondary_metrics = [item["metric"] for item in policy.get("secondaryMetrics", [])]
        self.assertNotIn("agentModules.intentExactPathAccuracy", hard_gates,
                         "Intent accuracy must not be a hard gate — real models are not perfect")
        self.assertNotIn("agentModules.toolCallF1", hard_gates,
                         "Tool call F1 must not be a hard gate — real models are not perfect")
        self.assertIn("agentModules.toolCallF1", secondary_metrics,
                      "Tool call F1 should be tracked as a secondary metric for ranking")

    def test_cli_tune_suite_runs_agent_modules(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_agent_modules_export_root(temp_path / "phase3")
            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_eval.main(
                    [
                        "tune-suite",
                        "--suite",
                        "agent-modules",
                        "--dataset-root",
                        str(dataset_root),
                        "--output-root",
                        str(temp_path / "out"),
                        "--experiment-id",
                        "agent-modules-tuning",
                        "--combination-budget",
                        "1",
                        "--max-samples-per-trial",
                        "2",
                        "--holdout-max-samples",
                        "1",
                        "--confidence-resamples",
                        "20",
                    ]
                )

            self.assertEqual(0, exit_code)
            experiment_dir = temp_path / "out" / "agent-modules-tuning"
            trials = _read_jsonl(experiment_dir / "trials.jsonl")
            candidate = load_json(experiment_dir / "champion-candidate.yaml")
            holdout = load_json(experiment_dir / "holdout-verification.json")
            self.assertTrue(all(trial["status"] == "completed" for trial in trials))
            self.assertEqual("agent-modules", candidate["suite"])
            self.assertEqual("pending", candidate["reviewStatus"])
            self.assertEqual(0, holdout["overlapCount"])
            # Non-perfect metrics should not block champion selection
            intent_accuracy = trials[0]["metrics"].get("agentModules.intentExactPathAccuracy", 1.0)
            tool_f1 = trials[0]["metrics"].get("agentModules.toolCallF1", 1.0)
            self.assertLess(intent_accuracy, 1.0)
            self.assertLess(tool_f1, 1.0)


def _write_dataset_root(path: Path) -> Path:
    rows = [
        _row("calibration-a", "group-calibration", "calibration", "Clarification", True),
        _row("development-a", "group-development", "development", "Follow-up", True),
        _row("holdout-a", "group-holdout", "holdout", "Clarification", True),
        _row("challenge-a", "group-challenge", "challenge", "N/A", False),
    ]
    dataset_path = path / "datasets" / "memory" / "memory-v2-dialogues.jsonl"
    dataset_path.parent.mkdir(parents=True, exist_ok=True)
    dataset_path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
    split_manifest = {
        "schemaVersion": 1,
        "datasetId": "memory-v2-dialogues",
        "splits": {
            "calibration": {"groupHash": "sha256:calibration", "groupIds": ["group-calibration"]},
            "development": {"groupHash": "sha256:development", "groupIds": ["group-development"]},
            "holdout": {"groupHash": "sha256:holdout", "groupIds": ["group-holdout"]},
            "challenge": {"groupHash": "sha256:challenge", "groupIds": ["group-challenge"]},
        },
    }
    split_path = path / "manifests" / "splits" / "memory-v2-dialogues.json"
    split_path.parent.mkdir(parents=True, exist_ok=True)
    split_path.write_text(json.dumps(split_manifest), encoding="utf-8")
    manifest = {
        "schemaVersion": 1,
        "datasetId": "memory-v2-dialogues",
        "version": 2,
        "sourceIds": ["mtrag-human"],
        "recordSchema": "eval-memory-dataset-record.schema.json",
        "localPath": "datasets/memory/memory-v2-dialogues.jsonl",
        "datasetHash": sha256_file(dataset_path),
        "splitManifestPath": "manifests/splits/memory-v2-dialogues.json",
        "splitManifestHash": sha256_file(split_path),
        "recordCount": len(rows),
        "groupCount": len(rows),
        "splits": {
            split: {"recordCount": 1, "groupCount": 1, "groupHash": details["groupHash"]}
            for split, details in split_manifest["splits"].items()
        },
    }
    manifest_path = path / "manifests" / "datasets" / "memory-v2-dialogues.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return path


def _write_rag_retrieval_export_root(path: Path, candidate_mode: str = "candidate-contexts") -> Path:
    rows = [
        _retrieval_row("calibration-a", "group-calibration", "calibration", "doc-calibration", "scientific-fact-checking", candidate_mode),
        _retrieval_row("development-a", "group-development", "development", "doc-development", "scientific-fact-checking", candidate_mode),
        _retrieval_row("holdout-a", "group-holdout", "holdout", "doc-holdout", "scientific-fact-checking", candidate_mode),
        _retrieval_row("challenge-a", "group-challenge", "challenge", "doc-challenge", "scientific-fact-checking", candidate_mode),
    ]
    dataset_path = path / "datasets" / "rag" / "beir-scifact-rag-v1.jsonl"
    dataset_path.parent.mkdir(parents=True, exist_ok=True)
    dataset_path.write_text("\n".join(json.dumps(row, sort_keys=True) for row in rows) + "\n", encoding="utf-8")
    split_manifest = {
        "schemaVersion": 1,
        "datasetId": "beir-scifact-rag-v1",
        "splits": {
            "calibration": {"groupHash": "sha256:rag-calibration", "groupIds": ["group-calibration"]},
            "development": {"groupHash": "sha256:rag-development", "groupIds": ["group-development"]},
            "holdout": {"groupHash": "sha256:rag-holdout", "groupIds": ["group-holdout"]},
            "challenge": {"groupHash": "sha256:rag-challenge", "groupIds": ["group-challenge"]},
        },
    }
    split_path = path / "manifests" / "splits" / "beir-scifact-rag-v1.json"
    split_path.parent.mkdir(parents=True, exist_ok=True)
    split_path.write_text(json.dumps(split_manifest), encoding="utf-8")
    manifest = {
        "schemaVersion": 1,
        "datasetId": "beir-scifact-rag-v1",
        "version": 1,
        "sourceIds": ["beir-scifact"],
        "recordSchema": "eval-retrieval-dataset-record.schema.json",
        "localPath": "datasets/rag/beir-scifact-rag-v1.jsonl",
        "datasetHash": sha256_file(dataset_path),
        "splitManifestPath": "manifests/splits/beir-scifact-rag-v1.json",
        "splitManifestHash": sha256_file(split_path),
        "recordCount": len(rows),
        "groupCount": len(rows),
        "splits": {
            split: {"recordCount": 1, "groupCount": 1, "groupHash": details["groupHash"]}
            for split, details in split_manifest["splits"].items()
        },
        "provenance": {
            "provider": "ollama",
            "modelName": "bge-m3",
            "embeddingModel": "bge-m3",
            "exportTimestamp": "2026-06-07T00:00:00Z",
        },
    }
    manifest_path = path / "manifests" / "datasets" / "beir-scifact-rag-v1.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return path


def _row(sample_id: str, group_id: str, split: str, multi_turn: str, answerable: bool) -> dict:
    turns = [{"speaker": "user", "text": "Where do the Arizona Cardinals play?"}]
    if multi_turn != "N/A":
        turns.extend(
            [
                {"speaker": "agent", "text": "The Cardinals are an NFL team."},
                {"speaker": "user", "text": "Do they play outside the US?"},
            ]
        )
    return {
        "sampleId": sample_id,
        "datasetId": "memory-v2-dialogues",
        "sourceGroupId": group_id,
        "split": split,
        "turns": turns,
        "expectedResponse": "The Arizona Cardinals played in London." if answerable else "I do not have the answer.",
        "referenceContextIds": ["doc-1"] if answerable else [],
        "metadata": {
            "answerability": ["ANSWERABLE" if answerable else "UNANSWERABLE"],
            "domain": f"domain-{split}",
            "multiTurn": [multi_turn],
            "questionType": ["Explanation"],
            "sourceTaskId": sample_id,
        },
    }


def _retrieval_row(sample_id: str, group_id: str, split: str, reference_id: str, domain: str, candidate_mode: str) -> dict:
    row = {
        "sampleId": sample_id,
        "datasetId": "beir-scifact-rag-v1",
        "sourceGroupId": group_id,
        "split": split,
        "userInput": f"Does {reference_id} support the scientific claim?",
        "referenceContextIds": [reference_id],
        "metadata": {
            "domain": domain,
            "sourceType": "phase10-real-export-fixture",
            "latencyMs": 12.0,
        },
    }
    candidates = _retrieval_candidates(reference_id, candidate_mode=candidate_mode)
    if candidate_mode == "retrieved-only":
        row["retrievedContexts"] = candidates[:1]
    else:
        row["metadata"]["candidateContexts"] = candidates
    return row


def _retrieval_candidates(reference_id: str, candidate_mode: str = "candidate-contexts") -> list[dict]:
    if candidate_mode == "conflicting-ranks":
        return [
            {
                "id": reference_id,
                "text": f"Evidence passage for {reference_id}.",
                "sourceId": "eval-v2-scifact",
                "score": 0.50,
                "rankSignals": {
                    "candidateRank": 1,
                    "denseRank": 1,
                    "bm25Rank": 100,
                    "denseScore": 0.99,
                    "bm25Score": 0.01,
                    "fusedScore": 0.50,
                },
            },
            {
                "id": f"{reference_id}-distractor-a",
                "text": "Nearby but irrelevant scientific passage.",
                "sourceId": "eval-v2-scifact",
                "score": 0.48,
                "rankSignals": {
                    "candidateRank": 2,
                    "denseRank": 5,
                    "bm25Rank": 5,
                    "denseScore": 0.60,
                    "bm25Score": 0.60,
                    "fusedScore": 0.48,
                },
            },
        ]
    candidates = [
        {
            "id": reference_id,
            "text": f"Evidence passage for {reference_id}.",
            "sourceId": "eval-v2-scifact",
            "score": 0.92,
            "rankSignals": {
                "candidateRank": 1,
                "denseRank": 1,
                "bm25Rank": 1,
                "denseScore": 0.92,
                "bm25Score": 0.90,
                "fusedScore": 0.92,
            },
        },
        {
            "id": f"{reference_id}-distractor-a",
            "text": "Nearby but irrelevant scientific passage.",
            "sourceId": "eval-v2-scifact",
            "score": 0.71,
            "rankSignals": {
                "candidateRank": 2,
                "denseRank": 2,
                "bm25Rank": 2,
                "denseScore": 0.71,
                "bm25Score": 0.69,
                "fusedScore": 0.71,
            },
        },
        {
            "id": f"{reference_id}-distractor-b",
            "text": "Another unrelated passage.",
            "sourceId": "eval-v2-scifact",
            "score": 0.35,
            "rankSignals": {
                "candidateRank": 3,
                "denseRank": 3,
                "bm25Rank": 3,
                "denseScore": 0.35,
                "bm25Score": 0.34,
                "fusedScore": 0.35,
            },
        },
        {
            "id": f"{reference_id}-distractor-c",
            "text": "A low scoring passage.",
            "sourceId": "eval-v2-scifact",
            "score": 0.10,
            "rankSignals": {
                "candidateRank": 4,
                "denseRank": 4,
                "bm25Rank": 4,
                "denseScore": 0.10,
                "bm25Score": 0.09,
                "fusedScore": 0.10,
            },
        },
    ]
    if candidate_mode == "candidate-rank-only":
        for candidate in candidates:
            candidate["rankSignals"] = {"candidateRank": candidate["rankSignals"]["candidateRank"]}
    if candidate_mode == "no-signals":
        for candidate in candidates:
            candidate.pop("score", None)
            candidate.pop("rankSignals", None)
    return candidates


def _write_agent_modules_export_root(path: Path) -> Path:
    """Write a Phase 10c real-export style dataset root for agent-modules tuning."""
    rows = [
        _agent_modules_row("am-calibration-1", "group-calibration", "calibration", True, "TOOL"),
        _agent_modules_row("am-development-1", "group-development", "development", True, "TOOL"),
        _agent_modules_row("am-holdout-1", "group-holdout", "holdout", True, "TOOL"),
        _agent_modules_row("am-challenge-1", "group-challenge", "challenge", False, "SYSTEM"),
    ]
    dataset_path = path / "datasets" / "agent-modules" / "memory-v2-dialogues.jsonl"
    dataset_path.parent.mkdir(parents=True, exist_ok=True)
    dataset_path.write_text("\n".join(json.dumps(row, sort_keys=True) for row in rows) + "\n", encoding="utf-8")
    split_manifest = {
        "schemaVersion": 1,
        "datasetId": "memory-v2-dialogues",
        "splits": {
            "calibration": {"groupHash": "sha256:am-calibration", "groupIds": ["group-calibration"]},
            "development": {"groupHash": "sha256:am-development", "groupIds": ["group-development"]},
            "holdout": {"groupHash": "sha256:am-holdout", "groupIds": ["group-holdout"]},
            "challenge": {"groupHash": "sha256:am-challenge", "groupIds": ["group-challenge"]},
        },
    }
    split_path = path / "manifests" / "splits" / "memory-v2-dialogues.json"
    split_path.parent.mkdir(parents=True, exist_ok=True)
    split_path.write_text(json.dumps(split_manifest), encoding="utf-8")
    manifest = {
        "schemaVersion": 1,
        "datasetId": "memory-v2-dialogues",
        "version": 2,
        "sourceIds": ["mtrag-human"],
        "recordSchema": "eval-agent-module-dataset-record.schema.json",
        "localPath": "datasets/agent-modules/memory-v2-dialogues.jsonl",
        "datasetHash": sha256_file(dataset_path),
        "splitManifestPath": "manifests/splits/memory-v2-dialogues.json",
        "splitManifestHash": sha256_file(split_path),
        "recordCount": len(rows),
        "groupCount": len(rows),
        "splits": {
            split: {"recordCount": 1, "groupCount": 1, "groupHash": details["groupHash"]}
            for split, details in split_manifest["splits"].items()
        },
    }
    manifest_path = path / "manifests" / "datasets" / "memory-v2-dialogues.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return path


def _agent_modules_row(sample_id: str, group_id: str, split: str, answerable: bool, real_kind: str) -> dict:
    """Build a real-export agent-modules row with intentional metric variation."""
    turns = [{"speaker": "user", "text": "Where do the Arizona Cardinals play?"}]
    return {
        "sampleId": sample_id,
        "datasetId": "memory-v2-dialogues",
        "sourceGroupId": group_id,
        "split": split,
        "turns": turns,
        "expectedResponse": "State Farm Stadium." if answerable else "I do not have the answer.",
        "referenceContextIds": ["doc-1"] if answerable else [],
        "metadata": {
            "answerability": ["ANSWERABLE" if answerable else "UNANSWERABLE"],
            "domain": f"domain-{split}",
            "multiTurn": ["N/A"],
            "questionType": ["Explanation"],
            "sourceTaskId": sample_id,
        },
        "moduleOutputs": {
            "intent": {
                "routed": True,
                "requiresClarification": False,
                "kind": real_kind,
                "pathLabel": f"General > {'Assistance' if real_kind == 'TOOL' else 'Other'} > {'Tool Use' if real_kind == 'TOOL' else 'Direct Response'}",
                "allowedTools": [],
            },
            "queryRewrite": "Find information about Arizona Cardinals stadium location",
            "toolList": [],
            "provider": {"classifierModel": "test-model", "rewriteModel": "test-model"},
        },
    }


def _read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


if __name__ == "__main__":
    unittest.main()
