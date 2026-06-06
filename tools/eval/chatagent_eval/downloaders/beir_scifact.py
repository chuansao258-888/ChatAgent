"""Download and normalize the approved BEIR SciFact retrieval corpus."""

from __future__ import annotations

import csv
import json
from collections import defaultdict
from pathlib import Path
from typing import Any

from chatagent_eval.datasets import (
    add_source_group_splits,
    build_dataset_manifest,
    build_split_manifest,
    build_source_manifest,
    connected_relevance_groups,
    read_jsonl,
    validate_size_gate,
    write_json,
    write_jsonl,
)
from chatagent_eval.downloaders.common import download_file, safe_extract_zip


def prepare(catalog: dict[str, Any], output_root: Path) -> dict[str, Any]:
    raw_root = output_root / "raw" / catalog["sourceId"]
    archive = download_file(
        catalog["sourceUrl"],
        raw_root / "scifact.zip",
        expected_sha256=catalog["expectedSha256"],
    )
    extracted_root = raw_root / "extracted"
    if not (extracted_root / "scifact" / "corpus.jsonl").exists():
        safe_extract_zip(archive, extracted_root)
    source_root = extracted_root / "scifact"

    corpus = read_jsonl(source_root / "corpus.jsonl")
    queries = {row["_id"]: row["text"] for row in read_jsonl(source_root / "queries.jsonl")}
    qrels = _load_qrels(source_root / "qrels")
    groups = connected_relevance_groups(qrels)

    rows = add_source_group_splits(
        [
            {
                "sampleId": f"beir-scifact-{query_id}",
                "datasetId": "beir-scifact-rag-v1",
                "sourceGroupId": groups[query_id],
                "userInput": queries[query_id],
                "referenceContextIds": sorted(set(document_ids)),
                "metadata": {"domain": "scientific-fact-checking", "sourceQueryId": query_id},
            }
            for query_id, document_ids in sorted(qrels.items())
        ]
    )

    corpus_path = output_root / "corpora" / "beir-scifact" / "documents.jsonl"
    dataset_path = output_root / "datasets" / "rag" / "beir-scifact-rag-v1.jsonl"
    write_jsonl(
        corpus_path,
        (
            {
                "documentId": row["_id"],
                "title": row.get("title", ""),
                "text": row["text"],
                "sourceGroupId": f"doc:{row['_id']}",
            }
            for row in corpus
        ),
    )
    write_jsonl(dataset_path, rows)
    split_manifest_path = output_root / "manifests" / "splits" / "beir-scifact-rag-v1.json"
    write_json(split_manifest_path, build_split_manifest("beir-scifact-rag-v1", rows))

    source_manifest = build_source_manifest(
        source_id=catalog["sourceId"],
        source_url=catalog["sourceUrl"],
        source_revision=catalog["sourceRevision"],
        license_name=catalog["license"],
        license_url=catalog["licenseUrl"],
        output_root=output_root,
        local_path=raw_root,
        files=[archive],
        counts={"documents": len(corpus), "queries": len(rows)},
        notes=catalog["notes"],
    )
    dataset_manifest = build_dataset_manifest(
        dataset_id="beir-scifact-rag-v1",
        version=1,
        source_ids=[catalog["sourceId"]],
        record_schema="eval-retrieval-dataset-record.schema.json",
        output_root=output_root,
        dataset_path=dataset_path,
        split_manifest_path=split_manifest_path,
        rows=rows,
    )
    write_json(output_root / "manifests" / "sources" / "beir-scifact.json", source_manifest)
    write_json(output_root / "manifests" / "datasets" / "beir-scifact-rag-v1.json", dataset_manifest)
    validate_size_gate("retrieval", "full", {"queries": len(rows), "documents": len(corpus)})
    return {"source": source_manifest, "datasets": [dataset_manifest]}


def _load_qrels(qrels_root: Path) -> dict[str, list[str]]:
    result: dict[str, list[str]] = defaultdict(list)
    for path in sorted(qrels_root.glob("*.tsv")):
        with path.open(encoding="utf-8", newline="") as source:
            for row in csv.DictReader(source, delimiter="\t"):
                if int(row["score"]) > 0:
                    result[row["query-id"]].append(row["corpus-id"])
    return dict(result)
