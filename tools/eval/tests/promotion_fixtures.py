from __future__ import annotations

import json
from pathlib import Path
from typing import Any

CONTROL_SCORES = {
    "no-rag": {"faithfulness": 0.62, "response_relevancy": 0.84, "factual_correctness": 0.58, "semantic_similarity": 0.60},
    "full-rag": {"faithfulness": 0.85, "response_relevancy": 0.88, "factual_correctness": 0.78, "semantic_similarity": 0.82, "context_precision": 0.80},
    "wrong-context": {"faithfulness": 0.30, "response_relevancy": 0.60, "factual_correctness": 0.25, "semantic_similarity": 0.30, "context_precision": 0.20},
    "oracle-context": {"faithfulness": 0.90, "response_relevancy": 0.90, "factual_correctness": 0.92, "semantic_similarity": 0.94, "context_precision": 0.95},
    "reranker-off": {"faithfulness": 0.80, "response_relevancy": 0.86, "factual_correctness": 0.75, "semantic_similarity": 0.80, "context_precision": 0.75},
    "reranker-on": {"faithfulness": 0.85, "response_relevancy": 0.88, "factual_correctness": 0.78, "semantic_similarity": 0.82, "context_precision": 0.80},
}


def write_candidate_artifact(
    root: Path,
    name: str,
    *,
    split: str | tuple[str, ...],
    config_fingerprint: str = "sha256:candidate",
    dataset_hash: str = "sha256:dataset",
    split_hashes: dict[str, str] | None = None,
    retrieval_overrides: dict[str, Any] | None = None,
    score_overrides: dict[str, dict[str, float]] | None = None,
) -> Path:
    artifact = root / name
    artifact.mkdir(parents=True)
    retrieval = {
        "contextRecallAtK": 0.72,
        "phraseRecall": 0.90,
        "hitAtK": 0.75,
        "mrr": 0.60,
        "preRerankMrr": 0.55,
        "topK": 8,
        "candidateK": 24,
        "rerankerMaxCandidates": 24,
        "rrfK": 60,
    }
    retrieval.update(retrieval_overrides or {})
    metrics = {
        "status": "pass",
        "retrieval": retrieval,
        "perFormat": {
            "SEC_HTML": {"contextRecallAtK": 0.73},
            "PDF": {"contextRecallAtK": 0.67},
            "DOCX": {"contextRecallAtK": 0.66},
            "XLSX": {"contextRecallAtK": 0.62},
            "WEB_MD": {"contextRecallAtK": 0.64},
        },
        "costUsd": 0.05,
        "gatePolicy": {
            "baselineContextRecall": 0.6294,
            "baselinePhraseRecall": 0.8552,
            "primaryRecallTarget": 0.70,
            "primaryRecallDelta": 0.08,
            "perFormatRegressionTolerance": 0.03,
            "minCitationSupport": 0.80,
            "minFullRagFaithfulness": 0.80,
            "minNegativeControlDegradation": 0.05,
            "maxLatencyP95Ms": 1000.0,
            "maxCostUsd": 0.10,
        },
        "limits": {"maxLatencyP95Ms": 1000.0, "maxCostUsd": 0.10},
    }
    (artifact / "metrics.json").write_text(json.dumps(metrics), encoding="utf-8")
    splits = (split,) if isinstance(split, str) else split
    split_hashes = split_hashes or {value: f"sha256:{value}" for value in splits}
    (artifact / "manifest.json").write_text(
        json.dumps(
            {
                "configFingerprint": config_fingerprint,
                "runId": name,
                "datasetId": "doc-ingestion-retrieval-v1",
                "datasetHash": dataset_hash,
                "config": {"splitHashes": split_hashes},
            }
        ),
        encoding="utf-8",
    )
    rows = []
    for source_index in range(2):
        source_id = f"source-{source_index}"
        chunk_id = f"chunk-{source_index}"
        for mode, base_scores in CONTROL_SCORES.items():
            scores = dict(base_scores)
            scores.update((score_overrides or {}).get(mode, {}))
            context_chunk_id = f"wrong-{source_index}" if mode == "wrong-context" else chunk_id
            contexts = [] if mode == "no-rag" else [
                {"id": context_chunk_id, "chunkId": context_chunk_id, "text": "Grounded context"}
            ]
            metadata: dict[str, Any] = {
                "format": "SEC_HTML",
                "sourceSampleId": source_id,
                "referenceContexts": [{"id": chunk_id, "text": "Grounded context"}],
                "controlRun": {"mode": mode},
                "ragasScores": scores,
                "latency": {"totalMs": 350.0},
                "tokenCounts": {"promptTokens": 100, "completionTokens": 20, "totalTokens": 120},
                "retrievalProvenance": {
                    "candidateK": 24,
                    "finalTopK": 8,
                    "rrfK": 60,
                    "rerankerMaxCandidates": 24,
                    "rerankerProvider": "bge-http",
                    "rerankerModel": "BAAI/bge-reranker-v2-m3",
                    "candidatePath": "KnowledgeBaseSimilaritySearcher.searchRankedCandidateHitsByKnowledgeBaseIds",
                    "finalPath": "KnowledgeBaseSimilaritySearcher.searchByKnowledgeBaseIds",
                    "primaryMetricsSource": "post-rerank-final-hits",
                },
            }
            if mode == "full-rag":
                metadata["citations"] = [{"sourceChunkId": chunk_id, "marker": "[1]", "supported": True}]
            rows.append(
                {
                    "sampleId": f"{source_id}-{mode}",
                    "datasetId": "doc-ingestion-retrieval-v1",
                    "sourceGroupId": f"group-{splits[source_index % len(splits)]}-{source_id}",
                    "split": splits[source_index % len(splits)],
                    "userInput": f"Question {source_index}?",
                    "response": f"Answer {source_index} [1]",
                    "reference": f"Reference {source_index}",
                    "referenceContextIds": [chunk_id],
                    "retrievedContexts": contexts,
                    "metadata": metadata,
                }
            )
    (artifact / "samples.jsonl").write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
    return artifact


def write_selection_artifact(
    root: Path,
    *,
    config_fingerprint: str = "sha256:candidate",
    dataset_hash: str = "sha256:dataset",
    sealed_holdout_accessed: bool = False,
) -> Path:
    selection = root / "selection"
    selection.mkdir(parents=True)
    (selection / "selection.json").write_text(
        json.dumps(
            {
                "runId": "joint-selection",
                "datasetHash": dataset_hash,
                "searchSplits": ["calibration", "development"],
                "searchSplitHashes": {"calibration": "sha256:cal", "development": "sha256:dev"},
                "searchSourceGroupIds": [
                    "group-calibration-source-0",
                    "group-development-source-1",
                ],
                "sealedHoldoutAccessed": sealed_holdout_accessed,
                "selectedConfigFingerprint": config_fingerprint,
            }
        ),
        encoding="utf-8",
    )
    return selection


def write_preflight_artifact(
    root: Path,
    *,
    status: str = "pass",
    approved_candidate_k: tuple[int, ...] = (24,),
) -> Path:
    preflight = root / "preflight"
    preflight.mkdir(parents=True)
    (preflight / "preflight.json").write_text(
        json.dumps(
            {
                "runId": "preflight-run",
                "status": status,
                "config": {"datasetId": "doc-ingestion-retrieval-v1", "controlModeCount": 6},
                "checks": [
                    {
                        "id": "budget.candidateK",
                        "status": "pass" if approved_candidate_k else "fail",
                        "candidateK": {"approved": list(approved_candidate_k), "skipped": []},
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    return preflight
