"""Legacy 10d-B2 control-delta, token/latency, and promotion-gate analysis.

The active 2026-06-16 B3.4 closeout cancelled the old control matrix. Keep this
module only for explicitly requested historical/diagnostic reruns; it is not
part of the default one-run full-rag RAGAS path. It works entirely from fixture
data and does not require live providers or a Ragas runtime.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from statistics import mean, quantiles
from typing import Any

# ── Control-mode labels ─────────────────────────────────────────────────────

NO_RAG = "no-rag"
FULL_RAG = "full-rag"
WRONG_CONTEXT = "wrong-context"
ORACLE_CONTEXT = "oracle-context"
RETRIEVAL_ONLY = "retrieval-only"
RERANKER_OFF = "reranker-off"
RERANKER_ON = "reranker-on"

ALL_CONTROLS = (NO_RAG, FULL_RAG, WRONG_CONTEXT, ORACLE_CONTEXT, RETRIEVAL_ONLY, RERANKER_OFF, RERANKER_ON)

# ── Promotion-gate configuration ────────────────────────────────────────────

BASELINE_CONTEXT_RECALL = 0.6294
BASELINE_PHRASE_RECALL = 0.8552

GATE_PRIMARY_RECALL_TARGET = 0.70
GATE_PRIMARY_RECALL_DELTA = 0.08
GATE_PER_FORMAT_REGRESSION = 0.03
GATE_CITATION_SUPPORT = 0.80
GATE_FULL_RAG_FAITHFULNESS = 0.80
GATE_NEGATIVE_CONTROL_DEGRADATION = 0.05

RAGAS_ANSWER_METRICS = (
    "faithfulness",
    "response_relevancy",
    "context_precision",
    "context_recall",
    "factual_correctness",
    "semantic_similarity",
)
RETRIEVAL_METRICS = (
    "contextRecallAtK",
    "hitAtK",
    "mrr",
    "phraseRecall",
)
REQUIRED_GATE_POLICY_KEYS = (
    "baselineContextRecall",
    "baselinePhraseRecall",
    "primaryRecallTarget",
    "primaryRecallDelta",
    "perFormatRegressionTolerance",
    "minCitationSupport",
    "minFullRagFaithfulness",
    "minNegativeControlDegradation",
    "maxLatencyP95Ms",
    "maxCostUsd",
)


@dataclass(frozen=True)
class ControlDelta:
    """Difference between two control modes for a single metric."""

    metric: str
    baseline_mode: str
    comparison_mode: str
    baseline_value: float | None
    comparison_value: float | None
    delta: float | None
    direction: str  # "improved", "degraded", "unchanged", "incomparable"


@dataclass(frozen=True)
class TokenSummary:
    """Aggregated token counts across rows."""

    total_prompt: int
    total_completion: int
    total_tokens: int
    total_context_tokens: int
    mean_prompt: float
    mean_completion: float
    mean_tokens: float
    mean_context_tokens: float
    row_count: int
    by_control: Mapping[str, dict[str, float]]


@dataclass(frozen=True)
class LatencySummary:
    """Aggregated latency statistics across rows."""

    mean_retrieval_ms: float | None
    mean_reranker_ms: float | None
    mean_answer_ms: float | None
    mean_total_ms: float | None
    p95_total_ms: float | None
    row_count: int
    by_control: Mapping[str, dict[str, float | None]]


@dataclass(frozen=True)
class GateResult:
    """Result of evaluating one promotion gate."""

    gate: str
    passed: bool
    status: str  # "pass", "fail", "warn", "skip"
    detail: Mapping[str, Any]


def require_10d_b2_gate_policy(value: Any) -> dict[str, float]:
    if not isinstance(value, Mapping):
        raise ValueError("candidate missing frozen 10d-B2 gate policy")
    missing = [
        key
        for key in REQUIRED_GATE_POLICY_KEYS
        if not isinstance(value.get(key), (int, float)) or isinstance(value.get(key), bool)
    ]
    if missing:
        raise ValueError(f"candidate gate policy missing numeric values: {missing}")
    policy = {key: float(value[key]) for key in REQUIRED_GATE_POLICY_KEYS}
    fixed = {
        "primaryRecallTarget": GATE_PRIMARY_RECALL_TARGET,
        "primaryRecallDelta": GATE_PRIMARY_RECALL_DELTA,
        "perFormatRegressionTolerance": GATE_PER_FORMAT_REGRESSION,
    }
    mismatched = [key for key, expected in fixed.items() if policy[key] != expected]
    if mismatched:
        raise ValueError(f"candidate gate policy changes fixed implementation gates: {mismatched}")
    return policy


# ── Row helpers ─────────────────────────────────────────────────────────────


def _control_mode(row: Mapping[str, Any]) -> str | None:
    """Extract the control-run mode from a row, handling both schema shapes."""
    control = row.get("controlRun") or (row.get("metadata") or {}).get("controlRun")
    if isinstance(control, Mapping):
        return str(control.get("mode") or "")
    return None


def _rows_by_control(rows: Sequence[Mapping[str, Any]]) -> dict[str, list[Mapping[str, Any]]]:
    grouped: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for row in rows:
        mode = _control_mode(row) or "unknown"
        grouped[mode].append(row)
    return dict(grouped)


def _source_sample_id(row: Mapping[str, Any]) -> str:
    metadata = row.get("metadata") or {}
    explicit = metadata.get("sourceSampleId") if isinstance(metadata, Mapping) else None
    if explicit:
        return str(explicit)
    sample_id = str(row.get("sampleId") or "")
    mode = _control_mode(row)
    suffix = f"-{mode}" if mode else ""
    return sample_id[:-len(suffix)] if suffix and sample_id.endswith(suffix) else sample_id


def _metric_value(row: Mapping[str, Any], metric: str) -> float | None:
    """Extract a metric value from a row, checking docIngestion and ragas namespaces."""
    meta = row.get("metadata") or {}
    scores = meta.get("ragasScores") or meta.get("docIngestion") or {}
    if isinstance(scores, Mapping) and metric in scores:
        value = scores[metric]
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
    # Flat metadata keys
    direct = meta.get(metric)
    if isinstance(direct, (int, float)) and not isinstance(direct, bool):
        return float(direct)
    return None


def _mean_or_none(values: Sequence[float | None]) -> float | None:
    numeric = [v for v in values if v is not None]
    return float(mean(numeric)) if numeric else None


def _is_improvement(metric: str, delta: float) -> bool:
    """Return True when a positive delta means improvement for this metric."""
    return True  # All 10d-B2 metrics are higher-is-better


# ── Control deltas ──────────────────────────────────────────────────────────


def compute_control_deltas(
    rows: Sequence[Mapping[str, Any]],
    metrics: Sequence[str] = RAGAS_ANSWER_METRICS,
    baseline_mode: str = NO_RAG,
    comparison_mode: str = FULL_RAG,
) -> list[ControlDelta]:
    """Compute per-metric deltas between two control modes.

    Returns one ControlDelta per metric. When either mode has no rows
    the delta is ``None`` and direction is ``"incomparable"``.
    """
    by_control = _rows_by_control(rows)
    baseline_rows = {_source_sample_id(row): row for row in by_control.get(baseline_mode, [])}
    comparison_rows = {_source_sample_id(row): row for row in by_control.get(comparison_mode, [])}
    paired_ids = sorted(set(baseline_rows) & set(comparison_rows))
    deltas: list[ControlDelta] = []
    for metric in metrics:
        baseline_values = [_metric_value(baseline_rows[sample_id], metric) for sample_id in paired_ids]
        comparison_values = [_metric_value(comparison_rows[sample_id], metric) for sample_id in paired_ids]
        baseline_mean = _mean_or_none(baseline_values)
        comparison_mean = _mean_or_none(comparison_values)
        delta = (comparison_mean - baseline_mean) if (baseline_mean is not None and comparison_mean is not None) else None
        direction = "incomparable"
        if delta is not None:
            direction = "improved" if delta > 0 else ("degraded" if delta < 0 else "unchanged")
        deltas.append(
            ControlDelta(
                metric=metric,
                baseline_mode=baseline_mode,
                comparison_mode=comparison_mode,
                baseline_value=baseline_mean,
                comparison_value=comparison_mean,
                delta=delta,
                direction=direction,
            )
        )
    return deltas


def control_delta_matrix(
    rows: Sequence[Mapping[str, Any]],
    metrics: Sequence[str] = RAGAS_ANSWER_METRICS,
    mode_pairs: Sequence[tuple[str, str]] | None = None,
) -> dict[tuple[str, str], list[ControlDelta]]:
    """Compute deltas for multiple mode pairs at once.

    Default pairs cover the 10d-B2 control matrix:
    (full-rag, no-rag), (wrong-context, full-rag), (oracle-context, full-rag).
    """
    if mode_pairs is None:
        mode_pairs = (
            (FULL_RAG, NO_RAG),
            (WRONG_CONTEXT, FULL_RAG),
            (ORACLE_CONTEXT, FULL_RAG),
        )
    result: dict[tuple[str, str], list[ControlDelta]] = {}
    for comparison, baseline in mode_pairs:
        result[(comparison, baseline)] = compute_control_deltas(
            rows, metrics=metrics, baseline_mode=baseline, comparison_mode=comparison
        )
    return result


# ── Token summaries ─────────────────────────────────────────────────────────


def compute_token_summary(rows: Sequence[Mapping[str, Any]]) -> TokenSummary:
    """Aggregate token counts from per-row tokenCounts metadata."""
    prompt: list[int] = []
    completion: list[int] = []
    total: list[int] = []
    context: list[int] = []
    by_control_raw: dict[str, dict[str, list[int]]] = defaultdict(lambda: defaultdict(list))

    for row in rows:
        tc = (row.get("tokenCounts") or (row.get("metadata") or {}).get("tokenCounts") or {})
        if not isinstance(tc, Mapping):
            continue
        mode = _control_mode(row) or "unknown"
        for key, store in (("promptTokens", prompt), ("completionTokens", completion),
                           ("totalTokens", total), ("retrievedContextTokens", context)):
            value = tc.get(key)
            if isinstance(value, int) and value >= 0:
                store.append(value)
                by_control_raw[mode][key].append(value)

    by_control: dict[str, dict[str, float]] = {}
    for mode, counts in by_control_raw.items():
        by_control[mode] = {
            f"mean{key[0].upper() + key[1:]}": float(mean(counts[key])) if counts[key] else 0.0
            for key in ("promptTokens", "completionTokens", "totalTokens", "retrievedContextTokens")
            if counts.get(key)
        }

    return TokenSummary(
        total_prompt=sum(prompt),
        total_completion=sum(completion),
        total_tokens=sum(total),
        total_context_tokens=sum(context),
        mean_prompt=float(mean(prompt)) if prompt else 0.0,
        mean_completion=float(mean(completion)) if completion else 0.0,
        mean_tokens=float(mean(total)) if total else 0.0,
        mean_context_tokens=float(mean(context)) if context else 0.0,
        row_count=len(rows),
        by_control=by_control,
    )


# ── Latency summaries ───────────────────────────────────────────────────────


def compute_latency_summary(rows: Sequence[Mapping[str, Any]], mode: str | None = None) -> LatencySummary:
    """Aggregate latency statistics from per-row latency metadata."""
    retrieval: list[float] = []
    reranker: list[float] = []
    answer: list[float] = []
    total: list[float] = []
    by_control_raw: dict[str, dict[str, list[float]]] = defaultdict(lambda: defaultdict(list))

    selected_rows = [row for row in rows if mode is None or _control_mode(row) == mode]
    for row in selected_rows:
        lat = (row.get("latency") or (row.get("metadata") or {}).get("latency") or {})
        if not isinstance(lat, Mapping):
            continue
        mode = _control_mode(row) or "unknown"
        for key, store in (("retrievalMs", retrieval), ("rerankerMs", reranker),
                           ("answerMs", answer), ("totalMs", total)):
            value = lat.get(key)
            if isinstance(value, (int, float)) and value >= 0 and not isinstance(value, bool):
                store.append(float(value))
                by_control_raw[mode][key].append(float(value))

    def _p95(values: list[float]) -> float | None:
        if len(values) < 2:
            return None
        try:
            qs = quantiles(values, n=20)
            return float(qs[-1])  # 95th percentile in 20-quantile = last element
        except (ValueError, TypeError):
            return None

    by_control: dict[str, dict[str, float | None]] = {}
    for mode, latencies in by_control_raw.items():
        entry: dict[str, float | None] = {}
        for key in ("retrievalMs", "rerankerMs", "answerMs", "totalMs"):
            vals = latencies.get(key, [])
            entry[f"mean{key[0].upper() + key[1:]}"] = float(mean(vals)) if vals else None
        entry["p95TotalMs"] = _p95(latencies.get("totalMs", []))
        by_control[mode] = entry

    return LatencySummary(
        mean_retrieval_ms=float(mean(retrieval)) if retrieval else None,
        mean_reranker_ms=float(mean(reranker)) if reranker else None,
        mean_answer_ms=float(mean(answer)) if answer else None,
        mean_total_ms=float(mean(total)) if total else None,
        p95_total_ms=_p95(total),
        row_count=len(selected_rows),
        by_control=by_control,
    )


# ── Citation support ────────────────────────────────────────────────────────


def compute_citation_support_recall(rows: Sequence[Mapping[str, Any]], mode: str = FULL_RAG) -> float | None:
    """Return the fraction of rows whose citations support a reference chunk.

    A row is counted as supported when at least one citation is marked supported
    and points at one of the row's referenceContextIds. Rows without the target
    control mode are ignored.
    """
    target_rows = [row for row in rows if _control_mode(row) == mode]
    if not target_rows:
        return None
    supported_rows = 0
    for row in target_rows:
        reference_ids = {str(item) for item in row.get("referenceContextIds") or [] if str(item)}
        citations = (row.get("citations") or (row.get("metadata") or {}).get("citations") or [])
        if not reference_ids or not isinstance(citations, Sequence):
            continue
        if any(_citation_supports_reference(citation, reference_ids) for citation in citations if isinstance(citation, Mapping)):
            supported_rows += 1
    return supported_rows / len(target_rows)


def _citation_supports_reference(citation: Mapping[str, Any], reference_ids: set[str]) -> bool:
    if citation.get("supported") is not True:
        return False
    source_chunk_id = str(citation.get("sourceChunkId") or "")
    return source_chunk_id in reference_ids


# ── Promotion gates ─────────────────────────────────────────────────────────


def evaluate_10d_b2_gates(
    *,
    context_recall: float | None,
    phrase_recall: float | None,
    control_deltas: Sequence[ControlDelta],
    per_format_metrics: Mapping[str, Mapping[str, float]] | None = None,
    baseline_per_format: Mapping[str, Mapping[str, float]] | None = None,
    citation_support_recall: float | None = None,
    min_citation_support: float = GATE_CITATION_SUPPORT,
    min_full_rag_faithfulness: float = GATE_FULL_RAG_FAITHFULNESS,
    min_negative_control_degradation: float = GATE_NEGATIVE_CONTROL_DEGRADATION,
    baseline_context_recall: float = BASELINE_CONTEXT_RECALL,
    baseline_phrase_recall: float = BASELINE_PHRASE_RECALL,
    latency_p95_ms: float | None = None,
    cost_usd: float | None = None,
    max_latency_p95_ms: float | None = None,
    max_cost_usd: float | None = None,
    pre_rerank_mrr: float | None = None,
    post_rerank_mrr: float | None = None,
) -> list[GateResult]:
    """Evaluate the 10d-B2 promotion gates against the sealed holdout results.

    Returns one GateResult per gate. Gates are evaluated in order:
    primary recall, RAG effectiveness, negative control, oracle-context report,
    per-format guardrails, latency/cost.
    """
    gates: list[GateResult] = []

    # ── Primary recall gate ────────────────────────────────────────────
    target = max(baseline_context_recall + GATE_PRIMARY_RECALL_DELTA, GATE_PRIMARY_RECALL_TARGET)
    if context_recall is not None:
        passed = context_recall >= target
        gates.append(
            GateResult(
                gate="primary.contextRecall",
                passed=passed,
                status="pass" if passed else "fail",
                detail={
                    "value": context_recall,
                    "target": target,
                    "baseline": baseline_context_recall,
                    "delta": context_recall - baseline_context_recall,
                },
            )
        )
    else:
        gates.append(
            GateResult(
                gate="primary.contextRecall",
                passed=False,
                status="skip",
                detail={"reason": "contextRecall value not available"},
            )
        )

    # ── RAG effectiveness gate ─────────────────────────────────────────
    rag_deltas = {d.metric: d for d in control_deltas if d.baseline_mode == NO_RAG and d.comparison_mode == FULL_RAG}
    fc_delta = rag_deltas.get("factual_correctness")
    ss_delta = rag_deltas.get("semantic_similarity")
    rr_delta = rag_deltas.get("response_relevancy")
    ff_delta = rag_deltas.get("faithfulness")

    fc_improved = fc_delta is not None and fc_delta.direction == "improved"
    ss_improved = ss_delta is not None and ss_delta.direction == "improved"
    rr_ok = rr_delta is not None and rr_delta.direction in {"improved", "unchanged"}
    full_rag_faithfulness = ff_delta.comparison_value if ff_delta is not None else None
    ff_ok = full_rag_faithfulness is not None and full_rag_faithfulness >= min_full_rag_faithfulness

    rag_passed = (fc_improved or ss_improved) and rr_ok and ff_ok
    rag_detail: dict[str, Any] = {
        "factualCorrectness": {
            "delta": fc_delta.delta if fc_delta else None,
            "direction": fc_delta.direction if fc_delta else "incomparable",
        },
        "semanticSimilarity": {
            "delta": ss_delta.delta if ss_delta else None,
            "direction": ss_delta.direction if ss_delta else "incomparable",
        },
        "responseRelevancy": {
            "delta": rr_delta.delta if rr_delta else None,
            "direction": rr_delta.direction if rr_delta else "incomparable",
        },
        "faithfulness": {
            "fullRagValue": full_rag_faithfulness,
            "min": min_full_rag_faithfulness,
        },
    }

    missing_required = []
    if not any(delta is not None and delta.direction != "incomparable" for delta in (fc_delta, ss_delta)):
        missing_required.append("factual_correctness_or_semantic_similarity")
    if rr_delta is None or rr_delta.direction == "incomparable":
        missing_required.append("response_relevancy")
    if full_rag_faithfulness is None:
        missing_required.append("faithfulness")
    if missing_required:
        rag_passed = False
        rag_detail["missingRequiredMetrics"] = missing_required

    gates.append(
        GateResult(
            gate="ragEffectiveness.fullRagVsNoRag",
            passed=rag_passed,
            status="pass" if rag_passed else "fail",
            detail=rag_detail,
        )
    )

    # ── Negative-control gate ──────────────────────────────────────────
    neg_deltas = {
        d.metric: d for d in control_deltas if d.baseline_mode == FULL_RAG and d.comparison_mode == WRONG_CONTEXT
    }
    neg_ff = neg_deltas.get("faithfulness")
    neg_fc = neg_deltas.get("factual_correctness")

    neg_worse = (
        neg_ff is not None
        and neg_ff.delta is not None
        and neg_ff.delta <= -min_negative_control_degradation
    ) or (
        neg_fc is not None
        and neg_fc.delta is not None
        and neg_fc.delta <= -min_negative_control_degradation
    )
    neg_detail: dict[str, Any] = {
        "faithfulness": {
            "delta": neg_ff.delta if neg_ff else None,
            "direction": neg_ff.direction if neg_ff else "incomparable",
        },
        "factualCorrectness": {
            "delta": neg_fc.delta if neg_fc else None,
            "direction": neg_fc.direction if neg_fc else "incomparable",
        },
        "minDegradation": min_negative_control_degradation,
    }

    if neg_ff is None and neg_fc is None:
        neg_worse = False
        neg_detail["reason"] = "no negative-control rows available for comparison"

    neg_passed = neg_worse
    gates.append(
        GateResult(
            gate="negativeControl.wrongContextDegrades",
            passed=neg_passed,
            status="pass" if neg_passed else ("fail" if neg_ff is not None or neg_fc is not None else "skip"),
            detail=neg_detail,
        )
    )

    # ── Oracle-context report gate ─────────────────────────────────────
    oracle_deltas = {
        d.metric: d for d in control_deltas if d.baseline_mode == FULL_RAG and d.comparison_mode == ORACLE_CONTEXT
    }
    oracle_fc = oracle_deltas.get("factual_correctness")
    oracle_ss = oracle_deltas.get("semantic_similarity")

    oracle_strong = (oracle_fc is not None and oracle_fc.direction == "improved") or \
                    (oracle_ss is not None and oracle_ss.direction == "improved")
    oracle_detail: dict[str, Any] = {
        "factualCorrectness": {
            "delta": oracle_fc.delta if oracle_fc else None,
            "direction": oracle_fc.direction if oracle_fc else "incomparable",
        },
        "semanticSimilarity": {
            "delta": oracle_ss.delta if oracle_ss else None,
            "direction": oracle_ss.direction if oracle_ss else "incomparable",
        },
        "bottleneck": "retrieval/reranking/chunking" if oracle_strong else "answer prompt, model, or judge/reference quality",
    }

    gates.append(
        GateResult(
            gate="oracleContext.reportedUpperBound",
            passed=True,  # Oracle-context is a documented report, not a hard gate
            status="pass",
            detail=oracle_detail,
        )
    )

    # ── Phrase-recall guardrail ────────────────────────────────────────
    if phrase_recall is not None:
        pr_passed = phrase_recall >= baseline_phrase_recall
        gates.append(
            GateResult(
                gate="guardrail.phraseRecall",
                passed=pr_passed,
                status="pass" if pr_passed else "fail",
                detail={
                    "value": phrase_recall,
                    "baseline": baseline_phrase_recall,
                    "delta": phrase_recall - baseline_phrase_recall,
                },
            )
        )
    else:
        gates.append(
            GateResult(
                gate="guardrail.phraseRecall",
                passed=False,
                status="skip",
                detail={"reason": "phraseRecall value not available"},
            )
        )

    # ── Per-format regression guardrail ────────────────────────────────
    regressions: list[dict[str, Any]] = []
    missing_formats: list[str] = []
    if per_format_metrics and baseline_per_format:
        missing_formats = sorted(set(baseline_per_format) - set(per_format_metrics))
        for fmt, metrics in per_format_metrics.items():
            baseline = baseline_per_format.get(fmt)
            if baseline is None:
                continue
            for metric_key in ("contextRecallAtK",):
                current = metrics.get(metric_key)
                base = baseline.get(metric_key)
                if current is not None and base is not None:
                    regression = base - current
                    if regression > GATE_PER_FORMAT_REGRESSION:
                        regressions.append(
                            {
                                "format": fmt,
                                "metric": metric_key,
                                "current": current,
                                "baseline": base,
                                "regression": regression,
                                "tolerance": GATE_PER_FORMAT_REGRESSION,
                            }
                        )
    fmt_passed = bool(per_format_metrics) and bool(baseline_per_format) and not regressions and not missing_formats
    gates.append(
        GateResult(
            gate="guardrail.perFormatRegression",
            passed=fmt_passed,
            status="pass" if fmt_passed else "fail",
            detail={
                "regressions": regressions,
                "missingFormats": missing_formats,
                "tolerance": GATE_PER_FORMAT_REGRESSION,
            },
        )
    )

    # ── Citation-support guardrail ─────────────────────────────────────
    if citation_support_recall is not None:
        citation_passed = citation_support_recall >= min_citation_support
        gates.append(
            GateResult(
                gate="guardrail.citationSupport",
                passed=citation_passed,
                status="pass" if citation_passed else "fail",
                detail={
                    "value": citation_support_recall,
                    "min": min_citation_support,
                },
            )
        )
    else:
        gates.append(
            GateResult(
                gate="guardrail.citationSupport",
                passed=False,
                status="fail",
                detail={"reason": "citation support not available", "min": min_citation_support},
            )
        )

    # ── Latency / cost guardrail ───────────────────────────────────────
    latency_ok = (
        latency_p95_ms is not None
        and max_latency_p95_ms is not None
        and latency_p95_ms <= max_latency_p95_ms
    )
    cost_ok = cost_usd is not None and max_cost_usd is not None and cost_usd <= max_cost_usd
    hg_passed = latency_ok and cost_ok
    gates.append(
        GateResult(
            gate="guardrail.latencyCost",
            passed=hg_passed,
            status="pass" if hg_passed else "fail",
            detail={
                "latencyP95Ms": latency_p95_ms,
                "maxLatencyP95Ms": max_latency_p95_ms,
                "costUsd": cost_usd,
                "maxCostUsd": max_cost_usd,
            },
        )
    )

    reranker_passed = (
        pre_rerank_mrr is not None
        and post_rerank_mrr is not None
        and post_rerank_mrr >= pre_rerank_mrr
    )
    gates.append(
        GateResult(
            gate="reranker.postRerankMrr",
            passed=reranker_passed,
            status="pass" if reranker_passed else "fail",
            detail={
                "preRerankMrr": pre_rerank_mrr,
                "postRerankMrr": post_rerank_mrr,
                "delta": (
                    post_rerank_mrr - pre_rerank_mrr
                    if pre_rerank_mrr is not None and post_rerank_mrr is not None
                    else None
                ),
            },
        )
    )

    reranker_deltas = {
        d.metric: d
        for d in control_deltas
        if d.baseline_mode == RERANKER_OFF and d.comparison_mode == RERANKER_ON
    }
    required_reranker_metrics = (
        "context_precision",
        "faithfulness",
        "response_relevancy",
    )
    missing_reranker_metrics = [
        metric
        for metric in required_reranker_metrics
        if reranker_deltas.get(metric) is None or reranker_deltas[metric].direction == "incomparable"
    ]
    correctness = [
        reranker_deltas.get("factual_correctness"),
        reranker_deltas.get("semantic_similarity"),
    ]
    reranker_answer_passed = (
        not missing_reranker_metrics
        and any(delta is not None and delta.direction != "incomparable" for delta in correctness)
        and all(
            delta is not None and delta.direction in {"improved", "unchanged"}
            for delta in (
                *(reranker_deltas.get(metric) for metric in required_reranker_metrics),
                *(delta for delta in correctness if delta is not None and delta.direction != "incomparable"),
            )
        )
    )
    gates.append(
        GateResult(
            gate="reranker.answerQuality",
            passed=reranker_answer_passed,
            status="pass" if reranker_answer_passed else "fail",
            detail={
                "missingRequiredMetrics": missing_reranker_metrics,
                "deltas": {
                    metric: delta.delta if delta else None
                    for metric, delta in reranker_deltas.items()
                },
            },
        )
    )

    return gates


def gate_summary(gates: Sequence[GateResult]) -> dict[str, Any]:
    """Produce a human-readable summary of all gate results."""
    statuses = [g.status for g in gates]
    return {
        "overall": "fail" if "fail" in statuses or "skip" in statuses else ("warn" if "warn" in statuses else "pass"),
        "total": len(gates),
        "pass": sum(1 for g in gates if g.status == "pass"),
        "fail": sum(1 for g in gates if g.status == "fail"),
        "warn": sum(1 for g in gates if g.status == "warn"),
        "skip": sum(1 for g in gates if g.status == "skip"),
        "gates": [{"gate": g.gate, "status": g.status, "passed": g.passed, "detail": g.detail} for g in gates],
    }
