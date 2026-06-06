"""Deterministic parameter trials and accuracy-first champion selection."""

from __future__ import annotations

import itertools
import random
from collections.abc import Iterable, Mapping, Sequence
from typing import Any

from .parameters import config_fingerprint, validate_parameter_space


def grid_trials(space: Mapping[str, Any], *, experiment_id: str, random_seed: int = 0) -> list[dict[str, Any]]:
    validate_parameter_space(space)
    parameter_ids = [parameter["id"] for parameter in space["parameters"]]
    value_lists = [parameter["values"] for parameter in space["parameters"]]
    return [
        _trial(experiment_id, space["id"], index, random_seed, dict(zip(parameter_ids, values)))
        for index, values in enumerate(itertools.product(*value_lists), start=1)
    ]


def random_trials(
    space: Mapping[str, Any],
    *,
    experiment_id: str,
    budget: int,
    random_seed: int,
) -> list[dict[str, Any]]:
    if budget <= 0:
        raise ValueError("budget must be positive")
    trials = grid_trials(space, experiment_id=experiment_id, random_seed=random_seed)
    random.Random(random_seed).shuffle(trials)
    selected = trials[:budget]
    for index, trial in enumerate(selected, start=1):
        trial["trialId"] = f"trial-{index:04d}"
    return selected


def select_champion(
    trials: Iterable[Mapping[str, Any]],
    *,
    primary_metric: str,
    direction: str,
    secondary_metrics: Sequence[tuple[str, str]] = (),
) -> Mapping[str, Any]:
    if direction not in {"maximize", "minimize"}:
        raise ValueError("direction must be maximize or minimize")
    eligible = [
        trial
        for trial in trials
        if trial.get("status") == "completed"
        and not trial.get("gateFailures")
        and trial.get("metrics", {}).get(primary_metric) is not None
    ]
    if not eligible:
        raise ValueError("no eligible completed trials")

    def sort_key(trial: Mapping[str, Any]) -> tuple[Any, ...]:
        metrics = trial["metrics"]
        values: list[Any] = [_ordered(metrics[primary_metric], direction)]
        values.extend(_ordered(metrics.get(name), metric_direction) for name, metric_direction in secondary_metrics)
        values.extend(
            [
                _optional_number(trial.get("latencyP95Ms")),
                _optional_number(trial.get("costUsd")),
                trial["configFingerprint"],
            ]
        )
        return tuple(values)

    return min(eligible, key=sort_key)


def _ordered(value: float | None, direction: str) -> float:
    if value is None:
        return float("inf")
    return -value if direction == "maximize" else value


def _optional_number(value: float | None) -> float:
    return float("inf") if value is None else value


def _trial(
    experiment_id: str,
    parameter_space_id: str,
    index: int,
    random_seed: int,
    parameters: dict[str, Any],
) -> dict[str, Any]:
    return {
        "experimentId": experiment_id,
        "trialId": f"trial-{index:04d}",
        "parameterSpaceId": parameter_space_id,
        "configFingerprint": config_fingerprint(parameters),
        "randomSeed": random_seed,
        "parameters": parameters,
        "status": "pending",
        "metrics": {},
        "gateFailures": [],
    }
