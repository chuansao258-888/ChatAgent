"""Load and validate the committed approved-source catalog."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from chatagent_eval.datasets import PLACEHOLDER_LICENSES


def load_catalog(catalog_root: Path, source_id: str) -> dict[str, Any]:
    path = catalog_root / f"{source_id}.json"
    catalog = json.loads(path.read_text(encoding="utf-8"))
    if catalog.get("sourceId") != source_id:
        raise ValueError(f"catalog sourceId does not match file name: {path}")
    if str(catalog.get("license", "")).lower() in PLACEHOLDER_LICENSES:
        raise ValueError(f"catalog uses placeholder license: {path}")
    for field in ("sourceUrl", "licenseUrl"):
        if not str(catalog.get(field, "")).startswith("https://"):
            raise ValueError(f"catalog {field} must use https: {path}")
    return catalog
