import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from chatagent_eval.question_set import (
    build_candidates,
    build_prompt,
    corpus_anchor_plan,
    distinctive_corpus_anchor_terms,
    file_hash,
    load_evidence_export,
    resolve_reference_needle,
    select_generation_evidence,
    validate_question,
    validate_question_collection,
    validate_question_corpus_grounding,
)
from prepare_gemini_validation_retry import write_validation_retry_batches
from validate_gemini_questions import load_latest_generated_rows
from validate_gemini_questions import apply_manual_replacement


def evidence(evidence_id: str = "sec-1", *, fmt: str = "SEC_HTML", split: str = "calibration", index: int = 1) -> dict:
    unique_marker = "".join(chr(ord("g") + int(char, 16)) for char in hashlib.sha256(evidence_id.encode()).hexdigest()[:12])
    return {
        "evidenceId": evidence_id,
        "filename": f"{evidence_id}.html",
        "format": fmt,
        "split": split,
        "chunkIndex": index,
        "referenceChunkId": f"chunk-{evidence_id}",
        "sourceSha256": hashlib.sha256(evidence_id.encode()).hexdigest(),
        "referenceContent": (
            f"The approved limit is 42 units. Verification must finish before release. "
            f"The unique marker is {unique_marker}."
        ),
        "sourceGroup": "source-group",
        "sourceUrl": "https://example.invalid/source",
    }


def generated(evidence_id: str = "sec-1") -> dict:
    return {
        "evidenceId": evidence_id,
        "question": "What is the approved limit?",
        "referenceNeedle": "The approved limit is 42 units.",
        "referenceAnswer": "42 units",
    }


class QuestionValidationTest(unittest.TestCase):
    def test_single_manual_replacement_is_separate_and_honestly_provenanced(self) -> None:
        index = {
            "evidenceExportManifestPath": "manifest.json",
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [evidence()],
        }
        receipt = {
            "version": "b3.3-codex-manual-replacement-v1",
            "evidenceId": "sec-1",
            "question": "What approved quantity is associated with verification before release?",
            "referenceNeedle": "The approved limit is 42 units.",
            "referenceAnswer": "42 units",
            "generatorModel": "gpt-5-codex",
            "generatorTool": "codex-desktop",
            "authorizedBy": "user",
            "authorizedAt": "2026-06-14",
            "reason": "Gemini Pro quota locked.",
        }
        rows = apply_manual_replacement([generated()], receipt, {"sec-1"})
        result = build_candidates(
            rows, index, generator_model="gemini-test", manual_replacement=receipt, enforce_quotas=False
        )
        item = result["questions"][0]
        self.assertEqual("codex-manual-assisted-llm-reviewed-v1", item["generationMethod"])
        self.assertEqual("gpt-5-codex", item["llmProvenance"]["generatorModel"])
        self.assertEqual(1, result["manualReplacementCount"])
        with self.assertRaisesRegex(ValueError, "exactly"):
            apply_manual_replacement(rows, receipt | {"extra": True}, {"sec-1"})

    def test_build_candidates_enriches_pending_provenance(self) -> None:
        index = {
            "evidenceExportManifestPath": "manifest.json",
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [evidence()],
        }
        result = build_candidates([generated()], index, generator_model="gemini-test", enforce_quotas=False)
        item = result["questions"][0]
        self.assertEqual("pending", item["auditStatus"])
        self.assertEqual("llm-generated-llm-reviewed-v1", item["generationMethod"])
        self.assertEqual("gemini-test", item["llmProvenance"]["generatorModel"])
        self.assertEqual("chunk-sec-1", item["referenceChunkId"])
        self.assertEqual(evidence()["sourceSha256"], item["sourceSha256"])

    def test_rejects_corpus_ambiguous_question_and_accepts_distinctive_anchors(self) -> None:
        target = evidence("target")
        target["referenceContent"] = (
            "The Falcon migration uses a blue release channel. The approved limit is 42 units. "
            "The linked path is d294699dex101."
        )
        competitor = evidence("competitor", index=2)
        competitor["referenceContent"] = (
            "The Heron migration uses a green release channel. The approved limit is 84 units."
        )
        index = {
            "evidenceExportManifestPath": "manifest.json",
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [target, competitor],
        }
        ambiguous = generated("target") | {
            "question": "What is the approved migration limit?",
            "referenceNeedle": "The approved limit is 42 units.",
            "referenceAnswer": "42 units",
        }
        with self.assertRaisesRegex(ValueError, "uniquely grounded"):
            build_candidates([ambiguous], index, generator_model="gemini-test", enforce_quotas=False)

        distinctive = ambiguous | {"question": "Which limit applies to the Falcon blue release channel?"}
        result = build_candidates([distinctive], index, generator_model="gemini-test", enforce_quotas=False)
        self.assertEqual("q-target", result["questions"][0]["id"])

    def test_corpus_grounding_requires_two_non_answer_anchors(self) -> None:
        item = {
            **evidence(),
            "id": "q-1",
            "question": "Which limit applies?",
            "referenceNeedle": "42 units",
            "referenceAnswer": "42 units",
        }
        with self.assertRaisesRegex(ValueError, "2 non-answer corpus anchors"):
            validate_question_corpus_grounding([item], {"records": [evidence()]})

    def test_retry_prompt_provides_frozen_corpus_anchor_candidates_without_answer_or_source_terms(self) -> None:
        target = evidence("target")
        target["referenceContent"] = (
            "The Falcon migration uses a blue release channel. The approved limit is 42 units."
        )
        competitor = evidence("competitor", index=2)
        competitor["referenceContent"] = (
            "The Heron migration uses a green release channel. The approved limit is 84 units."
        )
        index = {
            "evidenceExportManifestPath": "manifest.json",
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [target, competitor],
        }
        anchors = distinctive_corpus_anchor_terms(
            target,
            index,
            excluded_texts=["42 units", "The approved limit is 42 units."],
        )
        self.assertIn("falcon", anchors)
        self.assertIn("blue", anchors)
        self.assertNotIn("units", anchors)
        self.assertNotIn("target", anchors)
        self.assertNotIn("d294699dex101", anchors)
        plan = corpus_anchor_plan(
            target,
            index,
            excluded_texts=["42 units", "The approved limit is 42 units."],
        )
        self.assertTrue(plan["coversEveryCompetingChunk"])
        self.assertIn("falcon", plan["requiredAnchors"])

        with TemporaryDirectory() as directory:
            root = Path(directory)
            responses = root / "responses"
            responses.mkdir()
            responses.joinpath("batch.json").write_text(
                json.dumps([generated("target") | {"question": "What accompanies the approved migration?"}]),
                encoding="utf-8",
            )
            output = root / "retry"
            write_validation_retry_batches(index, [responses], output, 1, "gemini-test")
            prompt = output.joinpath("retry-batch-01.md").read_text(encoding="utf-8")
            self.assertIn('"distinguishingAnchorCandidates"', prompt)
            self.assertIn('"requiredDistinguishingAnchors"', prompt)
            self.assertIn('"falcon"', prompt)
            self.assertNotIn('"units"', prompt)
            self.assertIn("MUST include every exact term in requiredDistinguishingAnchors", prompt)
            self.assertIn("Required anchors must remain non-answer context", prompt)
            self.assertIn("extra common terms can make a competing chunk score higher", prompt)
            self.assertIn('"avoidContentTerms"', prompt)
            self.assertIn("Do not use any exact term in avoidContentTerms", prompt)
            self.assertIn("Do not use any digits unless an exact four-digit year", prompt)
            self.assertIn('"approved"', prompt)
            self.assertIn('"safeNeutralTerms"', prompt)
            self.assertIn("safeNeutralTerms are verified absent from the frozen corpus", prompt)
            self.assertIn('"designation"', prompt)
            self.assertIn('"postal"', prompt)
            self.assertIn('"postcode"', prompt)
            payload = json.loads(prompt[prompt.index("[\n"):])
            self.assertIn("accompanies", payload[0]["avoidContentTerms"])
            self.assertNotIn("accompanies", payload[0]["safeNeutralTerms"])
            self.assertIn("designation", payload[0]["safeNeutralTerms"])
            self.assertIn("postal", payload[0]["safeNeutralTerms"])
            self.assertIn("postcode", payload[0]["safeNeutralTerms"])

    def test_anchor_planner_allows_year_context_but_rejects_arbitrary_numbers(self) -> None:
        target = evidence("target")
        target["referenceContent"] = "Falcon revenue guidance for 2025 was 42 units at location 20549."
        competitor = evidence("competitor", index=2)
        competitor["referenceContent"] = "Falcon revenue guidance for 2024 was 84 units at location 20549."
        index = {
            "evidenceExportManifestPath": "manifest.json",
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [target, competitor],
        }

        anchors = distinctive_corpus_anchor_terms(target, index)
        plan = corpus_anchor_plan(target, index)

        self.assertIn("2025", anchors)
        self.assertNotIn("20549", anchors)
        self.assertTrue(plan["coversEveryCompetingChunk"])
        self.assertIn("2025", plan["requiredAnchors"])

    def test_retry_planner_marks_lexically_indistinguishable_binding_for_replacement(self) -> None:
        target = evidence("target")
        competitor = evidence("competitor", index=2)
        competitor["referenceContent"] = target["referenceContent"]
        index = {
            "evidenceExportManifestPath": "manifest.json",
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [target, competitor],
        }
        plan = corpus_anchor_plan(target, index, excluded_texts=["42 units"])
        self.assertFalse(plan["coversEveryCompetingChunk"])

        with TemporaryDirectory() as directory:
            root = Path(directory)
            responses = root / "responses"
            responses.mkdir()
            responses.joinpath("batch.json").write_text(
                json.dumps([generated("target")]),
                encoding="utf-8",
            )
            output = root / "retry"
            manifest = write_validation_retry_batches(index, [responses], output, 1, "gemini-test")
            self.assertEqual(2, manifest["failedCount"])
            self.assertEqual(1, manifest["retryableCount"])
            self.assertEqual(["target"], manifest["replacementRequiredEvidenceIds"])
            self.assertEqual(1, len(manifest["files"]))
            self.assertNotIn('"evidenceId": "target"', Path(manifest["files"][0]).read_text(encoding="utf-8"))

    def test_retry_planner_queues_later_duplicate_answer_span(self) -> None:
        first = evidence("sec-1")
        second = evidence("sec-2")
        second.update({
            "sourceSha256": first["sourceSha256"],
            "chunkIndex": first["chunkIndex"],
            "referenceChunkId": first["referenceChunkId"],
            "referenceContent": first["referenceContent"],
        })
        index = {
            "evidenceExportManifestPath": "manifest.json",
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [first, second],
        }
        rows = [
            generated("sec-1") | {
                "question": "What approved quantity applies when verification must finish before release?"
            },
            generated("sec-2") | {
                "question": "Which approved quantity accompanies verification before release?"
            },
        ]
        with TemporaryDirectory() as directory:
            root = Path(directory)
            responses = root / "responses"
            responses.mkdir()
            responses.joinpath("batch.json").write_text(json.dumps(rows), encoding="utf-8")
            output = root / "retry"
            manifest = write_validation_retry_batches(index, [responses], output, 1, "gemini-test")
            prompt = output.joinpath("retry-batch-01.md").read_text(encoding="utf-8")

        self.assertEqual(1, manifest["failedCount"])
        self.assertEqual(
            1,
            manifest["validationFeedbackCounts"]["questions from one chunk must use distinct answer spans"],
        )
        self.assertIn("every exact reservedAnswerSpans value", prompt)
        self.assertIn('"reservedAnswerSpans": [', prompt)
        self.assertIn('"The approved limit is 42 units."', prompt)
        self.assertIn('"evidenceId": "sec-2"', prompt)
        self.assertNotIn('"evidenceId": "sec-1"', prompt)

    def test_retry_planner_replays_manual_replacement(self) -> None:
        index = {
            "evidenceExportManifestPath": "manifest.json",
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [evidence()],
        }
        receipt = {
            "version": "b3.3-codex-manual-replacement-v1",
            "evidenceId": "sec-1",
            "question": "What approved quantity is associated with verification before release?",
            "referenceNeedle": "The approved limit is 42 units.",
            "referenceAnswer": "42 units",
            "generatorModel": "gpt-5-codex",
            "generatorTool": "codex-desktop",
            "authorizedBy": "user",
            "authorizedAt": "2026-06-14",
            "reason": "Gemini Pro quota locked.",
        }
        with TemporaryDirectory() as directory:
            root = Path(directory)
            responses = root / "responses"
            responses.mkdir()
            responses.joinpath("batch.json").write_text(
                json.dumps([generated() | {"question": "What is the approved limit?"}]),
                encoding="utf-8",
            )
            manifest = write_validation_retry_batches(
                index, [responses], root / "retry", 1, "gemini-test", receipt
            )

        self.assertEqual(0, manifest["failedCount"])
        self.assertEqual(0, manifest["retryableCount"])

    def test_rejects_source_filename_and_reference_leaks(self) -> None:
        item = generated()
        item["question"] = "What does sec-1.html say?"
        with self.assertRaisesRegex(ValueError, "leaks"):
            validate_question(item, evidence())
        item["question"] = "Why is The approved limit is 42 units. important?"
        with self.assertRaisesRegex(ValueError, "leaks"):
            validate_question(item, evidence())
        item = generated()
        item["referenceAnswer"] = "42"
        item["question"] = "Why is 42 the approved limit?"
        with self.assertRaisesRegex(ValueError, "leaks"):
            validate_question(item, evidence())
        item = generated()
        item["question"] = f"What is associated with {evidence()['sourceSha256'][:12]}?"
        with self.assertRaisesRegex(ValueError, "leaks"):
            validate_question(item, evidence())

    def test_rejects_non_verbatim_needle_and_unsupported_answer(self) -> None:
        item = generated()
        item["referenceNeedle"] = "not present"
        with self.assertRaisesRegex(ValueError, "verbatim"):
            validate_question(item, evidence())
        item = generated()
        item["referenceAnswer"] = "Bananas and pineapples are mandatory."
        with self.assertRaisesRegex(ValueError, "supported"):
            validate_question(item, evidence())

    def test_rejects_forbidden_generated_fields(self) -> None:
        index = {
            "evidenceExportManifestPath": "manifest.json",
            "evidenceExportDatasetHash": "sha256:dataset",
            "records": [evidence()],
        }
        item = generated() | {"filename": "leak.html"}
        with self.assertRaisesRegex(ValueError, "forbidden fields"):
            build_candidates([item], index, generator_model="gemini-test", enforce_quotas=False)

    def test_rejects_near_duplicates_and_more_than_three_per_chunk(self) -> None:
        base = {
            "filename": "same.html",
            "sourceSha256": "same-source",
            "format": "SEC_HTML",
            "split": "calibration",
            "chunkIndex": 1,
            "referenceNeedle": "needle",
        }
        with self.assertRaisesRegex(ValueError, "near-duplicate"):
            validate_question_collection(
                [base | {"id": "a", "question": "What is the approved limit?", "referenceNeedle": "needle-a"},
                 base | {"id": "b", "question": "What is the approved limits?", "referenceNeedle": "needle-b"}],
                enforce_quotas=False,
            )
        rows = [
            base | {"id": f"q-{i}", "question": hashlib.sha256(str(i).encode()).hexdigest(), "referenceNeedle": f"n-{i}"}
            for i in range(4)
        ]
        with self.assertRaisesRegex(ValueError, "at most 3"):
            validate_question_collection(rows, enforce_quotas=False)

    def test_same_filename_and_chunk_index_from_different_sources_are_distinct(self) -> None:
        rows = [
            {
                "id": f"q-{i}",
                "filename": "README.md",
                "sourceSha256": f"source-{i}",
                "format": "WEB_MD",
                "split": "calibration",
                "chunkIndex": 1,
                "question": hashlib.sha256(str(i).encode()).hexdigest(),
                "referenceNeedle": f"needle-{i}",
            }
            for i in range(5)
        ]
        validate_question_collection(rows, enforce_quotas=False)

    def test_evidence_loader_preserves_same_named_chunks_from_different_sources(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            dataset = root / "datasets" / "doc-ingestion" / "doc-ingestion-retrieval-v1.jsonl"
            dataset.parent.mkdir(parents=True)
            content = "The approved limit is 42 units. " * 5
            source_manifest = {"sources": []}
            inventory = []
            chunk_evidence = []
            rows = []
            for index in range(2):
                source_manifest["sources"].append({
                    "documentId": f"doc-{index}",
                    "sourceUrl": f"https://example.invalid/{index}",
                    "filename": "README.md",
                    "sourceGroup": "test",
                    "format": "WEB_MD",
                    "split": "calibration",
                    "sha256": f"source-{index}",
                })
                inventory.append({
                    "sourceUrl": f"https://example.invalid/{index}",
                    "sourceSha256": f"source-{index}",
                    "filename": "README.md",
                    "chunkId": f"chunk-{index}",
                    "chunkIndex": 0,
                    "contentLength": len(content),
                    "contentHash": f"sha256:{hashlib.sha256(content.encode()).hexdigest()}",
                    "parser": "MarkdownDocumentParser",
                    "chunker": "StructureAwareMarkdownChunker",
                })
                chunk_evidence.append(inventory[-1] | {
                    "sourceGroup": "test",
                    "format": "WEB_MD",
                    "split": "calibration",
                    "content": content,
                })
                rows.append({
                    "fileFormat": "WEB_MD",
                    "split": "calibration",
                    "sourceUrl": f"https://example.invalid/{index}",
                    "referenceContextIds": [f"chunk-{index}"],
                    "metadata": {
                        "referenceDocFilename": "README.md",
                        "sourceSha256": f"source-{index}",
                        "sourceGroup": "test",
                        "referenceContent": content,
                        "candidateContexts": [{
                            "documentName": "README.md",
                            "documentId": f"doc-{1 - index}",
                            "chunkIndex": 0,
                            "chunkId": f"chunk-{1 - index}",
                            "content": content,
                        }],
                    },
                })
            dataset.write_text(
                "".join(json.dumps(row) + "\n" for row in rows),
                encoding="utf-8",
                newline="\n",
            )
            dataset_hash = file_hash(dataset)
            manifest = root / "manifests" / "datasets" / "doc-ingestion-retrieval-v1.json"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(
                json.dumps({
                    "datasetHash": dataset_hash,
                    "localPath": "datasets/doc-ingestion/doc-ingestion-retrieval-v1.jsonl",
                }),
                encoding="utf-8",
            )
            source_manifest_path = root / "source-manifest.json"
            source_manifest_path.write_text(json.dumps(source_manifest), encoding="utf-8")
            inventory_path = root / "chunk-inventory.json"
            inventory_path.write_text(json.dumps(inventory), encoding="utf-8")
            chunk_evidence_path = root / "chunk-evidence.json"
            chunk_evidence_path.write_text(json.dumps(chunk_evidence), encoding="utf-8")
            receipt_path = root / "reference-rebind-receipt.json"
            receipt_path.write_text(
                json.dumps({
                    "sourceManifestHash": "sha256:frozen-baseline-source-manifest",
                    "chunkInventoryHash": file_hash(inventory_path),
                }),
                encoding="utf-8",
            )
            (root / "b3-export-manifest.json").write_text(json.dumps({
                "datasetHash": dataset_hash,
                "rebindReceiptHash": file_hash(receipt_path),
                "sourceManifestHash": file_hash(source_manifest_path),
                "chunkInventoryHash": file_hash(inventory_path),
                "chunkEvidenceHash": file_hash(chunk_evidence_path),
            }), encoding="utf-8")

            loaded = load_evidence_export(root)

            self.assertEqual(2, len(loaded["records"]))
            self.assertEqual(2, len({item["evidenceId"] for item in loaded["records"]}))
            self.assertEqual({"source-0", "source-1"}, {item["sourceSha256"] for item in loaded["records"]})
            by_chunk = {item["referenceChunkId"]: item for item in loaded["records"]}
            self.assertEqual("source-0", by_chunk["chunk-0"]["sourceSha256"])
            self.assertEqual("source-1", by_chunk["chunk-1"]["sourceSha256"])
            self.assertEqual("https://example.invalid/0", by_chunk["chunk-0"]["sourceUrl"])
            self.assertEqual("https://example.invalid/1", by_chunk["chunk-1"]["sourceUrl"])

            chunk_evidence_path.write_text(json.dumps(chunk_evidence[:-1]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "chunk evidence bytes"):
                load_evidence_export(root)

    def test_reference_needle_normalizes_only_contiguous_evidence_tokens(self) -> None:
        content = "Approved value:\n42 units."
        self.assertEqual("Approved value:\n42 units", resolve_reference_needle("Approved value 42 units", content))
        self.assertEqual("Approved 42", resolve_reference_needle("Approved 42", content))

    def test_response_retries_are_latest_wins_but_in_file_duplicates_fail(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "batch-01.json").write_text(json.dumps([generated()]), encoding="utf-8")
            retry = generated() | {"question": "Which limit was approved?"}
            (root / "retry-01.json").write_text(json.dumps([retry]), encoding="utf-8")
            rows = load_latest_generated_rows(root)
            self.assertEqual(1, len(rows))
            self.assertEqual("Which limit was approved?", rows[0]["question"])

            (root / "retry-02.json").write_text(json.dumps([retry, retry]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate evidenceId"):
                load_latest_generated_rows(root)

    def test_response_loader_requires_exact_expected_ids_across_clean_retry_dirs(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            initial = root / "initial"
            retry = root / "retry"
            initial.mkdir()
            retry.mkdir()
            (initial / "batch.json").write_text(json.dumps([generated("sec-1")]), encoding="utf-8")
            corrected = generated("sec-2") | {"question": "Which limit was approved?"}
            (retry / "retry.json").write_text(json.dumps([corrected]), encoding="utf-8")

            rows = load_latest_generated_rows([initial, retry], {"sec-1", "sec-2"})

            self.assertEqual({"sec-1", "sec-2"}, {row["evidenceId"] for row in rows})
            with self.assertRaisesRegex(ValueError, "every expected"):
                load_latest_generated_rows([initial], {"sec-1", "sec-2"})
            (retry / "unknown.json").write_text(json.dumps([generated("unknown")]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "unknown evidenceId"):
                load_latest_generated_rows([initial, retry], {"sec-1", "sec-2"})

    def test_selection_enforces_each_format_split_minimum(self) -> None:
        records = []
        for fmt in ("SEC_HTML", "PDF", "DOCX", "XLSX", "WEB_MD"):
            for split, count in (("calibration", 20), ("development", 10), ("holdout", 10)):
                records.extend(evidence(f"{fmt}-{split}-{i}", fmt=fmt, split=split, index=i) for i in range(count))
            records.extend(evidence(f"{fmt}-extra-{i}", fmt=fmt, split="calibration", index=100 + i) for i in range(60))
        selected = select_generation_evidence({"records": records}, target_total=500)
        self.assertEqual(500, len(selected))
        for fmt in ("SEC_HTML", "PDF", "DOCX", "XLSX", "WEB_MD"):
            self.assertEqual(100, sum(item["format"] == fmt for item in selected))

    def test_selection_uses_max_three_per_chunk_fallback_without_weakening_unique_ratio(self) -> None:
        records = []
        for fmt in ("SEC_HTML", "PDF", "DOCX", "XLSX", "WEB_MD"):
            for split, count in (("calibration", 30), ("development", 15), ("holdout", 15)):
                records.extend(evidence(f"{fmt}-{split}-{i}", fmt=fmt, split=split, index=i) for i in range(count))
        selected = select_generation_evidence({"records": records}, target_total=500)
        self.assertEqual(500, len(selected))
        for fmt in ("SEC_HTML", "PDF", "DOCX", "XLSX", "WEB_MD"):
            rows = [item for item in selected if item["format"] == fmt]
            chunk_counts = {}
            for item in rows:
                key = (item["sourceSha256"], item["chunkIndex"])
                chunk_counts[key] = chunk_counts.get(key, 0) + 1
            self.assertLessEqual(max(chunk_counts.values()), 3)
            self.assertGreaterEqual(len(chunk_counts) / len(rows), 0.60)
            self.assertTrue(any("-q2" in item["evidenceId"] for item in rows))

    def test_selection_excludes_indistinguishable_chunks_and_supplies_initial_anchor_plan(self) -> None:
        records = []
        for fmt in ("SEC_HTML", "PDF", "DOCX", "XLSX", "WEB_MD"):
            for split, count in (("calibration", 61), ("development", 20), ("holdout", 20)):
                records.extend(evidence(f"{fmt}-{split}-{i}", fmt=fmt, split=split, index=i) for i in range(count))
        first = records[0]
        duplicate = evidence("duplicate-binding", fmt=first["format"], split=first["split"], index=999)
        duplicate["referenceContent"] = first["referenceContent"]
        records.append(duplicate)

        selected = select_generation_evidence({"records": records}, target_total=500)

        self.assertEqual(500, len(selected))
        self.assertNotIn(first["evidenceId"], {item["evidenceId"] for item in selected})
        self.assertNotIn(duplicate["evidenceId"], {item["evidenceId"] for item in selected})
        self.assertTrue(all(len(item["requiredDistinguishingAnchors"]) >= 2 for item in selected))
        prompt = build_prompt(selected[:1])
        self.assertIn('"requiredDistinguishingAnchors"', prompt)
        self.assertIn("Include every requiredDistinguishingAnchors term naturally", prompt)

    def test_selection_rebalances_away_from_a_format_at_its_unique_ratio_cap(self) -> None:
        records = []
        for fmt, counts in {
            "SEC_HTML": (50, 20, 20),
            "PDF": (22, 9, 13),
            "DOCX": (50, 20, 20),
            "XLSX": (50, 20, 20),
            "WEB_MD": (50, 20, 20),
        }.items():
            for split, count in zip(("calibration", "development", "holdout"), counts):
                records.extend(evidence(f"{fmt}-{split}-{i}", fmt=fmt, split=split, index=i) for i in range(count))

        selected = select_generation_evidence({"records": records}, target_total=500)

        self.assertEqual(500, len(selected))
        pdf_rows = [item for item in selected if item["format"] == "PDF"]
        self.assertLessEqual(len(pdf_rows), 73)
        self.assertGreaterEqual(
            len({(item["sourceSha256"], item["chunkIndex"]) for item in pdf_rows}) / len(pdf_rows),
            0.60,
        )


if __name__ == "__main__":
    unittest.main()
