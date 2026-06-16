"""Tests for Phase 10d-A doc-ingestion failure analysis."""

import json
import tempfile
import unittest
from pathlib import Path

from chatagent_eval.doc_ingestion_analysis import (
    DocIngestionAnalysisConfig,
    _analyze_row,
    run_doc_ingestion_failure_analysis,
)


DATASET_ID = "doc-ingestion-retrieval-v1"
REFERENCE_DOC_ID = "doc-ref"


def _write_dataset_root(root: Path, rows: list[dict]) -> None:
    jsonl_path = root / "datasets" / "doc-ingestion" / f"{DATASET_ID}.jsonl"
    jsonl_path.parent.mkdir(parents=True, exist_ok=True)
    with open(jsonl_path, "w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row) + "\n")
    manifest_path = root / "manifests" / "datasets" / f"{DATASET_ID}.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(
        json.dumps({"localPath": f"datasets/doc-ingestion/{DATASET_ID}.jsonl"}),
        encoding="utf-8",
    )


def _row(
    sample_id: str,
    *,
    reference_chunk: str = "chunk-ref",
    split: str = "holdout",
    fmt: str = "TXT",
    query: str = "What is the answer?",
    candidate_chunks: list[str] | None = None,
    same_doc_chunks: set[str] | None = None,
) -> dict:
    if candidate_chunks is None:
        candidate_chunks = [reference_chunk, "chunk-other"]
    same_doc_chunks = same_doc_chunks or set()
    contexts = [
        {
            "id": chunk,
            "chunkId": chunk,
            "documentId": (
                REFERENCE_DOC_ID if chunk == reference_chunk or chunk in same_doc_chunks else f"doc-distractor-{index}"
            ),
            "content": "The answer is forty-two." if chunk == reference_chunk else "Distractor text",
            "rankSignals": {
                "candidateRank": index + 1,
                "denseRank": index + 1,
                "bm25Rank": index + 1,
            },
        }
        for index, chunk in enumerate(candidate_chunks)
    ]
    return {
        "sampleId": sample_id,
        "datasetId": DATASET_ID,
        "sourceGroupId": REFERENCE_DOC_ID,
        "split": split,
        "fileId": REFERENCE_DOC_ID,
        "fileFormat": fmt,
        "sourceUrl": "https://example.com/source",
        "userInput": query,
        "referenceContextIds": [reference_chunk],
        "metadata": {
            "format": fmt,
            "referenceContent": "The answer is forty-two.",
            "referenceDocId": REFERENCE_DOC_ID,
            "sourceGroup": "test",
            "sourceUrl": "https://example.com/source",
            "generationMethod": "template",
            "candidateContexts": contexts,
        },
    }


class DocIngestionAnalysisTest(unittest.TestCase):
    def test_ranking_miss_when_reference_is_in_candidate_pool_but_outside_top_k(self):
        row = _row("s-1", candidate_chunks=["wrong-1", "wrong-2", "chunk-ref"])
        analyzed = _analyze_row(row, DocIngestionAnalysisConfig(run_id="test", top_k=2, candidate_k=3))
        self.assertFalse(analyzed["topHit"])
        self.assertTrue(analyzed["oracleHit"])
        self.assertEqual(analyzed["primaryFailureCategory"], "ranking_miss")
        self.assertEqual(analyzed["referenceRank"], 3)

    def test_same_document_wrong_chunk_excludes_reference_chunk_itself(self):
        row = _row(
            "s-1",
            candidate_chunks=["wrong-same-doc", "wrong-other", "chunk-ref"],
            same_doc_chunks={"wrong-same-doc"},
        )
        analyzed = _analyze_row(row, DocIngestionAnalysisConfig(run_id="test", top_k=2, candidate_k=3))
        self.assertFalse(analyzed["topHit"])
        self.assertTrue(analyzed["oracleHit"])
        self.assertTrue(analyzed["sameDocTopK"])
        self.assertEqual(analyzed["primaryFailureCategory"], "same_document_wrong_chunk")

    def test_candidate_pool_miss_when_reference_is_absent(self):
        row = _row("s-1", candidate_chunks=["wrong-1", "wrong-2"])
        analyzed = _analyze_row(row, DocIngestionAnalysisConfig(run_id="test", top_k=2, candidate_k=2))
        self.assertFalse(analyzed["topHit"])
        self.assertFalse(analyzed["oracleHit"])
        self.assertEqual(analyzed["primaryFailureCategory"], "candidate_pool_miss")

    def test_ambiguous_duplicate_evidence_when_wrong_document_contains_reference_words(self):
        row = _row("s-1", candidate_chunks=["wrong-1", "wrong-2"])
        row["metadata"]["candidateContexts"][0]["content"] = "The answer is forty-two."
        analyzed = _analyze_row(row, DocIngestionAnalysisConfig(run_id="test", top_k=2, candidate_k=2))
        self.assertFalse(analyzed["topHit"])
        self.assertFalse(analyzed["oracleHit"])
        self.assertIn("ambiguous_duplicate_evidence", analyzed["signals"])
        self.assertEqual(analyzed["primaryFailureCategory"], "ambiguous_duplicate_evidence")

    def test_run_writes_analysis_and_audit_artifacts(self):
        rows = [
            _row("hit", candidate_chunks=["chunk-ref", "wrong"]),
            _row("miss", candidate_chunks=["wrong-1", "wrong-2"]),
            _row("rank", candidate_chunks=["wrong-1", "wrong-2", "chunk-ref"]),
            _row(
                "repair",
                candidate_chunks=["wrong-same-doc", "wrong-other"],
                same_doc_chunks={"wrong-same-doc"},
            ),
            _row(
                "trainable-repair",
                split="development",
                candidate_chunks=["wrong-same-doc", "wrong-other"],
                same_doc_chunks={"wrong-same-doc"},
            ),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            dataset_root = Path(tmp) / "dataset"
            _write_dataset_root(dataset_root, rows)
            output_root = Path(tmp) / "out"
            run_dir = run_doc_ingestion_failure_analysis(
                dataset_root=dataset_root,
                output_root=output_root,
                config=DocIngestionAnalysisConfig(
                    run_id="analysis",
                    top_k=2,
                    candidate_k=3,
                    failed_audit_target=2,
                    success_audit_target=1,
                ),
            )
            self.assertTrue((run_dir / "analysis.json").exists())
            self.assertTrue((run_dir / "analysis.md").exists())
            self.assertTrue((run_dir / "audit-assist.md").exists())
            self.assertTrue((run_dir / "audit-sample.jsonl").exists())
            self.assertTrue((run_dir / "repair-candidates-trainable.jsonl").exists())
            self.assertTrue((run_dir / "repair-overlay-review-template.jsonl").exists())
            report = json.loads((run_dir / "analysis.json").read_text(encoding="utf-8"))
            self.assertEqual(report["summary"]["rowCount"], 5)
            self.assertEqual(report["summary"]["failureCount"], 4)
            self.assertEqual(report["recommendedFirstAxis"], "chunk_boundary_reference_quality")
            self.assertIn("bySourceGroupId", report)
            self.assertIn("byParser", report)
            self.assertIn("byChunker", report)
            self.assertIn("byQueryLengthBucket", report)
            self.assertIn("byMineruRoute", report)
            self.assertIn("byTableNumericFlag", report)
            self.assertIn("estimatedHoldoutRecallImpact", report["axisRanking"][0])
            self.assertIn("implementationCost", report["axisRanking"][0])
            self.assertIn("regressionRisk", report["axisRanking"][0])
            self.assertEqual(len((run_dir / "audit-sample.jsonl").read_text(encoding="utf-8").splitlines()), 3)
            repair_lines = (run_dir / "repair-candidates.jsonl").read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(repair_lines), 2)
            repair = json.loads(repair_lines[0])
            self.assertEqual(repair["sampleId"], "repair")
            self.assertIn("topContexts", repair)
            self.assertIn("candidateContexts", repair)
            self.assertFalse(repair["repairAllowedForTuning"])
            self.assertEqual(repair["topContexts"][0]["chunkId"], "wrong-same-doc")
            trainable = (run_dir / "repair-candidates-trainable.jsonl").read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(trainable), 1)
            self.assertEqual(json.loads(trainable[0])["sampleId"], "trainable-repair")
            overlay_template = (
                run_dir / "repair-overlay-review-template.jsonl"
            ).read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(overlay_template), 1)
            overlay_record = json.loads(overlay_template[0])
            self.assertEqual("trainable-repair", overlay_record["sampleId"])
            self.assertFalse(overlay_record["tuningAllowed"])
            self.assertTrue(overlay_record["reviewRequired"])
            self.assertEqual("reference_chunk_wrong", overlay_record["decision"])


if __name__ == "__main__":
    unittest.main()
