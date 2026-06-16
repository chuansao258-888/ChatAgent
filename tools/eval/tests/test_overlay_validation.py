import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from chatagent_eval.question_set import (
    local_semantic_verifier_provenance,
    parse_overlay,
    validate_overlay,
    write_jsonl,
)


def base() -> dict:
    return {
        "version": "base",
        "questions": [
            {
                "id": "q-dev",
                "evidenceId": "e-dev",
                "filename": "dev.html",
                "format": "SEC_HTML",
                "split": "development",
                "chunkIndex": 1,
                "question": "What is the approved limit?",
                "referenceNeedle": "The approved limit is 42 units.",
                "referenceAnswer": "The approved limit is 42 units.",
            },
            {
                "id": "q-hold",
                "evidenceId": "e-hold",
                "filename": "hold.html",
                "format": "SEC_HTML",
                "split": "holdout",
                "chunkIndex": 2,
                "question": "When does release happen?",
                "referenceNeedle": "Release happens after verification.",
                "referenceAnswer": "Release happens after verification.",
            },
        ],
    }


def safe_overlay() -> dict:
    return {
        "id": "q-dev",
        "field": "question",
        "oldValue": "What is the approved limit?",
        "newValue": "How many units are approved?",
        "reason": "clarity",
        "reviewer": "Reviewer",
        "tuningAllowed": True,
    }


class OverlayValidationTest(unittest.TestCase):
    def test_local_verifier_provenance_uses_the_configured_model(self) -> None:
        with patch.dict(os.environ, {"CHATAGENT_EVAL_RAGAS_LLM_MODEL": "configured-local-model"}):
            self.assertEqual("configured-local-model", local_semantic_verifier_provenance()["model"])

    def test_envelope_rejects_extra_forbidden_duplicate_unknown_and_holdout(self) -> None:
        cases = [
            (safe_overlay() | {"extra": True}, "exactly"),
            (safe_overlay() | {"field": "filename"}, "forbidden"),
            (safe_overlay() | {"id": "unknown"}, "unknown"),
            (safe_overlay() | {"id": "q-hold", "oldValue": "When does release happen?"}, "holdout"),
            (safe_overlay() | {"reviewer": ""}, "reviewer"),
            (safe_overlay() | {"tuningAllowed": False}, "reviewer"),
            (safe_overlay() | {"oldValue": "wrong"}, "oldValue"),
        ]
        with TemporaryDirectory() as directory:
            path = Path(directory) / "overlay.jsonl"
            for row, message in cases:
                write_jsonl(path, [row])
                with self.assertRaisesRegex(ValueError, message):
                    parse_overlay(path, base())
            write_jsonl(path, [safe_overlay(), safe_overlay()])
            with self.assertRaisesRegex(ValueError, "duplicate"):
                parse_overlay(path, base())

    def test_safe_semantic_pass_writes_hash_bound_receipt(self) -> None:
        evidence_index = {
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [{
                "evidenceId": "e-dev",
                "filename": "dev.html",
                "sourceGroup": "group",
                "sourceUrl": "https://example.invalid/dev",
                "referenceContent": "The approved limit is 42 units.",
            }],
        }
        result = validate_overlay(
            base=base(),
            overlay_rows=[safe_overlay()],
            evidence_index=evidence_index,
            verifier=lambda question, answer, content: {
                "questionAnswerableFromEvidence": True,
                "referenceAnswerSupported": True,
                "referenceAnswerAddressesQuestion": True,
            },
            verifier_provenance={"provider": "test", "model": "test", "profile": "test"},
            enforce_quotas=False,
        )
        self.assertEqual("pass", result["rows"][0]["status"])
        self.assertEqual("sha256:dataset", result["evidenceExportDatasetHash"])
        self.assertNotIn("The approved limit is 42 units.", json.dumps(result))

    def test_semantic_false_incomplete_and_exception_fail_closed(self) -> None:
        evidence_index = {
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [{
                "evidenceId": "e-dev",
                "filename": "dev.html",
                "sourceGroup": "group",
                "sourceUrl": "https://example.invalid/dev",
                "referenceContent": "The approved limit is 42 units.",
            }],
        }
        for verifier in (
            lambda *_: {
                "questionAnswerableFromEvidence": False,
                "referenceAnswerSupported": True,
                "referenceAnswerAddressesQuestion": True,
            },
            lambda *_: {"questionAnswerableFromEvidence": True},
        ):
            with self.assertRaisesRegex(ValueError, "semantic verifier rejected"):
                validate_overlay(
                    base=base(), overlay_rows=[safe_overlay()], evidence_index=evidence_index,
                    verifier=verifier, verifier_provenance={},
                    enforce_quotas=False,
                )

    def test_overlay_rejects_new_collection_near_duplicate(self) -> None:
        evidence_index = {
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [{
                "evidenceId": "e-dev",
                "filename": "dev.html",
                "sourceGroup": "group",
                "sourceUrl": "https://example.invalid/dev",
                "referenceContent": "The approved limit is 42 units.",
            }],
        }
        overlay = safe_overlay() | {"newValue": "When does release happens?"}
        with self.assertRaisesRegex(ValueError, "near-duplicate"):
            validate_overlay(
                base=base(),
                overlay_rows=[overlay],
                evidence_index=evidence_index,
                verifier=lambda *_: {
                    "questionAnswerableFromEvidence": True,
                    "referenceAnswerSupported": True,
                    "referenceAnswerAddressesQuestion": True,
                },
                verifier_provenance={"provider": "test", "model": "test", "profile": "test"},
                enforce_quotas=False,
            )


if __name__ == "__main__":
    unittest.main()
