"""Download and normalize IBM MTRAG human multi-turn tasks."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from chatagent_eval.datasets import (
    add_source_group_splits,
    build_dataset_manifest,
    build_split_manifest,
    build_source_manifest,
    read_jsonl,
    validate_size_gate,
    write_json,
    write_jsonl,
)
from chatagent_eval.downloaders.common import download_file


def prepare(catalog: dict[str, Any], output_root: Path) -> dict[str, Any]:
    raw_root = output_root / "raw" / catalog["sourceId"]
    source_path = download_file(
        catalog["sourceUrl"],
        raw_root / "reference.jsonl",
        expected_sha256=catalog["expectedSha256"],
    )
    tasks = read_jsonl(source_path)
    rag_rows = add_source_group_splits([_rag_row(task) for task in tasks])
    memory_rows = add_source_group_splits([_memory_row(task) for task in tasks])

    documents: dict[str, dict[str, Any]] = {}
    for task in tasks:
        for context in task.get("contexts", []):
            document_id = str(context["document_id"])
            documents.setdefault(
                document_id,
                {
                    "documentId": document_id,
                    "title": context.get("title", ""),
                    "text": context.get("text", ""),
                    "sourceGroupId": document_id,
                },
            )

    corpus_path = output_root / "corpora" / "mtrag-human-reference" / "documents.jsonl"
    rag_path = output_root / "datasets" / "rag" / "mtrag-human-rag-v1.jsonl"
    memory_path = output_root / "datasets" / "memory" / "memory-v2-dialogues.jsonl"
    write_jsonl(corpus_path, (documents[key] for key in sorted(documents)))
    write_jsonl(rag_path, rag_rows)
    write_jsonl(memory_path, memory_rows)
    rag_split_path = output_root / "manifests" / "splits" / "mtrag-human-rag-v1.json"
    memory_split_path = output_root / "manifests" / "splits" / "memory-v2-dialogues.json"
    write_json(rag_split_path, build_split_manifest("mtrag-human-rag-v1", rag_rows))
    write_json(memory_split_path, build_split_manifest("memory-v2-dialogues", memory_rows))

    conversation_count = len({str(task["conversation_id"]) for task in tasks})
    source_manifest = build_source_manifest(
        source_id=catalog["sourceId"],
        source_url=catalog["sourceUrl"],
        source_revision=catalog["sourceRevision"],
        license_name=catalog["license"],
        license_url=catalog["licenseUrl"],
        output_root=output_root,
        local_path=raw_root,
        files=[source_path],
        counts={"documents": len(documents), "queries": len(tasks), "conversations": conversation_count},
        notes=catalog["notes"],
    )
    manifests = [
        build_dataset_manifest(
            dataset_id="mtrag-human-rag-v1",
            version=1,
            source_ids=[catalog["sourceId"]],
            record_schema="eval-retrieval-dataset-record.schema.json",
            output_root=output_root,
            dataset_path=rag_path,
            split_manifest_path=rag_split_path,
            rows=rag_rows,
        ),
        build_dataset_manifest(
            dataset_id="memory-v2-dialogues",
            version=2,
            source_ids=[catalog["sourceId"]],
            record_schema="eval-memory-dataset-record.schema.json",
            output_root=output_root,
            dataset_path=memory_path,
            split_manifest_path=memory_split_path,
            rows=memory_rows,
        ),
    ]
    write_json(output_root / "manifests" / "sources" / "mtrag-human.json", source_manifest)
    for manifest in manifests:
        write_json(output_root / "manifests" / "datasets" / f"{manifest['datasetId']}.json", manifest)
    validate_size_gate("memory", "full", {"tasks": len(memory_rows)})
    validate_size_gate("multiturn", "full", {"conversations": conversation_count})
    return {"source": source_manifest, "datasets": manifests}


def _rag_row(task: dict[str, Any]) -> dict[str, Any]:
    contexts = task.get("contexts", [])
    return {
        "sampleId": f"mtrag-{task['task_id']}",
        "datasetId": "mtrag-human-rag-v1",
        "sourceGroupId": str(task["conversation_id"]),
        "userInput": _last_user_text(task["input"]),
        "conversation": task["input"],
        "reference": _target_text(task),
        "referenceContextIds": [str(context["document_id"]) for context in contexts],
        "metadata": _metadata(task),
    }


def _memory_row(task: dict[str, Any]) -> dict[str, Any]:
    return {
        "sampleId": f"mtrag-memory-{task['task_id']}",
        "datasetId": "memory-v2-dialogues",
        "sourceGroupId": str(task["conversation_id"]),
        "turns": task["input"],
        "expectedResponse": _target_text(task),
        "referenceContextIds": [str(context["document_id"]) for context in task.get("contexts", [])],
        "metadata": _metadata(task),
    }


def _last_user_text(turns: list[dict[str, Any]]) -> str:
    for turn in reversed(turns):
        if turn.get("speaker") == "user":
            return str(turn["text"])
    raise ValueError("MTRAG task does not contain a user turn")


def _target_text(task: dict[str, Any]) -> str:
    targets = task.get("targets", [])
    return str(targets[0]["text"]) if targets else ""


def _metadata(task: dict[str, Any]) -> dict[str, Any]:
    return {
        "domain": task.get("Collection", ""),
        "answerability": task.get("Answerability", []),
        "multiTurn": task.get("Multi-Turn", []),
        "questionType": task.get("Question Type", []),
        "sourceTaskId": task["task_id"],
    }
