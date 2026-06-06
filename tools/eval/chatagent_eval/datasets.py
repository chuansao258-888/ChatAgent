"""Dataset manifests, hashes, source-group splits, and size gates."""

from __future__ import annotations

import hashlib
import json
from collections import Counter
from collections.abc import Iterable, Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path, PurePosixPath
from typing import Any

SPLIT_BUCKETS = (
    ("calibration", 55),
    ("development", 20),
    ("holdout", 20),
    ("challenge", 5),
)
PLACEHOLDER_LICENSES = {"dataset-specific license", "unknown", "tbd", "n/a", "none"}


def utc_timestamp() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return f"sha256:{digest.hexdigest()}"


def sha256_json(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return f"sha256:{hashlib.sha256(payload.encode('utf-8')).hexdigest()}"


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: Iterable[Mapping[str, Any]]) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with path.open("w", encoding="utf-8", newline="\n") as target:
        for row in rows:
            target.write(json.dumps(row, ensure_ascii=False, separators=(",", ":"), sort_keys=True))
            target.write("\n")
            count += 1
    return count


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def split_for_group(group_id: str) -> str:
    bucket = int(hashlib.sha256(group_id.encode("utf-8")).hexdigest()[:8], 16) % 100
    upper = 0
    for split, width in SPLIT_BUCKETS:
        upper += width
        if bucket < upper:
            return split
    raise AssertionError("split buckets must cover 100 percent")


def add_source_group_splits(rows: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for row in rows:
        group_id = str(row["sourceGroupId"])
        result.append({**row, "split": split_for_group(group_id)})
    validate_no_split_leakage(result)
    return result


def validate_no_split_leakage(rows: Sequence[Mapping[str, Any]]) -> None:
    group_splits: dict[str, str] = {}
    for row in rows:
        group_id = str(row["sourceGroupId"])
        split = str(row["split"])
        previous = group_splits.setdefault(group_id, split)
        if previous != split:
            raise ValueError(f"source group {group_id} appears in both {previous} and {split}")


def connected_relevance_groups(qrels: Mapping[str, Sequence[str]]) -> dict[str, str]:
    """Group queries connected through any shared relevant document."""
    parent: dict[str, str] = {}

    def find(value: str) -> str:
        parent.setdefault(value, value)
        while parent[value] != value:
            parent[value] = parent[parent[value]]
            value = parent[value]
        return value

    def union(left: str, right: str) -> None:
        left_root = find(left)
        right_root = find(right)
        if left_root != right_root:
            parent[max(left_root, right_root)] = min(left_root, right_root)

    for document_ids in qrels.values():
        if not document_ids:
            continue
        first = f"doc:{document_ids[0]}"
        for document_id in document_ids[1:]:
            union(first, f"doc:{document_id}")

    groups: dict[str, str] = {}
    for query_id, document_ids in qrels.items():
        groups[query_id] = find(f"doc:{document_ids[0]}") if document_ids else f"query:{query_id}"
    return groups


def relative_local_path(path: Path, output_root: Path) -> str:
    resolved_root = output_root.resolve()
    resolved_path = path.resolve()
    if not resolved_path.is_relative_to(resolved_root):
        raise ValueError(f"path is outside output root: {path}")
    return resolved_path.relative_to(resolved_root).as_posix()


def file_entry(path: Path, output_root: Path, source_url: str | None = None) -> dict[str, Any]:
    entry = {
        "path": relative_local_path(path, output_root),
        "sha256": sha256_file(path),
        "bytes": path.stat().st_size,
    }
    if source_url is not None:
        if not source_url.startswith("https://"):
            raise ValueError("downloaded file sourceUrl must use https")
        entry["sourceUrl"] = source_url
    return entry


def build_source_manifest(
    *,
    source_id: str,
    source_url: str,
    source_revision: str,
    license_name: str,
    license_url: str,
    output_root: Path,
    local_path: Path,
    files: Sequence[Path],
    file_urls: Mapping[Path, str] | None = None,
    counts: Mapping[str, int],
    notes: str,
) -> dict[str, Any]:
    manifest = {
        "schemaVersion": 1,
        "sourceId": source_id,
        "sourceUrl": source_url,
        "sourceRevision": source_revision,
        "license": license_name,
        "licenseUrl": license_url,
        "downloadedAt": utc_timestamp(),
        "localPath": relative_local_path(local_path, output_root),
        "files": [file_entry(path, output_root, (file_urls or {}).get(path)) for path in sorted(files)],
        "counts": dict(counts),
        "notes": notes,
    }
    validate_source_manifest(manifest)
    return manifest


def validate_source_manifest(manifest: Mapping[str, Any]) -> None:
    license_name = str(manifest.get("license", "")).strip()
    if not license_name or license_name.lower() in PLACEHOLDER_LICENSES:
        raise ValueError("source manifest must record a concrete license or public-data status")
    for field in ("sourceUrl", "licenseUrl"):
        if not str(manifest.get(field, "")).startswith("https://"):
            raise ValueError(f"{field} must be an https URL")
    local_path = str(manifest.get("localPath", ""))
    if not local_path or Path(local_path).is_absolute() or ".." in PurePosixPath(local_path).parts:
        raise ValueError("localPath must be a relative path inside the eval output root")
    files = manifest.get("files", [])
    if not files:
        raise ValueError("source manifest must contain downloaded files")
    for item in files:
        if not str(item.get("sha256", "")).startswith("sha256:"):
            raise ValueError("every downloaded file must have a sha256 hash")
        if "sourceUrl" in item and not str(item["sourceUrl"]).startswith("https://"):
            raise ValueError("downloaded file sourceUrl must use https")


def build_dataset_manifest(
    *,
    dataset_id: str,
    version: int,
    source_ids: Sequence[str],
    record_schema: str,
    output_root: Path,
    dataset_path: Path,
    split_manifest_path: Path,
    rows: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    validate_no_split_leakage(rows)
    split_counts = Counter(str(row["split"]) for row in rows)
    split_groups: dict[str, set[str]] = {}
    for row in rows:
        split_groups.setdefault(str(row["split"]), set()).add(str(row["sourceGroupId"]))
    split_manifest = {
        split: {
            "recordCount": split_counts[split],
            "groupCount": len(groups),
            "groupHash": sha256_json(sorted(groups)),
        }
        for split, groups in sorted(split_groups.items())
    }
    return {
        "schemaVersion": 1,
        "datasetId": dataset_id,
        "version": version,
        "sourceIds": list(source_ids),
        "recordSchema": record_schema,
        "localPath": relative_local_path(dataset_path, output_root),
        "datasetHash": sha256_file(dataset_path),
        "splitManifestPath": relative_local_path(split_manifest_path, output_root),
        "splitManifestHash": sha256_file(split_manifest_path),
        "recordCount": len(rows),
        "groupCount": len({str(row["sourceGroupId"]) for row in rows}),
        "splits": split_manifest,
    }


def build_split_manifest(dataset_id: str, rows: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    validate_no_split_leakage(rows)
    groups: dict[str, set[str]] = {}
    for row in rows:
        groups.setdefault(str(row["split"]), set()).add(str(row["sourceGroupId"]))
    return {
        "schemaVersion": 1,
        "datasetId": dataset_id,
        "splits": {
            split: {
                "groupIds": sorted(group_ids),
                "groupHash": sha256_json(sorted(group_ids)),
            }
            for split, group_ids in sorted(groups.items())
        },
    }


def validate_size_gate(suite: str, mode: str, counts: Mapping[str, int]) -> None:
    gates = {
        ("retrieval", "smoke"): {"queries": 50, "documents": 1000},
        ("retrieval", "full"): {"queries": 1000, "documents": 5000},
        ("memory", "smoke"): {"tasks": 20},
        ("memory", "full"): {"tasks": 200},
        ("multiturn", "smoke"): {"conversations": 20},
        ("multiturn", "full"): {"conversations": 100},
        ("text-recall", "smoke"): {"files": 25},
        ("text-recall", "full"): {"files": 200},
    }
    required = gates[(suite, mode)]
    failures = [f"{name}={counts.get(name, 0)} < {minimum}" for name, minimum in required.items() if counts.get(name, 0) < minimum]
    if failures:
        raise ValueError(f"{suite} {mode} size gate failed: {', '.join(failures)}")
