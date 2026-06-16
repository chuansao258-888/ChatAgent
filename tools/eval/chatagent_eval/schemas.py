"""Small dependency-free validator for the JSON Schema subset used by Phase 2."""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def validate(instance: Any, schema: Mapping[str, Any], path: str = "$") -> None:
    for nested_schema in schema.get("allOf", []):
        validate(instance, nested_schema, path)
    if_schema = schema.get("if")
    if isinstance(if_schema, Mapping) and _matches_schema(instance, if_schema, path):
        then_schema = schema.get("then")
        if isinstance(then_schema, Mapping):
            validate(instance, then_schema, path)
    _validate_type(instance, schema.get("type"), path)
    if "enum" in schema and instance not in schema["enum"]:
        raise ValueError(f"{path} must be one of {schema['enum']}")
    if isinstance(instance, str) and len(instance) < schema.get("minLength", 0):
        raise ValueError(f"{path} is shorter than minLength")
    if isinstance(instance, list):
        if len(instance) < schema.get("minItems", 0):
            raise ValueError(f"{path} has fewer than minItems")
        if schema.get("uniqueItems") and len({_canonical(item) for item in instance}) != len(instance):
            raise ValueError(f"{path} must contain unique items")
        item_schema = schema.get("items")
        if item_schema:
            for index, item in enumerate(instance):
                validate(item, item_schema, f"{path}[{index}]")
    if isinstance(instance, dict):
        for key in schema.get("required", []):
            if key not in instance:
                raise ValueError(f"{path} missing required field: {key}")
        properties = schema.get("properties", {})
        additional = schema.get("additionalProperties", True)
        for key, value in instance.items():
            if key in properties:
                validate(value, properties[key], f"{path}.{key}")
            elif isinstance(additional, Mapping):
                validate(value, additional, f"{path}.{key}")
            elif additional is False:
                raise ValueError(f"{path} contains unknown field: {key}")


def _validate_type(instance: Any, expected: Any, path: str) -> None:
    if expected is None:
        return
    expected_types = expected if isinstance(expected, list) else [expected]
    if not any(_matches_type(instance, item) for item in expected_types):
        raise ValueError(f"{path} must have type {expected_types}")


def _matches_type(instance: Any, expected: str) -> bool:
    return {
        "null": instance is None,
        "object": isinstance(instance, dict),
        "array": isinstance(instance, list),
        "string": isinstance(instance, str),
        "boolean": isinstance(instance, bool),
        "integer": isinstance(instance, int) and not isinstance(instance, bool),
        "number": isinstance(instance, (int, float)) and not isinstance(instance, bool),
    }.get(expected, True)


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def _matches_schema(instance: Any, schema: Mapping[str, Any], path: str) -> bool:
    try:
        validate(instance, schema, path)
        return True
    except ValueError:
        return False
