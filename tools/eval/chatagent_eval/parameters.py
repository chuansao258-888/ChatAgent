"""Parameter-space validation and reproducible configuration fingerprints."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping
from typing import Any

CLASSIFICATIONS = {
    "quality-tunable",
    "operational-only",
    "safety-fixed",
    "excluded-with-rationale",
}
TYPES = {"integer", "number", "string", "boolean"}


def config_fingerprint(config: Mapping[str, Any]) -> str:
    canonical = json.dumps(config, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def validate_parameter_space(space: Mapping[str, Any]) -> None:
    for key in ("id", "version", "primaryMetric", "direction", "parameters"):
        if key not in space:
            raise ValueError(f"parameter space missing required field: {key}")
    if space["direction"] not in {"maximize", "minimize"}:
        raise ValueError("direction must be maximize or minimize")
    parameters = space["parameters"]
    if not isinstance(parameters, list) or not parameters:
        raise ValueError("parameters must be a non-empty list")

    seen: set[str] = set()
    for parameter in parameters:
        parameter_id = parameter.get("id")
        if not parameter_id or parameter_id in seen:
            raise ValueError(f"parameter ids must be non-empty and unique: {parameter_id}")
        seen.add(parameter_id)
        classification = parameter.get("classification")
        if classification not in CLASSIFICATIONS:
            raise ValueError(f"invalid classification for {parameter_id}: {classification}")
        parameter_type = parameter.get("type")
        if parameter_type not in TYPES:
            raise ValueError(f"invalid type for {parameter_id}: {parameter_type}")
        values = parameter.get("values")
        if not isinstance(values, list) or not values:
            raise ValueError(f"parameter {parameter_id} must define at least one value")
        for value in values:
            if not _matches_type(value, parameter_type):
                raise ValueError(f"parameter {parameter_id} contains invalid {parameter_type} value: {value!r}")
        if classification == "excluded-with-rationale" and not parameter.get("rationale"):
            raise ValueError(f"excluded parameter {parameter_id} requires a rationale")


def _matches_type(value: Any, parameter_type: str) -> bool:
    if parameter_type == "boolean":
        return isinstance(value, bool)
    if parameter_type == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if parameter_type == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    return isinstance(value, str)
