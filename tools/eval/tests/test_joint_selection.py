from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from chatagent_eval.joint_selection import write_10d_b2_joint_selection
from tests.promotion_fixtures import write_candidate_artifact


class TestJointSelection(unittest.TestCase):
    def test_selects_best_candidate_that_passes_all_joint_gates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            split_hashes = {"calibration": "sha256:cal", "development": "sha256:dev"}
            lower = write_candidate_artifact(
                root, "lower", split=("calibration", "development"), config_fingerprint="sha256:lower", split_hashes=split_hashes
            )
            higher = write_candidate_artifact(
                root,
                "higher",
                split=("calibration", "development"),
                config_fingerprint="sha256:higher",
                split_hashes=split_hashes,
                retrieval_overrides={"contextRecallAtK": 0.74, "mrr": 0.62},
            )

            run_dir = self._select(root, [lower, higher])
            selection = json.loads((run_dir / "selection.json").read_text(encoding="utf-8"))
            self.assertEqual("sha256:higher", selection["selectedConfigFingerprint"])
            self.assertFalse(selection["sealedHoldoutAccessed"])

    def test_rejects_candidate_with_holdout_rows(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            candidate = write_candidate_artifact(
                root,
                "holdout",
                split="holdout",
                split_hashes={"calibration": "sha256:cal", "development": "sha256:dev", "holdout": "sha256:holdout"},
            )
            with self.assertRaisesRegex(ValueError, "exactly the calibration/development search splits"):
                self._select(root, [candidate])

    def test_retrieval_winner_is_rejected_when_answer_quality_regresses(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            split_hashes = {"calibration": "sha256:cal", "development": "sha256:dev"}
            eligible = write_candidate_artifact(
                root, "eligible", split=("calibration", "development"), config_fingerprint="sha256:eligible", split_hashes=split_hashes
            )
            bad = write_candidate_artifact(
                root,
                "bad",
                split=("calibration", "development"),
                config_fingerprint="sha256:bad",
                split_hashes=split_hashes,
                retrieval_overrides={"contextRecallAtK": 0.90, "mrr": 0.90},
                score_overrides={"reranker-on": {"faithfulness": 0.40}},
            )

            run_dir = self._select(root, [eligible, bad])
            selection = json.loads((run_dir / "selection.json").read_text(encoding="utf-8"))
            self.assertEqual("sha256:eligible", selection["selectedConfigFingerprint"])
            bad_result = next(candidate for candidate in selection["candidates"] if candidate["configFingerprint"] == "sha256:bad")
            self.assertEqual("fail", bad_result["gateResults"]["overall"])

    @staticmethod
    def _select(root: Path, candidates: list[Path]) -> Path:
        return write_10d_b2_joint_selection(
            candidate_artifacts=candidates,
            output_root=root / "out",
            run_id="joint-selection-test",
            expected_dataset_hash="sha256:dataset",
            expected_search_split_hashes={"calibration": "sha256:cal", "development": "sha256:dev"},
        )


if __name__ == "__main__":
    unittest.main()
