"""Network and archive helpers with cache, hash, and path-boundary checks."""

from __future__ import annotations

import hashlib
import shutil
import urllib.request
import zipfile
from pathlib import Path


def download_file(
    url: str,
    destination: Path,
    *,
    expected_sha256: str | None = None,
    user_agent: str = "ChatAgent-eval/1.0",
) -> Path:
    if not url.startswith("https://"):
        raise ValueError("eval sources must use https")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and (expected_sha256 is None or _sha256(destination) == _normalize_hash(expected_sha256)):
        return destination

    partial = destination.with_suffix(destination.suffix + ".part")
    headers = {"User-Agent": user_agent, "Accept-Encoding": "identity"}
    resume_at = partial.stat().st_size if partial.exists() else 0
    if resume_at:
        headers["Range"] = f"bytes={resume_at}-"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=120) as response:
        append = resume_at > 0 and getattr(response, "status", None) == 206
        with partial.open("ab" if append else "wb") as target:
            shutil.copyfileobj(response, target)

    actual_sha256 = _sha256(partial)
    if expected_sha256 is not None and actual_sha256 != _normalize_hash(expected_sha256):
        partial.unlink(missing_ok=True)
        raise ValueError(f"sha256 mismatch for {url}: {actual_sha256}")
    partial.replace(destination)
    return destination


def safe_extract_zip(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    resolved_destination = destination.resolve()
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            target = (resolved_destination / entry.filename).resolve()
            if not target.is_relative_to(resolved_destination):
                raise ValueError(f"zip entry escapes extraction root: {entry.filename}")
        source.extractall(destination)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _normalize_hash(value: str) -> str:
    return value.lower().removeprefix("sha256:")
