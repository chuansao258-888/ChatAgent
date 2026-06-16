import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from apply_question_review import apply_review_decisions
from collect_gemini_review import collect_review_rows
from prepare_gemini_review import write_review_batches
from prepare_question_review import build_review_rows


def candidate_root() -> dict:
    return {
        "evidenceExportDatasetHash": "sha256:dataset",
        "questions": [{
            "id": "q-1",
            "evidenceId": "e-1",
            "format": "SEC_HTML",
            "split": "holdout",
            "question": "What is approved?",
            "referenceNeedle": "The limit is 42.",
            "referenceAnswer": "42",
            "auditStatus": "pending",
            "generationMethod": "llm-generated-llm-reviewed-v1",
            "llmProvenance": {
                "generatorModel": "gemini-test",
                "generatorTool": "gemini-cli",
            },
        }],
    }


def evidence_index() -> dict:
    return {
        "evidenceExportDatasetHash": "sha256:dataset",
        "records": [{"evidenceId": "e-1", "referenceContent": "The limit is 42."}],
    }


class QuestionReviewTest(unittest.TestCase):
    def test_review_prompt_requires_character_exact_ids(self) -> None:
        with TemporaryDirectory() as directory:
            write_review_batches(candidate_root(), evidence_index(), Path(directory), 1)
            prompt = Path(directory, "review-batch-01.md").read_text(encoding="utf-8")

        self.assertIn("Copy every id exactly, character for character", prompt)

    def test_prepare_and_apply_require_explicit_gemini_decision(self) -> None:
        rows = build_review_rows(candidate_root(), evidence_index())
        self.assertEqual("pending", rows[0]["decision"])
        self.assertEqual("The limit is 42.", rows[0]["referenceContent"])
        rows[0].update({
            "decision": "llm-reviewed",
            "reviewerModel": "gemini-test",
            "reviewerTool": "gemini-cli",
            "reviewerPromptVersion": "review-v1",
            "questionAnswerableFromEvidence": True,
            "referenceAnswerSupported": True,
            "referenceAnswerAddressesQuestion": True,
            "standaloneAndUnambiguous": True,
            "noSourceOrReferenceLeak": True,
        })

        result = apply_review_decisions(candidate_root(), evidence_index(), rows)

        self.assertEqual("llm-reviewed", result["questions"][0]["auditStatus"])
        self.assertEqual("gemini-test", result["questions"][0]["llmProvenance"]["reviewerModel"])
        self.assertTrue(result["reviewReceiptHash"])

    def test_rejects_missing_decision_reviewer_and_content_changes(self) -> None:
        for mutate, message in (
            (lambda rows: rows[0].update(decision="pending"), "decision"),
            (lambda rows: rows[0].update(reviewerModel=""), "reviewerModel"),
            (lambda rows: rows[0].update(referenceAnswerSupported=False), "decision does not match"),
            (lambda rows: rows[0].update(question="changed"), "immutable"),
            (lambda rows: rows[0].update(referenceContent="changed"), "frozen evidence"),
            (lambda rows: rows[0].update(extra=True), "exactly"),
        ):
            rows = build_review_rows(candidate_root(), evidence_index())
            rows[0].update({
                "decision": "llm-reviewed",
                "reviewerModel": "gemini-test",
                "reviewerTool": "gemini-cli",
                "reviewerPromptVersion": "review-v1",
                "questionAnswerableFromEvidence": True,
                "referenceAnswerSupported": True,
                "referenceAnswerAddressesQuestion": True,
                "standaloneAndUnambiguous": True,
                "noSourceOrReferenceLeak": True,
            })
            mutate(rows)
            with self.assertRaisesRegex(ValueError, message):
                apply_review_decisions(candidate_root(), evidence_index(), rows)

    def test_rejects_missing_or_duplicate_candidate_decisions(self) -> None:
        rows = build_review_rows(candidate_root(), evidence_index())
        rows[0].update({
            "decision": "llm-reviewed",
            "reviewerModel": "gemini-test",
            "reviewerTool": "gemini-cli",
            "reviewerPromptVersion": "review-v1",
            "questionAnswerableFromEvidence": True,
            "referenceAnswerSupported": True,
            "referenceAnswerAddressesQuestion": True,
            "standaloneAndUnambiguous": True,
            "noSourceOrReferenceLeak": True,
        })
        with self.assertRaisesRegex(ValueError, "unknown/duplicate"):
            apply_review_decisions(candidate_root(), evidence_index(), rows + [json.loads(json.dumps(rows[0]))])
        with self.assertRaisesRegex(ValueError, "every candidate"):
            apply_review_decisions(candidate_root(), evidence_index(), [])

    def test_rejects_invalid_candidate_generation_method_with_current_id(self) -> None:
        candidates = candidate_root()
        candidates["questions"][0]["generationMethod"] = "invalid"
        rows = build_review_rows(candidates, evidence_index())
        rows[0].update({
            "decision": "llm-reviewed",
            "reviewerModel": "gemini-test",
            "reviewerTool": "gemini-cli",
            "reviewerPromptVersion": "review-v1",
            "questionAnswerableFromEvidence": True,
            "referenceAnswerSupported": True,
            "referenceAnswerAddressesQuestion": True,
            "standaloneAndUnambiguous": True,
            "noSourceOrReferenceLeak": True,
        })
        with self.assertRaisesRegex(ValueError, "q-1"):
            apply_review_decisions(candidates, evidence_index(), rows)

    def test_collector_rejects_non_string_review_notes(self) -> None:
        with TemporaryDirectory() as directory:
            response_dir = Path(directory)
            Path(response_dir, "review.json").write_text(json.dumps([{
                "id": "q-1",
                "decision": "llm-reviewed",
                "reviewerModel": "gemini-test",
                "reviewerTool": "gemini-cli",
                "reviewerPromptVersion": "review-v1",
                "questionAnswerableFromEvidence": True,
                "referenceAnswerSupported": True,
                "referenceAnswerAddressesQuestion": True,
                "standaloneAndUnambiguous": True,
                "noSourceOrReferenceLeak": True,
                "reviewNotes": None,
            }]), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "reviewNotes must be a string"):
                collect_review_rows(candidate_root(), evidence_index(), [response_dir])

    def test_collector_rejects_empty_reviewer_provenance(self) -> None:
        with TemporaryDirectory() as directory:
            response_dir = Path(directory)
            Path(response_dir, "review.json").write_text(json.dumps([{
                "id": "q-1",
                "decision": "llm-reviewed",
                "reviewerModel": " ",
                "reviewerTool": "gemini-cli",
                "reviewerPromptVersion": "review-v1",
                "questionAnswerableFromEvidence": True,
                "referenceAnswerSupported": True,
                "referenceAnswerAddressesQuestion": True,
                "standaloneAndUnambiguous": True,
                "noSourceOrReferenceLeak": True,
                "reviewNotes": "",
            }]), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "reviewerModel must be non-empty"):
                collect_review_rows(candidate_root(), evidence_index(), [response_dir])


if __name__ == "__main__":
    unittest.main()
