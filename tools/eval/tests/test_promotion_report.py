from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from chatagent_eval.promotion_report import write_10d_b2_promotion_report
from tests.promotion_fixtures import write_candidate_artifact, write_preflight_artifact, write_selection_artifact


class TestPromotionReport(unittest.TestCase):
    def test_writes_fail_closed_promotion_report_and_markdown(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact = write_candidate_artifact(root, "candidate", split="holdout")
            selection = write_selection_artifact(root)
            run_dir = write_10d_b2_promotion_report(
                candidate_artifact=artifact,
                selection_artifact=selection,
                preflight_artifact=write_preflight_artifact(root),
                output_root=root / "out",
                run_id="phase10d-b2-promo-test",
                expected_dataset_hash="sha256:dataset",
                expected_search_split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
                expected_holdout_split_hash="sha256:holdout",
            )

            report = json.loads((run_dir / "promotion-report.json").read_text(encoding="utf-8"))
            markdown = (run_dir / "promotion-decision.md").read_text(encoding="utf-8")
            self.assertEqual("pass", report["gateResults"]["overall"])
            self.assertEqual(2, report["evidenceValidation"]["pairedSourceSamples"])
            self.assertIn("Reranker On vs Off", markdown)
            self.assertFalse(report["productionDefaultsChanged"])

    def test_rejects_calibration_only_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact = write_candidate_artifact(
                root,
                "candidate",
                split="calibration",
                split_hashes={"calibration": "sha256:calibration", "holdout": "sha256:holdout"},
            )
            with self.assertRaisesRegex(ValueError, "sealed holdout rows only"):
                self._promote(root, artifact)

    def test_rejects_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact = write_candidate_artifact(root, "candidate", split="holdout", dataset_hash="sha256:wrong")
            with self.assertRaisesRegex(ValueError, "dataset hash"):
                self._promote(root, artifact)

    def test_rejects_incomplete_paired_controls(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact = write_candidate_artifact(root, "candidate", split="holdout")
            rows = (artifact / "samples.jsonl").read_text(encoding="utf-8").splitlines()
            (artifact / "samples.jsonl").write_text("\n".join(rows[:-1]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "incomplete paired controls"):
                self._promote(root, artifact)

    def test_rejects_candidate_pool_larger_than_reranker_bound(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact = write_candidate_artifact(
                root,
                "candidate",
                split="holdout",
                retrieval_overrides={"rerankerMaxCandidates": 8},
            )
            with self.assertRaisesRegex(ValueError, "full candidateK pool"):
                self._promote(root, artifact)

    def test_rejects_wrong_context_that_overlaps_reference(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact = write_candidate_artifact(root, "candidate", split="holdout")
            rows = [
                json.loads(line)
                for line in (artifact / "samples.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            wrong = next(row for row in rows if row["metadata"]["controlRun"]["mode"] == "wrong-context")
            reference_id = wrong["referenceContextIds"][0]
            wrong["retrievedContexts"][0]["id"] = reference_id
            wrong["retrievedContexts"][0]["chunkId"] = reference_id
            (artifact / "samples.jsonl").write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "wrong-context control overlaps"):
                self._promote(root, artifact)

    def test_rejects_selection_that_did_not_seal_holdout(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact = write_candidate_artifact(root, "candidate", split="holdout")
            selection = write_selection_artifact(root, sealed_holdout_accessed=True)
            with self.assertRaisesRegex(ValueError, "sealed holdout"):
                write_10d_b2_promotion_report(
                    candidate_artifact=artifact,
                    selection_artifact=selection,
                    preflight_artifact=write_preflight_artifact(root),
                    output_root=root / "out",
                    run_id="promotion-test",
                    expected_dataset_hash="sha256:dataset",
                    expected_search_split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
                    expected_holdout_split_hash="sha256:holdout",
                )

    def test_rejects_selection_that_used_only_calibration(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact = write_candidate_artifact(root, "candidate", split="holdout")
            selection = write_selection_artifact(root)
            selection_json = json.loads((selection / "selection.json").read_text(encoding="utf-8"))
            selection_json["searchSplits"] = ["calibration"]
            selection_json["searchSplitHashes"] = {"calibration": "sha256:cal"}
            (selection / "selection.json").write_text(json.dumps(selection_json), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "exactly calibration and development"):
                write_10d_b2_promotion_report(
                    candidate_artifact=artifact,
                    selection_artifact=selection,
                    preflight_artifact=write_preflight_artifact(root),
                    output_root=root / "out",
                    run_id="promotion-test",
                    expected_dataset_hash="sha256:dataset",
                    expected_search_split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
                    expected_holdout_split_hash="sha256:holdout",
                )

    def test_rejects_source_group_overlap_with_selection(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact = write_candidate_artifact(root, "candidate", split="holdout")
            selection = write_selection_artifact(root)
            selection_json = json.loads((selection / "selection.json").read_text(encoding="utf-8"))
            selection_json["searchSourceGroupIds"].append("group-holdout-source-0")
            (selection / "selection.json").write_text(json.dumps(selection_json), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "source-group overlap"):
                write_10d_b2_promotion_report(
                    candidate_artifact=artifact,
                    selection_artifact=selection,
                    preflight_artifact=write_preflight_artifact(root),
                    output_root=root / "out",
                    run_id="promotion-test",
                    expected_dataset_hash="sha256:dataset",
                    expected_search_split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
                    expected_holdout_split_hash="sha256:holdout",
                )

    def test_rejects_baseline_override_that_differs_from_frozen_policy(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact = write_candidate_artifact(root, "candidate", split="holdout")
            with self.assertRaisesRegex(ValueError, "frozen gate policy"):
                write_10d_b2_promotion_report(
                    candidate_artifact=artifact,
                    selection_artifact=write_selection_artifact(root),
                    preflight_artifact=write_preflight_artifact(root),
                    output_root=root / "out",
                    run_id="promotion-test",
                    expected_dataset_hash="sha256:dataset",
                    expected_search_split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
                    expected_holdout_split_hash="sha256:holdout",
                    baseline_context_recall=0.50,
                )

    def test_rejects_unsafe_run_id_before_reading_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaisesRegex(ValueError, "unsafe run id"):
                write_10d_b2_promotion_report(
                    candidate_artifact=root / "missing",
                    selection_artifact=root / "missing-selection",
                    preflight_artifact=root / "missing-preflight",
                    output_root=root / "out",
                    run_id="../escape",
                    expected_dataset_hash="sha256:dataset",
                    expected_search_split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
                    expected_holdout_split_hash="sha256:holdout",
                )

    def test_rejects_missing_samples_jsonl(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "empty").mkdir()
            with self.assertRaises(FileNotFoundError):
                write_10d_b2_promotion_report(
                    candidate_artifact=root / "empty",
                    selection_artifact=root / "selection",
                    preflight_artifact=root / "preflight",
                    output_root=root / "out",
                    run_id="test",
                    expected_dataset_hash="sha256:dataset",
                    expected_search_split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
                    expected_holdout_split_hash="sha256:holdout",
                )

    def _promote(self, root: Path, artifact: Path) -> Path:
        return write_10d_b2_promotion_report(
            candidate_artifact=artifact,
            selection_artifact=write_selection_artifact(root),
            preflight_artifact=write_preflight_artifact(root),
            output_root=root / "out",
            run_id="promotion-test",
            expected_dataset_hash="sha256:dataset",
            expected_search_split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
            expected_holdout_split_hash="sha256:holdout",
        )


if __name__ == "__main__":
    unittest.main()
