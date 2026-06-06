"""Deterministic parameter trials and accuracy-first champion selection."""

from __future__ import annotations

import itertools
import math
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


def build_experiment_trials(
    space: Mapping[str, Any],
    *,
    experiment_id: str,
    baseline_parameters: Mapping[str, Any],
    strategy: str,
    combination_budget: int,
    random_seed: int,
) -> list[dict[str, Any]]:
    validate_parameter_space(space)
    if strategy not in {"grid", "random"}:
        raise ValueError("strategy must be grid or random")
    if combination_budget <= 0:
        raise ValueError("combination_budget must be positive")
    _validate_baseline(space, baseline_parameters)

    candidates: list[tuple[str, dict[str, Any]]] = [("baseline", dict(baseline_parameters))]
    for parameter in space["parameters"]:
        for value in parameter["values"]:
            if value == baseline_parameters[parameter["id"]]:
                continue
            sensitivity = dict(baseline_parameters)
            sensitivity[parameter["id"]] = value
            candidates.append(("sensitivity", sensitivity))

    combinations = (
        grid_trials(space, experiment_id=experiment_id, random_seed=random_seed)
        if strategy == "grid"
        else random_trials(
            space,
            experiment_id=experiment_id,
            budget=combination_budget,
            random_seed=random_seed,
        )
    )
    candidates.extend(("combination", dict(trial["parameters"])) for trial in combinations[:combination_budget])

    trials: list[dict[str, Any]] = []
    seen: set[str] = set()
    for stage, parameters in candidates:
        fingerprint = config_fingerprint(parameters)
        if fingerprint in seen:
            continue
        seen.add(fingerprint)
        trials.append(
            {
                "experimentId": experiment_id,
                "trialId": f"trial-{len(trials) + 1:04d}",
                "parameterSpaceId": space["id"],
                "configFingerprint": fingerprint,
                "randomSeed": random_seed,
                "parameters": parameters,
                "stage": stage,
                "status": "pending",
                "metrics": {},
                "gateFailures": [],
            }
        )
    return trials


def select_champion(
    trials: Iterable[Mapping[str, Any]],
    *,
    primary_metric: str,
    direction: str,
    secondary_metrics: Sequence[tuple[str, str]] = (),
    baseline_parameters: Mapping[str, Any] | None = None,
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
                _baseline_deviation_count(trial.get("parameters", {}), baseline_parameters),
                _optional_number(trial.get("latencyP95Ms")),
                _optional_number(trial.get("costUsd")),
                trial["configFingerprint"],
            ]
        )
        return tuple(values)

    return min(eligible, key=sort_key)


def bootstrap_confidence_interval(
    values: Sequence[float],
    *,
    random_seed: int,
    resamples: int = 500,
    confidence: float = 0.95,
) -> dict[str, float | int]:
    if not values:
        raise ValueError("bootstrap confidence interval requires values")
    if resamples <= 0:
        raise ValueError("resamples must be positive")
    if confidence <= 0.0 or confidence >= 1.0:
        raise ValueError("confidence must be between zero and one")
    rng = random.Random(random_seed)
    means = sorted(sum(rng.choice(values) for _ in values) / len(values) for _ in range(resamples))
    tail = (1.0 - confidence) / 2.0
    lower_index = min(len(means) - 1, max(0, math.floor(tail * len(means))))
    upper_index = min(len(means) - 1, max(0, math.ceil((1.0 - tail) * len(means)) - 1))
    mean = sum(values) / len(values)
    variance = sum((value - mean) ** 2 for value in values) / len(values)
    return {
        "confidence": confidence,
        "resamples": resamples,
        "mean": mean,
        "lower": means[lower_index],
        "upper": means[upper_index],
        "variance": variance,
    }


def pareto_frontier(
    trials: Iterable[Mapping[str, Any]],
    *,
    objectives: Sequence[tuple[str, str]],
) -> list[Mapping[str, Any]]:
    eligible = [trial for trial in trials if trial.get("status") == "completed" and not trial.get("gateFailures")]
    frontier: list[Mapping[str, Any]] = []
    for candidate in eligible:
        if not any(_dominates(other, candidate, objectives) for other in eligible if other is not candidate):
            frontier.append(candidate)
    return sorted(frontier, key=lambda trial: str(trial["configFingerprint"]))


def category_regression_failures(
    baseline: Mapping[str, float],
    candidate: Mapping[str, float],
    *,
    tolerance: float,
) -> list[str]:
    if tolerance < 0:
        raise ValueError("category regression tolerance must be non-negative")
    failures: list[str] = []
    for category, baseline_value in baseline.items():
        candidate_value = candidate.get(category)
        if candidate_value is None or candidate_value < baseline_value - tolerance:
            failures.append(f"category-regression:{category}")
    return failures


def _ordered(value: float | None, direction: str) -> float:
    if value is None:
        return float("inf")
    return -value if direction == "maximize" else value


def _optional_number(value: float | None) -> float:
    return float("inf") if value is None else value


def _baseline_deviation_count(
    parameters: Mapping[str, Any],
    baseline_parameters: Mapping[str, Any] | None,
) -> int:
    if baseline_parameters is None:
        return 0
    return sum(parameters.get(key) != value for key, value in baseline_parameters.items())


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


def _validate_baseline(space: Mapping[str, Any], baseline: Mapping[str, Any]) -> None:
    expected_ids = {str(parameter["id"]) for parameter in space["parameters"]}
    if set(baseline) != expected_ids:
        raise ValueError("baseline parameters must exactly match parameter space ids")
    for parameter in space["parameters"]:
        if baseline[parameter["id"]] not in parameter["values"]:
            raise ValueError(f"baseline value is outside parameter space: {parameter['id']}")


def _dominates(
    left: Mapping[str, Any],
    right: Mapping[str, Any],
    objectives: Sequence[tuple[str, str]],
) -> bool:
    left_values: list[float] = []
    right_values: list[float] = []
    for metric, direction in objectives:
        left_value = _objective_value(left, metric)
        right_value = _objective_value(right, metric)
        if left_value is None or right_value is None:
            return False
        left_values.append(-left_value if direction == "maximize" else left_value)
        right_values.append(-right_value if direction == "maximize" else right_value)
    return all(left <= right for left, right in zip(left_values, right_values)) and any(
        left < right for left, right in zip(left_values, right_values)
    )


def _objective_value(trial: Mapping[str, Any], metric: str) -> float | None:
    if metric == "latencyP95Ms":
        value = trial.get("latencyP95Ms")
    elif metric == "costUsd":
        value = trial.get("costUsd")
    else:
        value = trial.get("metrics", {}).get(metric)
    return float(value) if value is not None else None
