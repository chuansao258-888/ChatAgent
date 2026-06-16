import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from chatagent_eval.question_set import canonical_hash
from freeze_question_set import freeze_question_set
from prepare_question_review import build_review_rows


def candidate_questions(*, status: str = "llm-reviewed") -> list[dict]:
    rows = []
    formats = ("SEC_HTML", "PDF", "DOCX", "XLSX", "WEB_MD")
    splits = ["calibration"] * 60 + ["development"] * 20 + ["holdout"] * 20
    for fmt in formats:
        for index, split in enumerate(splits):
            token = hashlib.sha256(f"{fmt}-{index}".encode()).hexdigest()
            rows.append({
                "id": f"q-{fmt.lower()}-{index}",
                "evidenceId": f"e-{fmt.lower()}-{index}",
                "filename": f"{fmt.lower()}-{index}.dat",
                "format": fmt,
                "split": split,
                "chunkIndex": index,
                "referenceChunkId": f"chunk-{fmt.lower()}-{index}",
                "sourceSha256": f"source-{fmt.lower()}-{index}",
                "question": f"What fact is represented by token {token}?",
                "referenceNeedle": f"needle-{index}",
                "referenceAnswer": f"answer-{index}",
                "generationMethod": "llm-generated-llm-reviewed-v1",
                "auditStatus": status,
                "llmProvenance": {
                    "generatorModel": "gemini-test",
                    "generatorTool": "gemini-cli",
                    "reviewerModel": "gemini-test",
                    "reviewerTool": "gemini-cli",
                    "reviewerPromptVersion": "review-v1",
                },
            })
    return rows


def evidence_index_for(questions: list[dict]) -> dict:
    return {
        "evidenceExportDatasetHash": "sha256:dataset",
        "records": [
            {
                "evidenceId": item["evidenceId"],
                "filename": item["filename"],
                "sourceSha256": item["sourceSha256"],
                "format": item["format"],
                "split": item["split"],
                "chunkIndex": item["chunkIndex"],
                "referenceChunkId": item["referenceChunkId"],
                "sourceGroup": "group",
                "sourceUrl": "https://example.invalid/source",
                "referenceContent": f"{item['question']} {item['referenceNeedle']} {item['referenceAnswer']}",
            }
            for item in questions
        ],
    }


def attach_review_receipt(candidates: dict, evidence_index: dict) -> list[dict]:
    questions_by_id = {item["id"]: item for item in candidates["questions"]}
    rows = build_review_rows(candidates, evidence_index)
    for row in rows:
        question = questions_by_id[row["id"]]
        row["decision"] = question["auditStatus"]
        row.update({
            "reviewerModel": question["llmProvenance"]["reviewerModel"],
            "reviewerTool": question["llmProvenance"]["reviewerTool"],
            "reviewerPromptVersion": question["llmProvenance"]["reviewerPromptVersion"],
            "questionAnswerableFromEvidence": question["auditStatus"] == "llm-reviewed",
            "referenceAnswerSupported": question["auditStatus"] == "llm-reviewed",
            "referenceAnswerAddressesQuestion": question["auditStatus"] == "llm-reviewed",
            "standaloneAndUnambiguous": question["auditStatus"] == "llm-reviewed",
            "noSourceOrReferenceLeak": question["auditStatus"] == "llm-reviewed",
        })
    candidates["reviewReceiptHash"] = canonical_hash(rows)
    return rows


class FreezeQuestionSetTest(unittest.TestCase):
    def test_freeze_writes_only_reviewed_rows_and_manifest_hashes(self) -> None:
        with TemporaryDirectory() as directory:
            evidence_manifest = Path(directory) / "b3-export-manifest.json"
            evidence_manifest.write_text("{}", encoding="utf-8")
            candidates = {
                "evidenceExportManifestPath": str(evidence_manifest),
                "evidenceExportDatasetHash": "sha256:dataset",
                "questions": candidate_questions(),
            }
            evidence_index = evidence_index_for(candidates["questions"])
            review_rows = attach_review_receipt(candidates, evidence_index)
            base, drafts, manifest = freeze_question_set(
                candidates, generated_by="test", evidence_index=evidence_index, review_rows=review_rows
            )
            self.assertEqual(500, len(base["questions"]))
            self.assertEqual([], drafts["questions"])
            self.assertEqual(500, manifest["totalQuestions"])
            self.assertEqual(100, manifest["perFormatCounts"]["SEC_HTML"])
            self.assertTrue(manifest["baseHash"])
            self.assertTrue(manifest["calibrationHash"])
            self.assertEqual(candidates["reviewReceiptHash"], manifest["reviewReceiptHash"])
            self.assertTrue(all(item["auditStatus"] == "llm-reviewed" for item in base["questions"]))
            self.assertTrue(all(item["evidenceId"].startswith("e-") for item in base["questions"]))

    def test_freeze_allows_exactly_one_hash_bound_codex_manual_replacement(self) -> None:
        with TemporaryDirectory() as directory:
            evidence_manifest = Path(directory) / "b3-export-manifest.json"
            evidence_manifest.write_text("{}", encoding="utf-8")
            questions = candidate_questions()
            questions[0]["generationMethod"] = "codex-manual-assisted-llm-reviewed-v1"
            questions[0]["llmProvenance"]["generatorModel"] = "gpt-5-codex"
            questions[0]["llmProvenance"]["generatorTool"] = "codex-desktop"
            receipt = {
                "version": "b3.3-codex-manual-replacement-v1",
                "evidenceId": questions[0]["evidenceId"],
                "question": questions[0]["question"],
                "referenceNeedle": questions[0]["referenceNeedle"],
                "referenceAnswer": questions[0]["referenceAnswer"],
                "generatorModel": "gpt-5-codex",
                "generatorTool": "codex-desktop",
                "authorizedBy": "user",
                "authorizedAt": "2026-06-14",
                "reason": "Gemini Pro quota locked.",
            }
            candidates = {
                "evidenceExportManifestPath": str(evidence_manifest),
                "evidenceExportDatasetHash": "sha256:dataset",
                "manualReplacementCount": 1,
                "manualReplacementReceiptHash": canonical_hash(receipt),
                "questions": questions,
            }
            evidence_index = evidence_index_for(questions)
            review_rows = attach_review_receipt(candidates, evidence_index)
            base, _, manifest = freeze_question_set(
                candidates,
                generated_by="test",
                evidence_index=evidence_index,
                review_rows=review_rows,
                manual_replacement=receipt,
            )
            self.assertEqual("codex-manual-assisted-llm-reviewed-v1", base["questions"][0]["generationMethod"])
            self.assertEqual(1, manifest["manualReplacementCount"])
            tampered = dict(receipt, question="A different question")
            with self.assertRaisesRegex(ValueError, "does not match manualReplacementReceiptHash"):
                freeze_question_set(
                    candidates,
                    generated_by="test",
                    evidence_index=evidence_index,
                    review_rows=review_rows,
                    manual_replacement=tampered,
                )
            questions[1]["generationMethod"] = "codex-manual-assisted-llm-reviewed-v1"
            review_rows = attach_review_receipt(candidates, evidence_index)
            with self.assertRaisesRegex(ValueError, "manual replacement count"):
                freeze_question_set(
                    candidates,
                    generated_by="test",
                    evidence_index=evidence_index,
                    review_rows=review_rows,
                    manual_replacement=receipt,
                )

    def test_pending_holdout_blocks_freeze(self) -> None:
        with TemporaryDirectory() as directory:
            evidence_manifest = Path(directory) / "b3-export-manifest.json"
            evidence_manifest.write_text("{}", encoding="utf-8")
            questions = candidate_questions()
            questions[-1]["auditStatus"] = "pending"
            with self.assertRaisesRegex(ValueError, "pending holdout"):
                freeze_question_set({
                    "evidenceExportManifestPath": str(evidence_manifest),
                    "evidenceExportDatasetHash": "sha256:dataset",
                    "reviewReceiptHash": "a" * 64,
                    "questions": questions,
                }, generated_by="test", evidence_index=evidence_index_for(questions), review_rows=[])

    def test_missing_review_receipt_and_changed_binding_fail(self) -> None:
        with TemporaryDirectory() as directory:
            evidence_manifest = Path(directory) / "b3-export-manifest.json"
            evidence_manifest.write_text("{}", encoding="utf-8")
            questions = candidate_questions()
            candidates = {
                "evidenceExportManifestPath": str(evidence_manifest),
                "evidenceExportDatasetHash": "sha256:dataset",
                "questions": questions,
            }
            with self.assertRaisesRegex(ValueError, "reviewReceiptHash"):
                freeze_question_set(candidates, generated_by="test", evidence_index=evidence_index_for(questions))

            questions[0]["sourceSha256"] = "changed"
            review_rows = attach_review_receipt(candidates, evidence_index_for(candidate_questions()))
            with self.assertRaisesRegex(ValueError, "frozen evidence binding"):
                freeze_question_set(
                    candidates,
                    generated_by="test",
                    evidence_index=evidence_index_for(candidate_questions()),
                    review_rows=review_rows,
                )

    def test_insufficient_reviewed_rows_and_missing_reviewer_fail(self) -> None:
        with TemporaryDirectory() as directory:
            evidence_manifest = Path(directory) / "b3-export-manifest.json"
            evidence_manifest.write_text("{}", encoding="utf-8")
            questions = candidate_questions()
            questions[0]["llmProvenance"]["reviewerModel"] = ""
            candidates = {
                "evidenceExportManifestPath": str(evidence_manifest),
                "evidenceExportDatasetHash": "sha256:dataset",
                "questions": questions,
            }
            evidence_index = evidence_index_for(questions)
            review_rows = attach_review_receipt(candidates, evidence_index)
            with self.assertRaisesRegex(ValueError, "reviewerModel"):
                freeze_question_set(
                    candidates, generated_by="test", evidence_index=evidence_index, review_rows=review_rows
                )

    def test_human_review_content_edit_is_revalidated_against_frozen_evidence(self) -> None:
        with TemporaryDirectory() as directory:
            evidence_manifest = Path(directory) / "b3-export-manifest.json"
            evidence_manifest.write_text("{}", encoding="utf-8")
            questions = candidate_questions()
            evidence_index = evidence_index_for(questions)
            questions[0]["question"] = f"Why does {questions[0]['referenceAnswer']} matter?"
            candidates = {
                "evidenceExportManifestPath": str(evidence_manifest),
                "evidenceExportDatasetHash": "sha256:dataset",
                "questions": questions,
            }
            review_rows = attach_review_receipt(candidates, evidence_index)

            with self.assertRaisesRegex(ValueError, "leaks"):
                freeze_question_set(
                    candidates, generated_by="test", evidence_index=evidence_index, review_rows=review_rows
                )

    def test_human_review_edit_cannot_introduce_near_duplicate(self) -> None:
        with TemporaryDirectory() as directory:
            evidence_manifest = Path(directory) / "b3-export-manifest.json"
            evidence_manifest.write_text("{}", encoding="utf-8")
            questions = candidate_questions()
            evidence_index = evidence_index_for(questions)
            questions[1]["question"] = questions[0]["question"] + " "
            candidates = {
                "evidenceExportManifestPath": str(evidence_manifest),
                "evidenceExportDatasetHash": "sha256:dataset",
                "questions": questions,
            }
            review_rows = attach_review_receipt(candidates, evidence_index)

            with self.assertRaisesRegex(ValueError, "near-duplicate"):
                freeze_question_set(
                    candidates, generated_by="test", evidence_index=evidence_index, review_rows=review_rows
                )
            for item in questions[:100]:
                item["auditStatus"] = "failed-review"
            review_rows = attach_review_receipt(candidates, evidence_index_for(questions))
            with self.assertRaisesRegex(ValueError, "at least 500"):
                freeze_question_set(
                    candidates,
                    generated_by="test",
                    evidence_index=evidence_index_for(questions),
                    review_rows=review_rows,
                )

    def test_review_receipt_rows_must_match_hash_and_reviewed_candidates(self) -> None:
        with TemporaryDirectory() as directory:
            evidence_manifest = Path(directory) / "b3-export-manifest.json"
            evidence_manifest.write_text("{}", encoding="utf-8")
            questions = candidate_questions()
            candidates = {
                "evidenceExportManifestPath": str(evidence_manifest),
                "evidenceExportDatasetHash": "sha256:dataset",
                "questions": questions,
            }
            evidence_index = evidence_index_for(questions)
            review_rows = attach_review_receipt(candidates, evidence_index)

            with self.assertRaisesRegex(ValueError, "receipt rows are required"):
                freeze_question_set(candidates, generated_by="test", evidence_index=evidence_index)

            tampered_rows = [dict(row) for row in review_rows]
            tampered_rows[0]["reviewNotes"] = "tampered"
            with self.assertRaisesRegex(ValueError, "do not match reviewReceiptHash"):
                freeze_question_set(
                    candidates, generated_by="test", evidence_index=evidence_index, review_rows=tampered_rows
                )

            candidates["questions"][0]["question"] = "A post-review edit that was not reviewed"
            with self.assertRaisesRegex(ValueError, "immutable field"):
                freeze_question_set(
                    candidates, generated_by="test", evidence_index=evidence_index, review_rows=review_rows
                )


if __name__ == "__main__":
    unittest.main()
