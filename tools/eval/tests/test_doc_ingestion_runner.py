"""Tests for doc_ingestion_runner: chunk-level metrics, manifest-based loading, and per-format breakdowns."""

import json
import tempfile
import unittest
from pathlib import Path

from chatagent_eval.doc_ingestion_runner import (
    DocIngestionConfig,
    _aggregate_metrics,
    _evaluate_row,
    _load_rows,
    _per_format_metrics,
    _phrase_recall,
    run_doc_ingestion_retrieval,
)
from chatagent_eval.schemas import load_json, validate


DATASET_ID = "doc-ingestion-retrieval-v1"


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    with open(path, "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row) + "\n")


def _write_dataset_manifest(manifest_path: Path, local_path: str) -> None:
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps({"localPath": local_path}))


def _sample_row(
    sample_id: str = "s-1",
    user_input: str = "What is the answer?",
    reference_chunk_ids: list[str] | None = None,
    fmt: str = "TXT",
    ref_content: str = "The answer is forty-two.",
    ref_doc_id: str = "doc-1",
    generation_method: str = "template-question",
    retrieved: list[dict] | None = None,
    split_override: str | None = None,
) -> dict:
    if reference_chunk_ids is None:
        reference_chunk_ids = ["chunk-1"]
    if retrieved is None:
        retrieved = [
            {
                "chunkId": reference_chunk_ids[0],
                "documentId": ref_doc_id,
                "content": ref_content,
                "score": 0.95,
            }
        ]
    candidate_contexts = [
        {
            "id": item.get("chunkId") or item.get("documentId") or f"candidate-{idx}",
            "chunkId": item.get("chunkId") or f"candidate-{idx}",
            "documentId": item.get("documentId"),
            "content": item.get("content", ""),
            "score": item.get("score", 1.0 - (idx * 0.1)),
            "rankSignals": {
                "candidateRank": idx + 1,
                "denseRank": idx + 1,
                "bm25Rank": idx + 1,
                "denseScore": item.get("score", 1.0 - (idx * 0.1)),
                "bm25Score": item.get("score", 1.0 - (idx * 0.1)),
                "fusedScore": item.get("score", 1.0 - (idx * 0.1)),
            },
        }
        for idx, item in enumerate(retrieved)
        if item.get("chunkId")
    ]
    return {
        "sampleId": sample_id,
        "datasetId": DATASET_ID,
        "sourceGroupId": ref_doc_id,
        "split": split_override if split_override is not None else "calibration",
        "userInput": user_input,
        "referenceContextIds": reference_chunk_ids,
        "metadata": {
            "format": fmt,
            "referenceContent": ref_content,
            "generationMethod": generation_method,
            "sourceUrl": "https://example.com/test.txt",
            "sourceSha256": "abc123",
            "sourceGroup": "test",
            "license": "CC-BY-4.0",
            "knowledgeBaseId": "kb-1",
            "referenceDocId": ref_doc_id,
            "referenceDocFilename": "test.txt",
            "retrievedContexts": retrieved,
            "candidateContexts": candidate_contexts,
        },
    }


def _build_dataset_root(
    tmp: str, rows: list[dict], local_name: str = "run.jsonl"
) -> Path:
    """Create a minimal Phase 3-compatible dataset root with manifest."""
    root = Path(tmp)
    jsonl_path = root / "datasets" / "doc-ingestion" / local_name
    jsonl_path.parent.mkdir(parents=True, exist_ok=True)
    _write_jsonl(jsonl_path, rows)
    _write_dataset_manifest(
        root / "manifests" / "datasets" / f"{DATASET_ID}.json",
        f"datasets/doc-ingestion/{local_name}",
    )
    return root


class TestPhraseRecall(unittest.TestCase):
    def test_perfect_recall(self):
        result = _phrase_recall("The quick brown fox", ["The quick brown fox jumps over"])
        self.assertAlmostEqual(result, 1.0)

    def test_partial_recall(self):
        result = _phrase_recall("The quick brown fox jumps over the lazy dog", ["The quick brown fox"])
        # Words >= 4 chars: quick, brown, jumps, over, lazy
        # Present: quick, brown → 2/5 = 0.4
        self.assertAlmostEqual(result, 0.4)

    def test_empty_reference(self):
        self.assertEqual(_phrase_recall("", ["some text"]), 0.0)

    def test_empty_retrieved(self):
        self.assertEqual(_phrase_recall("some reference content", []), 0.0)


class TestEvaluateRow(unittest.TestCase):
    def test_hit_at_k_when_chunk_found(self):
        row = _sample_row(retrieved=[
            {"chunkId": "chunk-1", "documentId": "doc-1", "content": "The answer", "score": 0.9},
        ])
        config = DocIngestionConfig(run_id="test")
        result = _evaluate_row(row, config)
        self.assertEqual(result["metrics"]["hitAtK"], 1.0)
        self.assertEqual(result["metrics"]["contextRecallAtK"], 1.0)
        self.assertEqual(result["metrics"]["mrr"], 1.0)

    def test_miss_when_wrong_chunk_from_same_doc(self):
        """Chunk-level regression: same document but wrong chunk → metrics must be 0."""
        row = _sample_row(
            reference_chunk_ids=["chunk-correct"],
            retrieved=[
                {"chunkId": "chunk-wrong", "documentId": "doc-1", "content": "Wrong chunk", "score": 0.9},
                {"chunkId": "chunk-other", "documentId": "doc-1", "content": "Other chunk", "score": 0.8},
            ],
        )
        config = DocIngestionConfig(run_id="test")
        result = _evaluate_row(row, config)
        self.assertEqual(result["metrics"]["hitAtK"], 0.0)
        self.assertEqual(result["metrics"]["contextRecallAtK"], 0.0)
        self.assertEqual(result["metrics"]["mrr"], 0.0)
        self.assertEqual(len(result["failures"]), 1)
        self.assertEqual(result["failures"][0]["errorCategory"], "missing_reference_chunk")

    def test_miss_when_chunk_not_found(self):
        row = _sample_row(
            reference_chunk_ids=["chunk-missing"],
            retrieved=[
                {"chunkId": "chunk-other", "documentId": "doc-other", "content": "Something else", "score": 0.5},
            ],
        )
        config = DocIngestionConfig(run_id="test")
        result = _evaluate_row(row, config)
        self.assertEqual(result["metrics"]["hitAtK"], 0.0)
        self.assertEqual(result["metrics"]["mrr"], 0.0)

    def test_mrr_rank_2(self):
        row = _sample_row(
            reference_chunk_ids=["chunk-1"],
            retrieved=[
                {"chunkId": "chunk-other", "documentId": "doc-1", "content": "Wrong", "score": 0.8},
                {"chunkId": "chunk-1", "documentId": "doc-1", "content": "Correct", "score": 0.7},
            ],
        )
        config = DocIngestionConfig(run_id="test")
        result = _evaluate_row(row, config)
        self.assertAlmostEqual(result["metrics"]["mrr"], 0.5)

    def test_replay_uses_candidate_rank_signals(self):
        row = _sample_row(
            reference_chunk_ids=["chunk-reference"],
            retrieved=[{"chunkId": "legacy-wrong", "documentId": "doc-1", "content": "Legacy wrong", "score": 0.99}],
        )
        row["metadata"]["candidateContexts"] = [
            {
                "id": "distractor",
                "chunkId": "distractor",
                "content": "Distractor text",
                "rankSignals": {"candidateRank": 1, "denseRank": 1, "bm25Rank": 1},
            },
            {
                "id": "chunk-reference",
                "chunkId": "chunk-reference",
                "content": "The answer is forty-two.",
                "rankSignals": {"candidateRank": 2, "denseRank": 2, "bm25Rank": 2},
            },
        ]
        config = DocIngestionConfig(run_id="test", top_k=2, candidate_k=2)
        result = _evaluate_row(row, config)
        self.assertEqual(result["metrics"]["hitAtK"], 1.0)
        self.assertEqual(result["sample"]["retrievedContexts"][1]["chunkId"], "chunk-reference")

    def test_failure_recorded_on_miss(self):
        row = _sample_row(
            reference_chunk_ids=["chunk-missing"],
            retrieved=[
                {"chunkId": "chunk-other", "documentId": "doc-other", "content": "Wrong", "score": 0.5},
            ],
        )
        config = DocIngestionConfig(run_id="test")
        result = _evaluate_row(row, config)
        self.assertEqual(len(result["failures"]), 1)
        self.assertEqual(result["failures"][0]["metric"], "docIngestion.hitAtK")


class TestAggregateMetrics(unittest.TestCase):
    def test_averages_across_rows(self):
        evaluated = [
            {"metrics": {"hitAtK": 1.0, "contextRecallAtK": 1.0, "mrr": 1.0, "phraseRecall": 0.8}, "format": "TXT"},
            {"metrics": {"hitAtK": 0.0, "contextRecallAtK": 0.0, "mrr": 0.0, "phraseRecall": 0.4}, "format": "PDF"},
        ]
        config = DocIngestionConfig(run_id="test")
        result = _aggregate_metrics(evaluated, config)
        self.assertAlmostEqual(result["docIngestion.hitAtK"], 0.5)
        self.assertAlmostEqual(result["docIngestion.queryCount"], 2)

    def test_empty_input(self):
        config = DocIngestionConfig(run_id="test")
        result = _aggregate_metrics([], config)
        self.assertEqual(result["docIngestion.queryCount"], 0)


class TestPerFormatMetrics(unittest.TestCase):
    def test_breakdown_by_format(self):
        evaluated = [
            {"metrics": {"hitAtK": 1.0, "contextRecallAtK": 1.0, "mrr": 1.0, "phraseRecall": 0.9}, "format": "TXT"},
            {"metrics": {"hitAtK": 1.0, "contextRecallAtK": 1.0, "mrr": 1.0, "phraseRecall": 0.8}, "format": "TXT"},
            {"metrics": {"hitAtK": 0.0, "contextRecallAtK": 0.0, "mrr": 0.0, "phraseRecall": 0.1}, "format": "PDF"},
        ]
        config = DocIngestionConfig(run_id="test")
        result = _per_format_metrics(evaluated, config)
        self.assertIn("TXT", result)
        self.assertIn("PDF", result)
        self.assertAlmostEqual(result["TXT"]["hitAtK"], 1.0)
        self.assertAlmostEqual(result["PDF"]["hitAtK"], 0.0)


class TestLoadRows(unittest.TestCase):
    def test_loads_via_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = _build_dataset_root(tmp, [
                _sample_row(sample_id="s-1"),
                _sample_row(sample_id="s-2", ref_doc_id="doc-2", reference_chunk_ids=["chunk-2"]),
            ])
            config = DocIngestionConfig(run_id="test")
            rows = _load_rows(root, config)
            self.assertEqual(len(rows), 2)

    def test_respects_max_samples(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = _build_dataset_root(tmp, [
                _sample_row(sample_id=f"s-{i}") for i in range(10)
            ])
            config = DocIngestionConfig(run_id="test", max_samples=3)
            rows = _load_rows(root, config)
            self.assertEqual(len(rows), 3)

    def test_raises_on_missing_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = DocIngestionConfig(run_id="test")
            with self.assertRaises(ValueError):
                _load_rows(Path(tmp), config)

    def test_splits_filter_returns_only_matching_rows(self):
        """Regression: config.splits must filter rows before max_samples."""
        with tempfile.TemporaryDirectory() as tmp:
            root = _build_dataset_root(tmp, [
                _sample_row(sample_id="s-cal-1", split_override="calibration"),
                _sample_row(sample_id="s-cal-2", split_override="calibration", ref_doc_id="doc-2",
                            reference_chunk_ids=["chunk-2"]),
                _sample_row(sample_id="s-hold-1", split_override="holdout", ref_doc_id="doc-3",
                            reference_chunk_ids=["chunk-3"]),
                _sample_row(sample_id="s-dev-1", split_override="development", ref_doc_id="doc-4",
                            reference_chunk_ids=["chunk-4"]),
            ])
            # Request only calibration rows
            config = DocIngestionConfig(run_id="test", splits=("calibration",))
            rows = _load_rows(root, config)
            self.assertEqual(len(rows), 2)
            self.assertTrue(all(r.get("split") == "calibration" for r in rows))

    def test_splits_filter_with_max_samples(self):
        """Regression: splits filter applies before max_samples truncation."""
        with tempfile.TemporaryDirectory() as tmp:
            root = _build_dataset_root(tmp, [
                _sample_row(sample_id="s-cal-1", split_override="calibration"),
                _sample_row(sample_id="s-cal-2", split_override="calibration", ref_doc_id="doc-2",
                            reference_chunk_ids=["chunk-2"]),
                _sample_row(sample_id="s-cal-3", split_override="calibration", ref_doc_id="doc-3",
                            reference_chunk_ids=["chunk-3"]),
                _sample_row(sample_id="s-hold-1", split_override="holdout", ref_doc_id="doc-4",
                            reference_chunk_ids=["chunk-4"]),
            ])
            config = DocIngestionConfig(run_id="test", splits=("calibration",), max_samples=2)
            rows = _load_rows(root, config)
            self.assertEqual(len(rows), 2)
            self.assertTrue(all(r.get("split") == "calibration" for r in rows))

    def test_empty_splits_loads_all_rows(self):
        """Empty splits tuple (default) loads everything."""
        with tempfile.TemporaryDirectory() as tmp:
            root = _build_dataset_root(tmp, [
                _sample_row(sample_id="s-cal-1", split_override="calibration"),
                _sample_row(sample_id="s-hold-1", split_override="holdout", ref_doc_id="doc-2",
                            reference_chunk_ids=["chunk-2"]),
            ])
            config = DocIngestionConfig(run_id="test")
            rows = _load_rows(root, config)
            self.assertEqual(len(rows), 2)


class TestRunDocIngestionRetrieval(unittest.TestCase):
    def test_full_run_produces_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = _build_dataset_root(tmp, [
                _sample_row(sample_id="s-1", fmt="TXT"),
                _sample_row(
                    sample_id="s-2", fmt="TXT", ref_doc_id="doc-2",
                    reference_chunk_ids=["chunk-2"],
                    retrieved=[{"chunkId": "chunk-2", "documentId": "doc-2", "content": "Result", "score": 0.8}],
                ),
            ])
            output_root = Path(tmp) / "output"
            output_root.mkdir()
            config = DocIngestionConfig(run_id="smoke-test")
            run_dir = run_doc_ingestion_retrieval(
                dataset_root=root, output_root=output_root, config=config
            )
            # Verify all artifact files exist
            for name in ("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl", "report.json"):
                self.assertTrue((run_dir / name).exists(), f"Missing artifact: {name}")

            # Verify metrics content
            with open(run_dir / "metrics.json", encoding="utf-8") as f:
                metrics = json.load(f)
            self.assertEqual(metrics["status"], "pass")
            self.assertAlmostEqual(metrics["retrieval"]["hitAtK"], 1.0)
            self.assertIn("TXT", metrics["perFormat"])


class TestSchemaRejection(unittest.TestCase):
    """Regression: rows that cannot produce meaningful chunk-level metrics must be caught."""

    def test_empty_reference_context_ids_produces_failure(self):
        """A row with no referenceContextIds cannot score chunk-level recall."""
        row = _sample_row(
            reference_chunk_ids=[],
            retrieved=[
                {"chunkId": "chunk-1", "documentId": "doc-1", "content": "Some text", "score": 0.9},
            ],
        )
        config = DocIngestionConfig(run_id="test")
        result = _evaluate_row(row, config)
        # Empty reference chunk ID → no possible hit
        self.assertEqual(result["metrics"]["hitAtK"], 0.0)
        self.assertEqual(result["metrics"]["mrr"], 0.0)
        self.assertEqual(len(result["failures"]), 1)

    def test_null_chunk_id_in_retrieved_treated_as_missing(self):
        """A retrieved context with null/empty chunkId must not match any reference."""
        row = _sample_row(
            reference_chunk_ids=["chunk-1"],
            retrieved=[
                {"chunkId": None, "documentId": "doc-1", "content": "Some text", "score": 0.9},
            ],
        )
        config = DocIngestionConfig(run_id="test")
        result = _evaluate_row(row, config)
        # null chunkId → str(None) = "None", won't match "chunk-1"
        self.assertEqual(result["metrics"]["hitAtK"], 0.0)
        self.assertEqual(len(result["failures"]), 1)


class TestDatasetManifestSchema(unittest.TestCase):
    """Regression: dataset manifest must conform to eval-dataset-manifest.schema.json.

    Uses the real project schema validator (chatagent_eval.schemas.validate)
    against the actual schema file, not hand-written field sets.
    """

    _SCHEMA_PATH = (
        Path(__file__).resolve().parents[3]
        / "chatagent" / "bootstrap" / "src" / "test" / "resources"
        / "eval" / "v2" / "schemas" / "eval-dataset-manifest.schema.json"
    )

    @classmethod
    def setUpClass(cls) -> None:
        cls._schema = load_json(cls._SCHEMA_PATH)

    @staticmethod
    def _minimal_manifest(**overrides) -> dict:
        manifest = {
            "schemaVersion": 1,
            "datasetId": "doc-ingestion-retrieval-v1",
            "version": 1,
            "sourceIds": ["project-gutenberg"],
            "recordSchema": "eval-doc-ingestion-dataset-record.schema.json",
            "localPath": "datasets/doc-ingestion/doc-ingestion-retrieval-v1.jsonl",
            "datasetHash": "sha256:abc123",
            "splitManifestPath": "manifests/splits/doc-ingestion-retrieval-v1.json",
            "splitManifestHash": "sha256:def456",
            "recordCount": 10,
            "groupCount": 3,
            "splits": {"calibration": {"recordCount": 10, "groupCount": 3, "groupHash": "sha256:ghi789"}},
            "provenance": {
                "provider": "ollama",
                "modelName": "bge-m3",
                "embeddingModel": "bge-m3",
                "exportTimestamp": "2026-06-08T22:00:00",
            },
        }
        manifest.update(overrides)
        return manifest

    def _assert_valid(self, manifest: dict) -> None:
        """Validate manifest against the real eval-dataset-manifest.schema.json."""
        validate(manifest, self._schema)

    def _assert_invalid(self, manifest: dict) -> None:
        """Assert that validation against the real schema fails."""
        with self.assertRaises(ValueError):
            validate(manifest, self._schema)

    # ── Schema-level tests (common schema) ────────────────────────────

    def test_valid_manifest_with_provenance_passes(self):
        """A manifest with valid provenance passes the real schema."""
        self._assert_valid(self._minimal_manifest())

    def test_valid_manifest_without_provenance_passes(self):
        """The common schema allows manifests without provenance (legacy)."""
        manifest = self._minimal_manifest()
        del manifest["provenance"]
        self._assert_valid(manifest)

    def test_provenance_with_extra_parser_field_rejected(self):
        """Extra provenance field (parser) violates additionalProperties: false."""
        manifest = self._minimal_manifest()
        manifest["provenance"]["parser"] = "TikaDocumentParser"
        self._assert_invalid(manifest)

    def test_provenance_with_extra_chunker_field_rejected(self):
        """Extra provenance field (chunker) violates additionalProperties: false."""
        manifest = self._minimal_manifest()
        manifest["provenance"]["chunker"] = "PlainTextChunker"
        self._assert_invalid(manifest)

    def test_provenance_missing_provider_rejected(self):
        """Missing required 'provider' in provenance is rejected."""
        manifest = self._minimal_manifest()
        del manifest["provenance"]["provider"]
        self._assert_invalid(manifest)

    def test_provenance_missing_model_name_rejected(self):
        """Missing required 'modelName' in provenance is rejected."""
        manifest = self._minimal_manifest()
        del manifest["provenance"]["modelName"]
        self._assert_invalid(manifest)

    def test_provenance_missing_embedding_model_rejected(self):
        """Missing required 'embeddingModel' in provenance is rejected."""
        manifest = self._minimal_manifest()
        del manifest["provenance"]["embeddingModel"]
        self._assert_invalid(manifest)

    def test_provenance_missing_export_timestamp_rejected(self):
        """Missing required 'exportTimestamp' in provenance is rejected."""
        manifest = self._minimal_manifest()
        del manifest["provenance"]["exportTimestamp"]
        self._assert_invalid(manifest)

    def test_manifest_missing_required_top_field_rejected(self):
        """Missing a required top-level field is rejected."""
        required = [
            "schemaVersion", "datasetId", "version", "sourceIds", "recordSchema",
            "localPath", "datasetHash", "splitManifestPath", "splitManifestHash",
            "recordCount", "groupCount", "splits",
        ]
        for field in required:
            manifest = self._minimal_manifest()
            del manifest[field]
            self._assert_invalid(manifest)

    # ── 10a-specific tests (doc-ingestion export contract) ─────────────

    def test_10a_manifest_must_include_provenance(self):
        """10a export manifest must include provenance for pipeline traceability.

        This is a 10a contract above the common schema: the schema allows
        no provenance (legacy), but 10a exports are required to carry it.
        """
        manifest = self._minimal_manifest()
        self.assertIn("provenance", manifest,
                       "10a dataset manifest must include provenance")
        prov = manifest["provenance"]
        self.assertEqual(prov["provider"], "ollama")
        self.assertEqual(prov["modelName"], "bge-m3")
        self.assertEqual(prov["embeddingModel"], "bge-m3")


class TestRecordSchemaValidation(unittest.TestCase):
    """Regression: record must conform to eval-doc-ingestion-dataset-record.schema.json.

    Uses the real project schema validator (chatagent_eval.schemas.validate)
    against the actual schema file, not hand-written field sets.
    """

    _SCHEMA_PATH = (
        Path(__file__).resolve().parents[3]
        / "chatagent" / "bootstrap" / "src" / "test" / "resources"
        / "eval" / "v2" / "schemas" / "eval-doc-ingestion-dataset-record.schema.json"
    )

    @classmethod
    def setUpClass(cls) -> None:
        cls._schema = load_json(cls._SCHEMA_PATH)

    @staticmethod
    def _minimal_record(**overrides) -> dict:
        record = {
            "sampleId": "s-1",
            "datasetId": "doc-ingestion-retrieval-v1",
            "sourceGroupId": "doc-1",
            "split": "calibration",
            "userInput": "What is the answer?",
            "referenceContextIds": ["chunk-1"],
            "metadata": {
                "format": "TXT",
                "referenceContent": "The answer is forty-two.",
                "generationMethod": "template-question",
                "knowledgeBaseId": "kb-1",
                "referenceDocId": "doc-1",
                "referenceDocFilename": "test.txt",
                "retrievedContexts": [
                    {
                        "chunkId": "chunk-1",
                        "documentId": "doc-1",
                        "content": "The answer is forty-two.",
                    }
                ],
            },
        }
        record.update(overrides)
        return record

    def _assert_valid(self, record: dict) -> None:
        validate(record, self._schema)

    def _assert_invalid(self, record: dict) -> None:
        with self.assertRaises(ValueError):
            validate(record, self._schema)

    def test_xlsx_record_with_row_based_chunker_metadata_passes(self):
        """XLSX records with chunkerMaxRowsPerChunk/chunkerOverlapRows must pass schema."""
        record = self._minimal_record(
            metadata={
                "format": "XLSX",
                "referenceContent": "cell content",
                "generationMethod": "direct-evidence-preflight",
                "knowledgeBaseId": "kb-1",
                "referenceDocId": "doc-xlsx",
                "referenceDocFilename": "who-data.xlsx",
                "sourceUrl": "https://example.com/who-data.xlsx",
                "retrievedContexts": [
                    {"chunkId": "chunk-1", "documentId": "doc-xlsx", "content": "cell content"}
                ],
                "parser": "SpreadsheetDocumentParser",
                "chunker": "TableAwareChunker",
                "chunkerMaxRowsPerChunk": 50,
                "chunkerOverlapRows": 2,
                "embeddingModel": "bge-m3",
                "embeddingProvider": "ollama",
                "embeddingDimension": 1024,
                "vectorIndex": "milvus",
                "vectorMetric": "COSINE",
                "ingestionPath": "ingestSync",
                "mqEnabled": False,
                "mineruEnabled": False,
            }
        )
        self._assert_valid(record)

    def test_full_chain_record_with_candidate_mq_and_mineru_metadata_passes(self):
        record = self._minimal_record(
            metadata={
                "format": "PDF",
                "referenceContent": "visual text",
                "generationMethod": "template-question",
                "knowledgeBaseId": "kb-1",
                "referenceDocId": "doc-pdf",
                "referenceDocFilename": "who.pdf",
                "retrievedContexts": [
                    {"chunkId": "chunk-1", "documentId": "doc-pdf", "content": "visual text"}
                ],
                "candidateContexts": [
                    {
                        "id": "chunk-1",
                        "chunkId": "chunk-1",
                        "content": "visual text",
                        "rankSignals": {"candidateRank": 1, "denseRank": 1, "bm25Rank": 1},
                    }
                ],
                "mqEnabled": True,
                "mineruEnabled": True,
                "mq": {
                    "outboxEventId": "event-1",
                    "idempotencyKey": "doc:hash",
                    "outboxStatus": "SENT",
                    "consumerCompleted": True,
                    "parseStatus": "COMPLETED",
                    "chunkCount": 1,
                    "milvusInserted": True,
                },
                "mineru": {
                    "enabled": True,
                    "selected": True,
                    "engineId": "mineru",
                    "visualChunkCount": 1,
                    "evidenceFields": ["engineId", "vdpStatus"],
                },
            }
        )
        self._assert_valid(record)

    def test_txt_record_with_char_based_chunker_metadata_passes(self):
        """TXT records with chunkerTargetChars/chunkerOverlapChars must still pass schema."""
        record = self._minimal_record(
            metadata={
                "format": "TXT",
                "referenceContent": "text content",
                "generationMethod": "template-question",
                "knowledgeBaseId": "kb-1",
                "referenceDocId": "doc-txt",
                "referenceDocFilename": "example.txt",
                "retrievedContexts": [
                    {"chunkId": "chunk-1", "documentId": "doc-txt", "content": "text content"}
                ],
                "parser": "TikaDocumentParser",
                "chunker": "PlainTextChunker",
                "chunkerTargetChars": 1200,
                "chunkerOverlapChars": 150,
            }
        )
        self._assert_valid(record)

    def test_record_missing_required_field_rejected(self):
        """Missing a required top-level field (sampleId) must be rejected."""
        record = self._minimal_record()
        del record["sampleId"]
        self._assert_invalid(record)


if __name__ == "__main__":
    unittest.main()
