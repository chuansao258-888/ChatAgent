"""Deterministic retrieval and text-recall metrics."""

from __future__ import annotations

import math
import re
from collections.abc import Iterable, Sequence


def _top_k(values: Sequence[str], k: int) -> Sequence[str]:
    if k <= 0:
        raise ValueError("k must be positive")
    return values[:k]


def hit_at_k(retrieved: Sequence[str], relevant: Iterable[str], k: int) -> float:
    relevant_set = set(relevant)
    return float(any(item in relevant_set for item in _top_k(retrieved, k)))


def recall_at_k(retrieved: Sequence[str], relevant: Iterable[str], k: int) -> float:
    relevant_set = set(relevant)
    if not relevant_set:
        return 0.0
    return len(set(_top_k(retrieved, k)) & relevant_set) / len(relevant_set)


def precision_at_k(retrieved: Sequence[str], relevant: Iterable[str], k: int) -> float:
    relevant_set = set(relevant)
    return len(set(_top_k(retrieved, k)) & relevant_set) / k


def reciprocal_rank(retrieved: Sequence[str], relevant: Iterable[str]) -> float:
    relevant_set = set(relevant)
    for index, item in enumerate(retrieved, start=1):
        if item in relevant_set:
            return 1.0 / index
    return 0.0


def ndcg_at_k(retrieved: Sequence[str], relevant: Iterable[str], k: int) -> float:
    relevant_set = set(relevant)
    if not relevant_set:
        return 0.0
    gains = [1.0 if item in relevant_set else 0.0 for item in _top_k(retrieved, k)]
    dcg = sum(gain / math.log2(index + 2) for index, gain in enumerate(gains))
    ideal_count = min(len(relevant_set), k)
    ideal_dcg = sum(1.0 / math.log2(index + 2) for index in range(ideal_count))
    return dcg / ideal_dcg if ideal_dcg else 0.0


def phrase_recall(texts: Sequence[str], required_phrases: Iterable[str]) -> float:
    phrases = list(required_phrases)
    if not phrases:
        return 0.0
    searchable = _normalize(" ".join(texts))
    found = sum(1 for phrase in phrases if _normalize(phrase) in searchable)
    return found / len(phrases)


def _normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip().casefold()
