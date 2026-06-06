"""Parameter-space validation and reproducible configuration fingerprints."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping, Sequence
from typing import Any

CLASSIFICATIONS = {
    "quality-tunable",
    "operational-only",
    "safety-fixed",
    "excluded-with-rationale",
}
TYPES = {"integer", "number", "string", "boolean"}
PROMOTION_STATUSES = {"current", "candidate", "deferred", "not-applicable"}


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


def validate_parameter_registry(registry: Mapping[str, Any]) -> None:
    for key in ("schemaVersion", "registryId", "parameters"):
        if key not in registry:
            raise ValueError(f"parameter registry missing required field: {key}")
    parameters = registry["parameters"]
    if not isinstance(parameters, list) or not parameters:
        raise ValueError("parameter registry must contain parameters")

    seen: set[str] = set()
    for parameter in parameters:
        parameter_id = parameter.get("id")
        if not parameter_id or parameter_id in seen:
            raise ValueError(f"registry parameter ids must be non-empty and unique: {parameter_id}")
        seen.add(parameter_id)
        for key in ("owner", "source", "classification", "type", "default", "suites", "metrics", "promotionStatus", "rationale"):
            if key not in parameter:
                raise ValueError(f"registry parameter {parameter_id} missing required field: {key}")
        if parameter["classification"] not in CLASSIFICATIONS:
            raise ValueError(f"invalid registry classification for {parameter_id}: {parameter['classification']}")
        if parameter["type"] not in TYPES:
            raise ValueError(f"invalid registry type for {parameter_id}: {parameter['type']}")
        if not _matches_type(parameter["default"], parameter["type"]):
            raise ValueError(f"registry parameter {parameter_id} has invalid default type")
        if parameter["promotionStatus"] not in PROMOTION_STATUSES:
            raise ValueError(f"invalid promotion status for {parameter_id}: {parameter['promotionStatus']}")
        if not isinstance(parameter["suites"], list) or not isinstance(parameter["metrics"], list):
            raise ValueError(f"registry parameter {parameter_id} suites and metrics must be arrays")
        if parameter["classification"] == "quality-tunable":
            if not parameter.get("parameterSpaceId") or not parameter.get("spaceParameterId"):
                raise ValueError(f"quality-tunable registry parameter {parameter_id} must reference a parameter space")
        if parameter["classification"] != "quality-tunable" and not str(parameter["rationale"]).strip():
            raise ValueError(f"non-quality registry parameter {parameter_id} requires rationale")


def validate_registry_coverage(registry: Mapping[str, Any], spaces: Sequence[Mapping[str, Any]]) -> None:
    validate_parameter_registry(registry)
    registry_pairs = {
        (str(parameter.get("parameterSpaceId")), str(parameter.get("spaceParameterId"))): parameter
        for parameter in registry["parameters"]
        if parameter.get("parameterSpaceId") and parameter.get("spaceParameterId")
    }
    for space in spaces:
        validate_parameter_space(space)
        for parameter in space["parameters"]:
            key = (str(space["id"]), str(parameter["id"]))
            registered = registry_pairs.get(key)
            if registered is None:
                raise ValueError(f"parameter space value is missing from registry: {space['id']}.{parameter['id']}")
            if registered["classification"] != parameter["classification"]:
                raise ValueError(f"classification mismatch for {space['id']}.{parameter['id']}")


def validate_tuning_policy(
    policy: Mapping[str, Any],
    space: Mapping[str, Any],
    registry: Mapping[str, Any],
    suite: str,
) -> None:
    for key in ("id", "suite", "parameterSpaceId", "primaryMetric", "direction", "baselineParameters", "hardGates"):
        if key not in policy:
            raise ValueError(f"tuning policy missing required field: {key}")
    if policy["suite"] != suite:
        raise ValueError("tuning policy suite mismatch")
    if policy["parameterSpaceId"] != space["id"]:
        raise ValueError("tuning policy parameter space mismatch")
    if policy["primaryMetric"] != space["primaryMetric"] or policy["direction"] != space["direction"]:
        raise ValueError("tuning policy primary objective mismatch")
    registered_defaults = {
        str(parameter["spaceParameterId"]): parameter["default"]
        for parameter in registry["parameters"]
        if parameter.get("parameterSpaceId") == space["id"]
    }
    for parameter_id, value in policy["baselineParameters"].items():
        if registered_defaults.get(parameter_id) != value:
            raise ValueError(f"tuning policy baseline differs from registry default: {parameter_id}")


def _matches_type(value: Any, parameter_type: str) -> bool:
    if parameter_type == "boolean":
        return isinstance(value, bool)
    if parameter_type == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if parameter_type == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    return isinstance(value, str)
