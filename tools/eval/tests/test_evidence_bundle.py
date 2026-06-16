from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from chatagent_eval.evidence_bundle import write_10d_b2_evidence_bundle
from tests.promotion_fixtures import write_candidate_artifact


class TestEvidenceBundle(unittest.TestCase):
    def test_composes_selection_ready_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            answer, scored, retrieval_metrics = _write_linked_artifacts(
                root,
                splits=("calibration", "development"),
                split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
            )

            run_dir = _bundle(root, "evidence-bundle", answer, scored, retrieval_metrics)

            metrics = _read_json(run_dir / "metrics.json")
            bundle_manifest = _read_json(run_dir / "manifest.json")
            self.assertEqual(24.0, metrics["retrieval"]["rerankerMaxCandidates"])
            self.assertEqual(
                {"calibration": "sha256:cal", "development": "sha256:dev"},
                bundle_manifest["config"]["inputSplitHashes"],
            )
            self.assertTrue((run_dir / "samples.jsonl").exists())

    def test_candidate_fingerprint_is_stable_across_search_and_holdout_splits(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            search = _write_linked_artifacts(
                root / "search",
                splits=("calibration", "development"),
                split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
            )
            holdout = _write_linked_artifacts(
                root / "holdout",
                splits=("holdout",),
                split_hashes={"holdout": "sha256:holdout"},
            )
            holdout_retrieval = _read_json(holdout[2])
            holdout_retrieval["retrieval"]["contextRecallAtK"] = 0.81
            holdout_retrieval["retrieval"]["mrr"] = 0.73
            holdout[2].write_text(json.dumps(holdout_retrieval), encoding="utf-8")
            search_bundle = _bundle(root, "search-bundle", *search)
            holdout_bundle = _bundle(root, "holdout-bundle", *holdout)

            self.assertEqual(
                _read_json(search_bundle / "manifest.json")["configFingerprint"],
                _read_json(holdout_bundle / "manifest.json")["configFingerprint"],
            )

    def test_candidate_fingerprint_changes_when_retrieval_configuration_changes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            first = _write_linked_artifacts(
                root / "first",
                splits=("holdout",),
                split_hashes={"holdout": "sha256:holdout"},
            )
            second = _write_linked_artifacts(
                root / "second",
                splits=("holdout",),
                split_hashes={"holdout": "sha256:holdout"},
            )
            retrieval = _read_json(second[2])
            retrieval["retrievalConfig"]["rerankerModel"] = "different-reranker"
            second[2].write_text(json.dumps(retrieval), encoding="utf-8")
            rows = [
                json.loads(line)
                for line in (second[1] / "samples.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            for row in rows:
                row["metadata"]["retrievalProvenance"]["rerankerModel"] = "different-reranker"
            (second[1] / "samples.jsonl").write_text(
                "\n".join(json.dumps(row) for row in rows),
                encoding="utf-8",
            )

            first_bundle = _bundle(root, "first-bundle", *first)
            second_bundle = _bundle(root, "second-bundle", *second)

            self.assertNotEqual(
                _read_json(first_bundle / "manifest.json")["configFingerprint"],
                _read_json(second_bundle / "manifest.json")["configFingerprint"],
            )

    def test_rejects_retrieval_metrics_from_different_dataset(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            answer, scored, retrieval_metrics = _write_linked_artifacts(
                root,
                splits=("holdout",),
                split_hashes={"holdout": "sha256:holdout"},
            )
            retrieval = _read_json(retrieval_metrics)
            retrieval["datasetHash"] = "sha256:wrong"
            retrieval_metrics.write_text(json.dumps(retrieval), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "different dataset hashes"):
                _bundle(root, "evidence-bundle", answer, scored, retrieval_metrics)

    def test_rejects_cost_evidence_for_a_different_scored_run(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            answer, scored, retrieval_metrics = _write_linked_artifacts(
                root,
                splits=("holdout",),
                split_hashes={"holdout": "sha256:holdout"},
            )
            cost_evidence = _write_cost_evidence(root, scored)
            evidence = _read_json(cost_evidence)
            evidence["sourceRunId"] = "different-run"
            cost_evidence.write_text(json.dumps(evidence), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "does not identify"):
                _bundle(root, "evidence-bundle", answer, scored, retrieval_metrics, cost_evidence=cost_evidence)

    def test_rejects_partial_reranker_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            answer, scored, retrieval_metrics = _write_linked_artifacts(
                root,
                splits=("calibration", "development"),
                split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
            )
            retrieval = _read_json(retrieval_metrics)
            retrieval["retrieval"]["rerankerMaxCandidates"] = 8
            retrieval_metrics.write_text(json.dumps(retrieval), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "full candidate pool"):
                _bundle(root, "evidence-bundle", answer, scored, retrieval_metrics)

    def test_rejects_answers_generated_with_different_retrieval_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            answer, scored, retrieval_metrics = _write_linked_artifacts(
                root,
                splits=("calibration", "development"),
                split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
            )
            rows = [
                json.loads(line)
                for line in (scored / "samples.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            next(row for row in rows if row["metadata"]["controlRun"]["mode"] == "full-rag")[
                "metadata"
            ]["retrievalProvenance"]["finalTopK"] = 5
            (scored / "samples.jsonl").write_text(
                "\n".join(json.dumps(row) for row in rows),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "retrievalProvenance.finalTopK"):
                _bundle(root, "evidence-bundle", answer, scored, retrieval_metrics)


def _write_linked_artifacts(
    root: Path,
    *,
    splits: tuple[str, ...],
    split_hashes: dict[str, str],
) -> tuple[Path, Path, Path]:
    root.mkdir(parents=True, exist_ok=True)
    answer = write_candidate_artifact(root, "answer", split=splits, split_hashes=split_hashes)
    answer_manifest = _read_json(answer / "manifest.json")
    answer_manifest["runId"] = "answer-run"
    answer_manifest["configFingerprint"] = "sha256:answer-run"
    answer_manifest["config"] = {
        "controlModes": ["no-rag", "full-rag", "wrong-context", "oracle-context", "reranker-off", "reranker-on"],
        "contextTokenBudget": 6000,
        "finalTopK": 8,
        "llmModel": "deepseek-chat",
        "llmProvider": "deepseek",
        "sourceArtifact": "split-specific-input.jsonl",
        "splitHashes": split_hashes,
        "splits": list(splits),
    }
    (answer / "manifest.json").write_text(json.dumps(answer_manifest), encoding="utf-8")

    scored = write_candidate_artifact(root, "scored", split=splits, split_hashes=split_hashes)
    scored_manifest = _read_json(scored / "manifest.json")
    scored_manifest["configFingerprint"] = "sha256:scored-run"
    scored_manifest["config"] = {
        "embeddingModel": "ollama/bge-m3",
        "inputConfigFingerprint": "sha256:answer-run",
        "inputRunId": "answer-run",
        "inputSplitHashes": split_hashes,
        "inputSuite": "doc-ingestion-rag-effectiveness",
        "judgeModel": "deepseek-chat",
        "judgeProvider": "deepseek",
        "metricNames": ["faithfulness", "response_relevancy"],
    }
    (scored / "manifest.json").write_text(json.dumps(scored_manifest), encoding="utf-8")

    retrieval = _read_json(scored / "metrics.json")
    retrieval["datasetHash"] = "sha256:dataset"
    retrieval["splits"] = list(splits)
    retrieval["splitHashes"] = split_hashes
    retrieval["retrievalConfig"] = {
        "topK": 8,
        "candidateK": 24,
        "rrfK": 60,
        "embeddingProvider": "ollama",
        "embeddingModel": "bge-m3",
        "vectorProvider": "milvus",
        "rerankerProvider": "bge-http",
        "rerankerModel": "BAAI/bge-reranker-v2-m3",
        "rerankerMaxCandidates": 24,
        "rerankerMaxChunkChars": 900,
        "rerankerConfidenceFilterEnabled": True,
        "rerankerScoreThreshold": 0.08,
    }
    retrieval_metrics = root / "retrieval.json"
    retrieval_metrics.write_text(json.dumps(retrieval), encoding="utf-8")
    return answer, scored, retrieval_metrics


def _bundle(
    root: Path,
    run_id: str,
    answer: Path,
    scored: Path,
    retrieval_metrics: Path,
    *,
    cost_evidence: Path | None = None,
) -> Path:
    return write_10d_b2_evidence_bundle(
        retrieval_metrics_path=retrieval_metrics,
        answer_artifact=answer,
        scored_answer_artifact=scored,
        cost_evidence_path=cost_evidence or _write_cost_evidence(root, scored),
        output_root=root / "out",
        run_id=run_id,
        max_latency_p95_ms=1000.0,
        max_cost_usd=0.10,
    )


def _write_cost_evidence(root: Path, scored: Path) -> Path:
    manifest = _read_json(scored / "manifest.json")
    path = root / f"{scored.parent.name}-{scored.name}-cost.json"
    path.write_text(
        json.dumps(
            {
                "costUsd": 0.05,
                "method": "fixture-token-rate-calculation",
                "sourceRunId": manifest["runId"],
                "sourceConfigFingerprint": manifest["configFingerprint"],
            }
        ),
        encoding="utf-8",
    )
    return path


def _read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
