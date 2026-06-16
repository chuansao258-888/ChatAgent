"""Deterministic fixture tests for 10d-B2 control deltas, token/latency summaries, and promotion gates.

All tests use in-memory fixture data — no live providers, no Ragas runtime, no dataset roots.
"""

from __future__ import annotations

import unittest

from chatagent_eval.control_analysis import (
    NO_RAG,
    FULL_RAG,
    WRONG_CONTEXT,
    ORACLE_CONTEXT,
    ControlDelta,
    GateResult,
    TokenSummary,
    LatencySummary,
    _control_mode,
    _rows_by_control,
    _metric_value,
    compute_citation_support_recall,
    compute_control_deltas,
    control_delta_matrix,
    compute_token_summary,
    compute_latency_summary,
    evaluate_10d_b2_gates,
    gate_summary,
)


# ── Fixture builders ────────────────────────────────────────────────────────


def _answer_row(
    sample_id: str,
    control_mode: str,
    *,
    faithfulness: float | None = None,
    response_relevancy: float | None = None,
    factual_correctness: float | None = None,
    semantic_similarity: float | None = None,
    context_recall: float | None = None,
    hit_at_k: float | None = None,
    phrase_recall: float | None = None,
    prompt_tokens: int | None = None,
    completion_tokens: int | None = None,
    total_tokens: int | None = None,
    context_tokens: int | None = None,
    retrieval_ms: float | None = None,
    reranker_ms: float | None = None,
    answer_ms: float | None = None,
    total_ms: float | None = None,
    fmt: str = "TXT",
) -> dict:
    """Build a deterministic answer fixture row with control label and metrics."""
    ragas_scores: dict[str, float | None] = {}
    for key, value in (("faithfulness", faithfulness), ("response_relevancy", response_relevancy),
                        ("factual_correctness", factual_correctness),
                        ("semantic_similarity", semantic_similarity)):
        if value is not None:
            ragas_scores[key] = value

    doc_ingestion: dict[str, float] = {}
    if context_recall is not None:
        doc_ingestion["contextRecallAtK"] = context_recall
    if hit_at_k is not None:
        doc_ingestion["hitAtK"] = hit_at_k
    if phrase_recall is not None:
        doc_ingestion["phraseRecall"] = phrase_recall

    metadata: dict = {
        "format": fmt,
        "ragasScores": ragas_scores,
        "docIngestion": doc_ingestion,
        "controlRun": {"mode": control_mode},
        "sourceSampleId": sample_id,
    }

    token_counts: dict[str, int] = {}
    if prompt_tokens is not None:
        token_counts["promptTokens"] = prompt_tokens
    if completion_tokens is not None:
        token_counts["completionTokens"] = completion_tokens
    if total_tokens is not None:
        token_counts["totalTokens"] = total_tokens
    if context_tokens is not None:
        token_counts["retrievedContextTokens"] = context_tokens
    if token_counts:
        metadata["tokenCounts"] = token_counts

    latency: dict[str, float] = {}
    if retrieval_ms is not None:
        latency["retrievalMs"] = retrieval_ms
    if reranker_ms is not None:
        latency["rerankerMs"] = reranker_ms
    if answer_ms is not None:
        latency["answerMs"] = answer_ms
    if total_ms is not None:
        latency["totalMs"] = total_ms
    if latency:
        metadata["latency"] = latency

    row = {
        "sampleId": sample_id,
        "datasetId": "doc-ingestion-retrieval-v1",
        "sourceGroupId": f"group-{sample_id}",
        "split": "holdout",
        "userInput": f"test query for {sample_id}",
        "referenceContextIds": [f"chunk-{sample_id}"],
        "response": f"generated answer for {sample_id}",
        "reference": f"reference answer for {sample_id}",
        "metadata": metadata,
    }
    if control_mode == FULL_RAG:
        metadata["citations"] = [
            {"sourceChunkId": f"chunk-{sample_id}", "supported": True}
        ]
    return row


def _retrieval_row(sample_id: str, fmt: str = "TXT", *, context_recall: float = 1.0,
                   phrase_recall: float = 1.0, hit_at_k: float = 1.0) -> dict:
    """Build a retrieval-only row (no answer-level metrics)."""
    return _answer_row(sample_id, "retrieval-only", fmt=fmt,
                       context_recall=context_recall, phrase_recall=phrase_recall,
                       hit_at_k=hit_at_k)


# ── Control-mode extraction ─────────────────────────────────────────────────


class TestControlModeExtraction(unittest.TestCase):
    def test_extracts_from_metadata_control_run(self):
        row = _answer_row("s-1", "full-rag")
        self.assertEqual("full-rag", _control_mode(row))

    def test_extracts_from_top_level_control_run(self):
        row = _answer_row("s-1", "no-rag")
        del row["metadata"]["controlRun"]
        row["controlRun"] = {"mode": "no-rag"}
        self.assertEqual("no-rag", _control_mode(row))

    def test_unknown_for_missing_control(self):
        row = _answer_row("s-1", "full-rag")
        del row["metadata"]["controlRun"]
        self.assertIsNone(_control_mode(row))

    def test_rows_by_control_groups_correctly(self):
        rows = [
            _answer_row("a", "full-rag"),
            _answer_row("b", "no-rag"),
            _answer_row("c", "full-rag"),
            _answer_row("d", "wrong-context"),
        ]
        grouped = _rows_by_control(rows)
        self.assertEqual(2, len(grouped["full-rag"]))
        self.assertEqual(1, len(grouped["no-rag"]))
        self.assertEqual(1, len(grouped["wrong-context"]))


# ── Metric extraction ───────────────────────────────────────────────────────


class TestMetricExtraction(unittest.TestCase):
    def test_extracts_ragas_score(self):
        row = _answer_row("s-1", "full-rag", faithfulness=0.85)
        self.assertAlmostEqual(0.85, _metric_value(row, "faithfulness"))

    def test_extracts_doc_ingestion_metric(self):
        row = _answer_row("s-1", "full-rag", context_recall=0.72)
        self.assertAlmostEqual(0.72, _metric_value(row, "contextRecallAtK"))

    def test_returns_none_for_missing_metric(self):
        row = _answer_row("s-1", "full-rag")
        self.assertIsNone(_metric_value(row, "factual_correctness"))

    def test_returns_none_for_non_numeric(self):
        row = _answer_row("s-1", "full-rag")
        row["metadata"]["ragasScores"]["faithfulness"] = "high"
        self.assertIsNone(_metric_value(row, "faithfulness"))


# ── Control deltas ──────────────────────────────────────────────────────────


class TestControlDeltas(unittest.TestCase):
    def test_full_rag_improves_over_no_rag(self):
        rows = [
            _answer_row("a", "full-rag", faithfulness=0.85, factual_correctness=0.78, response_relevancy=0.90),
            _answer_row("b", "full-rag", faithfulness=0.82, factual_correctness=0.75, response_relevancy=0.88),
            _answer_row("a", "no-rag", faithfulness=0.65, factual_correctness=0.60, response_relevancy=0.85),
            _answer_row("b", "no-rag", faithfulness=0.62, factual_correctness=0.58, response_relevancy=0.83),
        ]
        deltas = compute_control_deltas(
            rows,
            metrics=("faithfulness", "factual_correctness", "response_relevancy"),
            baseline_mode="no-rag",
            comparison_mode="full-rag",
        )
        by_metric = {d.metric: d for d in deltas}
        self.assertEqual("improved", by_metric["faithfulness"].direction)
        self.assertAlmostEqual((0.85 + 0.82) / 2 - (0.65 + 0.62) / 2, by_metric["faithfulness"].delta, places=4)
        self.assertEqual("improved", by_metric["factual_correctness"].direction)
        self.assertEqual("improved", by_metric["response_relevancy"].direction)

    def test_wrong_context_degraded_vs_full_rag(self):
        rows = [
            _answer_row("a", "full-rag", faithfulness=0.85),
            _answer_row("a", "wrong-context", faithfulness=0.45),
        ]
        deltas = compute_control_deltas(
            rows, metrics=("faithfulness",), baseline_mode="full-rag", comparison_mode="wrong-context",
        )
        self.assertEqual("degraded", deltas[0].direction)
        self.assertAlmostEqual(-0.40, deltas[0].delta, places=4)

    def test_oracle_context_improves_over_full_rag(self):
        rows = [
            _answer_row("a", "full-rag", factual_correctness=0.78),
            _answer_row("a", "oracle-context", factual_correctness=0.92),
        ]
        deltas = compute_control_deltas(
            rows, metrics=("factual_correctness",), baseline_mode="full-rag", comparison_mode="oracle-context",
        )
        self.assertEqual("improved", deltas[0].direction)
        self.assertAlmostEqual(0.14, deltas[0].delta, places=4)

    def test_incomparable_when_mode_has_no_rows(self):
        rows = [_answer_row("a", "full-rag", faithfulness=0.85)]
        deltas = compute_control_deltas(
            rows, metrics=("faithfulness",), baseline_mode="no-rag", comparison_mode="full-rag",
        )
        self.assertEqual("incomparable", deltas[0].direction)
        self.assertIsNone(deltas[0].delta)

    def test_unchanged_when_values_equal(self):
        rows = [
            _answer_row("a", "full-rag", faithfulness=0.80),
            _answer_row("a", "no-rag", faithfulness=0.80),
        ]
        deltas = compute_control_deltas(
            rows, metrics=("faithfulness",), baseline_mode="no-rag", comparison_mode="full-rag",
        )
        self.assertEqual("unchanged", deltas[0].direction)
        self.assertAlmostEqual(0.0, deltas[0].delta, places=4)

    def test_delta_matrix_covers_default_pairs(self):
        rows = [
            _answer_row("a", "full-rag", faithfulness=0.85, factual_correctness=0.78),
            _answer_row("a", "no-rag", faithfulness=0.62, factual_correctness=0.58),
            _answer_row("a", "wrong-context", faithfulness=0.40, factual_correctness=0.35),
            _answer_row("a", "oracle-context", faithfulness=0.92, factual_correctness=0.88),
        ]
        matrix = control_delta_matrix(rows, metrics=("faithfulness", "factual_correctness"))
        self.assertIn(("full-rag", "no-rag"), matrix)
        self.assertIn(("wrong-context", "full-rag"), matrix)
        self.assertIn(("oracle-context", "full-rag"), matrix)
        self.assertEqual("improved", matrix[("full-rag", "no-rag")][0].direction)
        self.assertEqual("degraded", matrix[("wrong-context", "full-rag")][0].direction)
        self.assertEqual("improved", matrix[("oracle-context", "full-rag")][0].direction)


# ── Token summaries ─────────────────────────────────────────────────────────


class TestTokenSummary(unittest.TestCase):
    def test_aggregates_token_counts(self):
        rows = [
            _answer_row("a", "full-rag", prompt_tokens=100, completion_tokens=20, total_tokens=120),
            _answer_row("b", "full-rag", prompt_tokens=200, completion_tokens=30, total_tokens=230),
            _answer_row("c", "no-rag", prompt_tokens=50, completion_tokens=15, total_tokens=65),
        ]
        summary = compute_token_summary(rows)
        self.assertEqual(350, summary.total_prompt)
        self.assertEqual(65, summary.total_completion)
        self.assertEqual(415, summary.total_tokens)
        self.assertEqual(3, summary.row_count)
        self.assertAlmostEqual(350 / 3, summary.mean_prompt, places=4)

    def test_by_control_breakdown(self):
        rows = [
            _answer_row("a", "full-rag", prompt_tokens=100, completion_tokens=20, total_tokens=120),
            _answer_row("b", "full-rag", prompt_tokens=200, completion_tokens=30, total_tokens=230),
            _answer_row("c", "no-rag", prompt_tokens=50, completion_tokens=15, total_tokens=65),
        ]
        summary = compute_token_summary(rows)
        self.assertIn("full-rag", summary.by_control)
        self.assertIn("no-rag", summary.by_control)
        self.assertAlmostEqual(150, summary.by_control["full-rag"]["meanPromptTokens"], places=4)
        self.assertAlmostEqual(50, summary.by_control["no-rag"]["meanPromptTokens"], places=4)

    def test_empty_rows_produces_zeros(self):
        summary = compute_token_summary([])
        self.assertEqual(0, summary.total_tokens)
        self.assertEqual(0, summary.row_count)
        self.assertEqual(0.0, summary.mean_tokens)

    def test_handles_missing_token_counts(self):
        rows = [_answer_row("a", "full-rag")]
        summary = compute_token_summary(rows)
        self.assertEqual(0, summary.total_tokens)


# ── Latency summaries ───────────────────────────────────────────────────────


class TestLatencySummary(unittest.TestCase):
    def test_mode_filter_reports_production_control_latency_only(self):
        rows = [
            _answer_row("a", FULL_RAG, total_ms=400),
            _answer_row("b", FULL_RAG, total_ms=500),
            _answer_row("a", ORACLE_CONTEXT, total_ms=5000),
            _answer_row("b", ORACLE_CONTEXT, total_ms=6000),
        ]
        summary = compute_latency_summary(rows, mode=FULL_RAG)
        self.assertEqual(2, summary.row_count)
        self.assertLess(summary.p95_total_ms, 1000)
        self.assertEqual({"full-rag"}, set(summary.by_control))

    def test_aggregates_latency_stats(self):
        rows = [
            _answer_row("a", "full-rag", retrieval_ms=10, answer_ms=300, total_ms=310),
            _answer_row("b", "full-rag", retrieval_ms=12, answer_ms=350, total_ms=362),
            _answer_row("c", "full-rag", retrieval_ms=8, answer_ms=280, total_ms=288),
        ]
        summary = compute_latency_summary(rows)
        self.assertAlmostEqual(10.0, summary.mean_retrieval_ms, places=2)
        self.assertAlmostEqual(310.0, summary.mean_answer_ms, places=2)
        self.assertAlmostEqual(320.0, summary.mean_total_ms, places=2)
        self.assertEqual(3, summary.row_count)

    def test_p95_is_none_for_single_row(self):
        rows = [_answer_row("a", "full-rag", total_ms=310)]
        summary = compute_latency_summary(rows)
        self.assertIsNone(summary.p95_total_ms)

    def test_p95_computed_for_enough_rows(self):
        rows = [_answer_row(f"{i}", "full-rag", total_ms=float(100 + i * 10)) for i in range(20)]
        summary = compute_latency_summary(rows)
        self.assertIsNotNone(summary.p95_total_ms)

    def test_by_control_breakdown(self):
        rows = [
            _answer_row("a", "full-rag", total_ms=300),
            _answer_row("b", "no-rag", total_ms=100),
        ]
        summary = compute_latency_summary(rows)
        self.assertIn("full-rag", summary.by_control)
        self.assertIn("no-rag", summary.by_control)
        self.assertAlmostEqual(300, summary.by_control["full-rag"]["meanTotalMs"] or 0, places=2)
        self.assertAlmostEqual(100, summary.by_control["no-rag"]["meanTotalMs"] or 0, places=2)

    def test_handles_missing_latency(self):
        rows = [_answer_row("a", "full-rag")]
        summary = compute_latency_summary(rows)
        self.assertIsNone(summary.mean_total_ms)
        self.assertEqual(1, summary.row_count)


# ── Citation support ────────────────────────────────────────────────────────


class TestCitationSupport(unittest.TestCase):
    def test_full_rag_reference_citation_support(self):
        rows = [
            _answer_row("a", FULL_RAG),
            _answer_row("b", FULL_RAG),
        ]
        self.assertEqual(1.0, compute_citation_support_recall(rows))

    def test_unsupported_or_wrong_citation_lowers_recall(self):
        good = _answer_row("a", FULL_RAG)
        bad = _answer_row("b", FULL_RAG)
        bad["metadata"]["citations"] = [{"sourceChunkId": "other", "supported": True}]
        self.assertEqual(0.5, compute_citation_support_recall([good, bad]))

    def test_missing_full_rag_rows_returns_none(self):
        self.assertIsNone(compute_citation_support_recall([_answer_row("a", NO_RAG)]))


# ── Promotion gates ─────────────────────────────────────────────────────────


class TestPromotionGates(unittest.TestCase):
    def test_primary_recall_pass(self):
        deltas = [
            ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.85, 0.23, "improved"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        primary = _find_gate(gates, "primary.contextRecall")
        self.assertTrue(primary.passed)
        self.assertEqual(0.72, primary.detail["value"])

    def test_primary_recall_fail_below_target(self):
        deltas = [
            ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.65, 0.03, "improved"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.62, phrase_recall=0.90, control_deltas=deltas)
        primary = _find_gate(gates, "primary.contextRecall")
        self.assertFalse(primary.passed)

    def test_primary_recall_pass_with_delta_over_baseline(self):
        # target = max(0.6294 + 0.08, 0.70) = 0.7094
        # 0.705 < 0.7094 -> fail
        deltas = [
            ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.85, 0.23, "improved"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.705, phrase_recall=0.90, control_deltas=deltas)
        primary = _find_gate(gates, "primary.contextRecall")
        self.assertFalse(primary.passed)
        self.assertAlmostEqual(0.7094, primary.detail["target"], places=4)

    def test_primary_and_phrase_gates_use_supplied_frozen_baselines(self):
        gates = evaluate_10d_b2_gates(
            context_recall=0.79,
            phrase_recall=0.90,
            control_deltas=[],
            baseline_context_recall=0.72,
            baseline_phrase_recall=0.91,
        )
        primary = _find_gate(gates, "primary.contextRecall")
        phrase = _find_gate(gates, "guardrail.phraseRecall")
        self.assertFalse(primary.passed)
        self.assertAlmostEqual(0.80, primary.detail["target"])
        self.assertFalse(phrase.passed)
        self.assertEqual(0.91, phrase.detail["baseline"])

    def test_rag_effectiveness_pass_when_fc_and_ss_both_improve(self):
        deltas = [
            ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.85, 0.23, "improved"),
            ControlDelta("factual_correctness", "no-rag", "full-rag", 0.58, 0.78, 0.20, "improved"),
            ControlDelta("semantic_similarity", "no-rag", "full-rag", 0.60, 0.82, 0.22, "improved"),
            ControlDelta("response_relevancy", "no-rag", "full-rag", 0.85, 0.88, 0.03, "improved"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        rag = _find_gate(gates, "ragEffectiveness.fullRagVsNoRag")
        self.assertTrue(rag.passed, f"should pass with both FC and SS improved, got: {rag.detail}")

    def test_rag_effectiveness_pass_when_only_fc_improves(self):
        deltas = [
            ControlDelta("factual_correctness", "no-rag", "full-rag", 0.58, 0.78, 0.20, "improved"),
            ControlDelta("response_relevancy", "no-rag", "full-rag", 0.85, 0.88, 0.03, "improved"),
            ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.85, 0.23, "improved"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        rag = _find_gate(gates, "ragEffectiveness.fullRagVsNoRag")
        self.assertTrue(rag.passed)

    def test_rag_effectiveness_fail_when_rr_degraded(self):
        deltas = [
            ControlDelta("factual_correctness", "no-rag", "full-rag", 0.58, 0.78, 0.20, "improved"),
            ControlDelta("response_relevancy", "no-rag", "full-rag", 0.90, 0.82, -0.08, "degraded"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        rag = _find_gate(gates, "ragEffectiveness.fullRagVsNoRag")
        self.assertFalse(rag.passed)

    def test_rag_effectiveness_fail_when_no_answer_metrics(self):
        deltas: list[ControlDelta] = []
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        rag = _find_gate(gates, "ragEffectiveness.fullRagVsNoRag")
        self.assertFalse(rag.passed)
        self.assertIn("missingRequiredMetrics", rag.detail)

    def test_rag_effectiveness_fail_when_faithfulness_missing(self):
        deltas = [
            ControlDelta("factual_correctness", "no-rag", "full-rag", 0.58, 0.78, 0.20, "improved"),
            ControlDelta("response_relevancy", "no-rag", "full-rag", 0.85, 0.88, 0.03, "improved"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        rag = _find_gate(gates, "ragEffectiveness.fullRagVsNoRag")
        self.assertFalse(rag.passed)
        self.assertIn("faithfulness", rag.detail["missingRequiredMetrics"])

    def test_rag_effectiveness_requires_absolute_full_rag_faithfulness(self):
        deltas = [
            ControlDelta("factual_correctness", "no-rag", "full-rag", 0.58, 0.78, 0.20, "improved"),
            ControlDelta("response_relevancy", "no-rag", "full-rag", 0.85, 0.88, 0.03, "improved"),
            ControlDelta("faithfulness", "no-rag", "full-rag", None, 0.79, None, "incomparable"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        rag = _find_gate(gates, "ragEffectiveness.fullRagVsNoRag")
        self.assertFalse(rag.passed)
        self.assertEqual(0.79, rag.detail["faithfulness"]["fullRagValue"])

    def test_negative_control_pass_when_wrong_context_degraded(self):
        deltas = [
            ControlDelta("faithfulness", "full-rag", "wrong-context", 0.85, 0.40, -0.45, "degraded"),
            ControlDelta("factual_correctness", "full-rag", "wrong-context", 0.78, 0.35, -0.43, "degraded"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        neg = _find_gate(gates, "negativeControl.wrongContextDegrades")
        self.assertTrue(neg.passed)

    def test_negative_control_fail_when_not_worse(self):
        deltas = [
            ControlDelta("faithfulness", "full-rag", "wrong-context", 0.85, 0.83, -0.02, "unchanged"),
            ControlDelta("factual_correctness", "full-rag", "wrong-context", 0.78, 0.77, -0.01, "unchanged"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        neg = _find_gate(gates, "negativeControl.wrongContextDegrades")
        self.assertFalse(neg.passed)

    def test_negative_control_requires_material_degradation(self):
        deltas = [
            ControlDelta("faithfulness", "full-rag", "wrong-context", 0.85, 0.84, -0.01, "degraded"),
            ControlDelta("factual_correctness", "full-rag", "wrong-context", 0.78, 0.76, -0.02, "degraded"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        neg = _find_gate(gates, "negativeControl.wrongContextDegrades")
        self.assertFalse(neg.passed)
        self.assertEqual(0.05, neg.detail["minDegradation"])

    def test_oracle_context_identifies_retrieval_bottleneck(self):
        deltas = [
            ControlDelta("factual_correctness", "full-rag", "oracle-context", 0.78, 0.92, 0.14, "improved"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        oracle = _find_gate(gates, "oracleContext.reportedUpperBound")
        self.assertTrue(oracle.passed)
        self.assertIn("retrieval/reranking/chunking", oracle.detail["bottleneck"])

    def test_oracle_context_identifies_generation_bottleneck(self):
        deltas = [
            ControlDelta("factual_correctness", "full-rag", "oracle-context", 0.78, 0.75, -0.03, "degraded"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        oracle = _find_gate(gates, "oracleContext.reportedUpperBound")
        self.assertTrue(oracle.passed)
        self.assertIn("answer prompt, model, or judge", oracle.detail["bottleneck"])

    def test_phrase_recall_guardrail_pass(self):
        deltas = [
            ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.85, 0.23, "improved"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=deltas)
        pr = _find_gate(gates, "guardrail.phraseRecall")
        self.assertTrue(pr.passed)
        self.assertEqual(0.90, pr.detail["value"])

    def test_phrase_recall_guardrail_fail_below_baseline(self):
        deltas = [
            ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.85, 0.23, "improved"),
        ]
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.82, control_deltas=deltas)
        pr = _find_gate(gates, "guardrail.phraseRecall")
        self.assertFalse(pr.passed)

    def test_per_format_regression_detected(self):
        deltas = [
            ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.85, 0.23, "improved"),
        ]
        per_format = {"PDF": {"contextRecallAtK": 0.50}, "TXT": {"contextRecallAtK": 0.80}}
        baseline_fmt = {"PDF": {"contextRecallAtK": 0.58}, "TXT": {"contextRecallAtK": 0.81}}
        gates = evaluate_10d_b2_gates(
            context_recall=0.72, phrase_recall=0.90, control_deltas=deltas,
            per_format_metrics=per_format, baseline_per_format=baseline_fmt,
        )
        fmt_gate = _find_gate(gates, "guardrail.perFormatRegression")
        self.assertFalse(fmt_gate.passed)
        self.assertEqual(1, len(fmt_gate.detail["regressions"]))

    def test_per_format_no_regression_passes(self):
        deltas = [
            ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.85, 0.23, "improved"),
        ]
        per_format = {"PDF": {"contextRecallAtK": 0.56}, "TXT": {"contextRecallAtK": 0.80}}
        baseline_fmt = {"PDF": {"contextRecallAtK": 0.58}, "TXT": {"contextRecallAtK": 0.81}}
        gates = evaluate_10d_b2_gates(
            context_recall=0.72, phrase_recall=0.90, control_deltas=deltas,
            per_format_metrics=per_format, baseline_per_format=baseline_fmt,
        )
        fmt_gate = _find_gate(gates, "guardrail.perFormatRegression")
        self.assertTrue(fmt_gate.passed)

    def test_citation_support_guardrail_pass(self):
        gates = evaluate_10d_b2_gates(
            context_recall=0.72,
            phrase_recall=0.90,
            control_deltas=[
                ControlDelta("factual_correctness", "no-rag", "full-rag", 0.58, 0.78, 0.20, "improved"),
                ControlDelta("response_relevancy", "no-rag", "full-rag", 0.85, 0.88, 0.03, "improved"),
                ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.85, 0.23, "improved"),
            ],
            citation_support_recall=0.90,
        )
        citation = _find_gate(gates, "guardrail.citationSupport")
        self.assertTrue(citation.passed)

    def test_citation_support_guardrail_fail_when_missing(self):
        gates = evaluate_10d_b2_gates(context_recall=0.72, phrase_recall=0.90, control_deltas=[])
        citation = _find_gate(gates, "guardrail.citationSupport")
        self.assertFalse(citation.passed)

    def test_citation_support_guardrail_fail_below_threshold(self):
        gates = evaluate_10d_b2_gates(
            context_recall=0.72,
            phrase_recall=0.90,
            control_deltas=[],
            citation_support_recall=0.50,
        )
        citation = _find_gate(gates, "guardrail.citationSupport")
        self.assertFalse(citation.passed)

    def test_latency_cost_gate(self):
        deltas = [
            ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.85, 0.23, "improved"),
        ]
        gates = evaluate_10d_b2_gates(
            context_recall=0.72, phrase_recall=0.90, control_deltas=deltas,
            latency_p95_ms=450, max_latency_p95_ms=500,
            cost_usd=0.05, max_cost_usd=0.10,
        )
        hg = _find_gate(gates, "guardrail.latencyCost")
        self.assertTrue(hg.passed)

    def test_latency_cost_fail(self):
        deltas = [
            ControlDelta("faithfulness", "no-rag", "full-rag", 0.62, 0.85, 0.23, "improved"),
        ]
        gates = evaluate_10d_b2_gates(
            context_recall=0.72, phrase_recall=0.90, control_deltas=deltas,
            latency_p95_ms=600, max_latency_p95_ms=500,
        )
        hg = _find_gate(gates, "guardrail.latencyCost")
        self.assertFalse(hg.passed)


# ── Gate summary ────────────────────────────────────────────────────────────


class TestGateSummary(unittest.TestCase):
    def test_all_pass(self):
        gates = [
            GateResult("g1", True, "pass", {}),
            GateResult("g2", True, "pass", {}),
        ]
        summary = gate_summary(gates)
        self.assertEqual("pass", summary["overall"])
        self.assertEqual(2, summary["pass"])

    def test_one_fail_dominates(self):
        gates = [
            GateResult("g1", True, "pass", {}),
            GateResult("g2", False, "fail", {}),
        ]
        summary = gate_summary(gates)
        self.assertEqual("fail", summary["overall"])

    def test_warn_when_no_fail_but_warn_present(self):
        gates = [
            GateResult("g1", True, "pass", {}),
            GateResult("g2", True, "warn", {}),
        ]
        summary = gate_summary(gates)
        self.assertEqual("warn", summary["overall"])

    def test_skip_fails_closed(self):
        summary = gate_summary([GateResult("g1", False, "skip", {})])
        self.assertEqual("fail", summary["overall"])


# ── Helpers ─────────────────────────────────────────────────────────────────


def _find_gate(gates: list[GateResult], name: str) -> GateResult:
    for gate in gates:
        if gate.gate == name:
            return gate
    raise AssertionError(f"missing gate: {name}")


if __name__ == "__main__":
    unittest.main()
