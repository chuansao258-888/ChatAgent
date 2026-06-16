"""Tests for answer_harness: prompt building, control-mode generation, and output schema."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

import run_eval
from chatagent_eval.answer_harness import (
    NO_RAG,
    FULL_RAG,
    WRONG_CONTEXT,
    ORACLE_CONTEXT,
    RERANKER_OFF,
    RERANKER_ON,
    AnswerHarnessConfig,
    _build_prompt,
    _clean_base_url,
    _apply_context_budget,
    _context_list,
    _context_text,
    _extract_citations,
    _generate_answer,
    _generate_row,
    _prompt_contexts,
    _provider_api_key as _answer_provider_api_key,
    _provider_base_url as _answer_provider_base_url,
    _shuffled_contexts,
    run_answer_harness,
)
from chatagent_eval.schemas import load_json, validate


def _retrieval_row(sample_id: str = "s-1", query: str = "What is revenue?",
                   ref_content: str = "Revenue was $42 million.",
                   ref_answer: str | None = None,
                   ref_chunk_ids: list[str] | None = None,
                   retrieved: list[dict] | None = None,
                   fmt: str = "TXT", split: str = "calibration") -> dict:
    if ref_chunk_ids is None:
        ref_chunk_ids = [f"chunk-{sample_id}"]
    if retrieved is None:
        retrieved = [
            {"chunkId": ref_chunk_ids[0], "documentId": "doc-1", "content": ref_content, "score": 0.95},
        ]
    if ref_answer is None:
        ref_answer = ref_content
    return {
        "sampleId": sample_id,
        "datasetId": "doc-ingestion-retrieval-v1",
        "sourceGroupId": f"group-{sample_id}",
        "split": split,
        "userInput": query,
        "referenceContextIds": ref_chunk_ids,
        "metadata": {
            "format": fmt,
            "referenceContent": ref_content,
            "referenceAnswer": ref_answer,
            "generationMethod": "template-question",
            "retrievedContexts": retrieved,
            "candidateContexts": [
                {
                    "id": ctx.get("chunkId", f"cand-{i}"),
                    "chunkId": ctx.get("chunkId", f"cand-{i}"),
                    "content": ctx.get("content", ctx.get("text", "")),
                    "rankSignals": {"candidateRank": i + 1, "denseRank": i + 1},
                }
                for i, ctx in enumerate(retrieved)
            ],
            "retrievalLatency": {
                "candidatePathMs": 12.0,
                "finalProductionPathMs": 20.0,
            },
        },
    }


def _mock_generate(messages: list[dict[str, str]], config: AnswerHarnessConfig) -> tuple[str, dict[str, int]]:
    query = messages[-1].get("content", "") if messages else ""
    if "Answer without any provided context" in query:
        return "From my knowledge: approximately $40 million.", {"promptTokens": 30, "completionTokens": 12, "totalTokens": 42}
    return "Based on the context, revenue was $42 million [1].", {"promptTokens": 60, "completionTokens": 12, "totalTokens": 72}


def _mock_empty_generate(messages: list[dict[str, str]], config: AnswerHarnessConfig) -> tuple[str, dict[str, int]]:
    return "", {"promptTokens": 60, "completionTokens": 0, "totalTokens": 60}


class TestPromptBuilding(unittest.TestCase):
    def test_no_rag_prompt_has_no_context(self):
        messages = _build_prompt("What is revenue?", [], NO_RAG)
        self.assertIn("Answer without any provided context", messages[-1]["content"])

    def test_full_rag_prompt_includes_context(self):
        messages = _build_prompt("What is revenue?", ["Revenue was $42M."], FULL_RAG)
        self.assertIn("Revenue was $42M.", messages[-1]["content"])
        self.assertIn("shortest answer", messages[0]["content"])

    def test_full_rag_prompt_discourages_reference_style_extra_facts(self):
        messages = _build_prompt("What is revenue?", ["Revenue was $42M."], FULL_RAG)
        system = messages[0]["content"]
        self.assertIn("Prefer the exact requested value, name, date, or phrase", system)
        self.assertIn("Do not add prefaces, explanations, citations, source descriptions, or facts", system)

    def test_oracle_prompt_labels_context_as_reference(self):
        messages = _build_prompt("What is revenue?", ["Revenue was $42M."], ORACLE_CONTEXT)
        self.assertIn("Reference text:", messages[-1]["content"])

    def test_prompt_contexts_match_production_citation_shape(self):
        contexts = [{
            "chunkId": "chunk-1",
            "documentName": "Filing.html",
            "chunkIndex": 3,
            "sectionPath": "Revenue",
            "content": "Revenue was $42M.",
        }]
        rendered = _prompt_contexts(contexts)[0]
        self.assertIn("[1] Source: Filing.html [KNOWLEDGE_BASE] chunk 3", rendered)
        self.assertIn("Section: Revenue", rendered)
        self.assertIn("Chunk Content:\nRevenue was $42M.", rendered)


class TestContextHelpers(unittest.TestCase):
    def test_context_list_from_metadata(self):
        row = _retrieval_row()
        contexts = _context_list(row, row["metadata"])
        self.assertEqual(1, len(contexts))
        self.assertEqual("Revenue was $42 million.", _context_text(contexts[0]))

    def test_context_list_from_top_level(self):
        row = _retrieval_row()
        row["retrievedContexts"] = [{"id": "c1", "chunkId": "c1", "text": "Top-level context"}]
        del row["metadata"]["retrievedContexts"]
        contexts = _context_list(row, row["metadata"])
        self.assertEqual("Top-level context", _context_text(contexts[0]))

    def test_shuffled_contexts_offsets_by_one(self):
        rows = [
            _retrieval_row("s-1", retrieved=[{"chunkId": "a", "content": "A"}]),
            _retrieval_row("s-2", retrieved=[{"chunkId": "b", "content": "B"}]),
            _retrieval_row("s-3", retrieved=[{"chunkId": "c", "content": "C"}]),
        ]
        shuffled = _shuffled_contexts(rows)
        self.assertEqual("B", _context_text(shuffled[0][0]))
        self.assertEqual("C", _context_text(shuffled[1][0]))
        self.assertEqual("A", _context_text(shuffled[2][0]))

    def test_shuffled_contexts_skip_same_source_group(self):
        rows = [
            _retrieval_row("s-1", retrieved=[{"chunkId": "a", "content": "A"}]),
            _retrieval_row("s-2", retrieved=[{"chunkId": "b", "content": "B"}]),
            _retrieval_row("s-3", retrieved=[{"chunkId": "c", "content": "C"}]),
        ]
        rows[1]["sourceGroupId"] = rows[0]["sourceGroupId"]
        shuffled = _shuffled_contexts(rows)
        self.assertEqual("C", _context_text(shuffled[0][0]))


class TestGenerateRow(unittest.TestCase):
    def test_uses_human_authored_reference_answer_instead_of_full_chunk(self):
        row = _retrieval_row(
            ref_content="Long context with revenue details and unrelated boilerplate.",
            ref_answer="Revenue was $42 million.",
        )
        result = _generate_row(
            row=row,
            mode=FULL_RAG,
            shuffled_contexts=[[]],
            index=0,
            config=AnswerHarnessConfig(run_id="manual-reference-answer"),
            generate=_mock_generate,
        )
        self.assertEqual("Revenue was $42 million.", result["reference"])
        self.assertEqual("Revenue was $42 million.", result["metadata"]["referenceAnswer"])

    def setUp(self):
        self.config = AnswerHarnessConfig(run_id="test", max_samples=2)

    def test_generates_all_required_control_modes(self):
        rows = [_retrieval_row("s-1"), _retrieval_row("s-2")]
        row = rows[0]
        shuffled = _shuffled_contexts(rows)
        for mode in (NO_RAG, FULL_RAG, WRONG_CONTEXT, ORACLE_CONTEXT, RERANKER_OFF, RERANKER_ON):
            result = _generate_row(
                row=row, mode=mode, shuffled_contexts=shuffled, index=0,
                config=self.config, generate=_mock_generate,
            )
            self.assertIn(mode, result["sampleId"])
            self.assertIn("response", result)
            self.assertIn("reference", result)
            self.assertEqual(mode, result["metadata"]["controlRun"]["mode"])
            self.assertEqual("s-1", result["metadata"]["sourceSampleId"])
            self.assertTrue(result["metadata"]["referenceContexts"])

    def test_full_rag_has_retrieved_contexts(self):
        row = _retrieval_row("s-1")
        shuffled = _shuffled_contexts([row])
        result = _generate_row(
            row=row, mode=FULL_RAG, shuffled_contexts=shuffled, index=0,
            config=self.config, generate=_mock_generate,
        )
        self.assertGreater(len(result["retrievedContexts"]), 0)
        self.assertIn("text", result["retrievedContexts"][0])

    def test_no_rag_has_empty_contexts(self):
        row = _retrieval_row("s-1")
        shuffled = _shuffled_contexts([row])
        result = _generate_row(
            row=row, mode=NO_RAG, shuffled_contexts=shuffled, index=0,
            config=self.config, generate=_mock_generate,
        )
        self.assertEqual([], result["retrievedContexts"])

    def test_oracle_uses_reference_content(self):
        row = _retrieval_row("s-1", ref_content="Oracle reference text here.")
        shuffled = _shuffled_contexts([row])
        result = _generate_row(
            row=row, mode=ORACLE_CONTEXT, shuffled_contexts=shuffled, index=0,
            config=self.config, generate=_mock_generate,
        )
        self.assertIn("Oracle reference text", result["retrievedContexts"][0]["text"])

    def test_wrong_context_swaps_from_shuffled(self):
        rows = [
            _retrieval_row("s-1", query="Q1", retrieved=[{"chunkId": "a", "content": "A context"}]),
            _retrieval_row("s-2", query="Q2", retrieved=[{"chunkId": "b", "content": "B context"}]),
        ]
        shuffled = _shuffled_contexts(rows)
        result = _generate_row(
            row=rows[0], mode=WRONG_CONTEXT, shuffled_contexts=shuffled, index=0,
            config=self.config, generate=_mock_generate,
        )
        # Row 0 gets shuffled context from row 1
        self.assertIn("B context", result["retrievedContexts"][0]["text"])

    def test_wrong_context_requires_a_disjoint_source_group(self):
        row = _retrieval_row("s-1")
        with self.assertRaisesRegex(ValueError, "disjoint source-group"):
            _generate_row(
                row=row, mode=WRONG_CONTEXT, shuffled_contexts=_shuffled_contexts([row]), index=0,
                config=self.config, generate=_mock_generate,
            )

    def test_reranker_off_uses_candidate_contexts(self):
        row = _retrieval_row(
            "s-1",
            retrieved=[{"chunkId": "final", "content": "Final reranked context"}],
        )
        row["metadata"]["candidateContexts"] = [
            {"id": "cand-1", "chunkId": "cand-1", "content": "Candidate context", "rankSignals": {"candidateRank": 1, "denseRank": 1}},
        ]
        result = _generate_row(
            row=row, mode=RERANKER_OFF, shuffled_contexts=_shuffled_contexts([row]), index=0,
            config=self.config, generate=_mock_generate,
        )
        self.assertIn("Candidate context", result["retrievedContexts"][0]["text"])
        self.assertFalse(result["metadata"]["controlRun"]["rerankerEnabled"])

    def test_reranker_on_requires_retrieved_contexts(self):
        row = _retrieval_row("s-1")
        row["metadata"]["retrievedContexts"] = []
        with self.assertRaisesRegex(ValueError, "reranker-on"):
            _generate_row(
                row=row, mode=RERANKER_ON, shuffled_contexts=_shuffled_contexts([row]), index=0,
                config=self.config, generate=_mock_generate,
            )

    def test_total_latency_includes_the_control_retrieval_path(self):
        row = _retrieval_row("s-1")
        shuffled = _shuffled_contexts([row])
        full = _generate_row(
            row=row, mode=FULL_RAG, shuffled_contexts=shuffled, index=0,
            config=self.config, generate=_mock_generate,
        )
        reranker_off = _generate_row(
            row=row, mode=RERANKER_OFF, shuffled_contexts=shuffled, index=0,
            config=self.config, generate=_mock_generate,
        )
        no_rag = _generate_row(
            row=row, mode=NO_RAG, shuffled_contexts=shuffled, index=0,
            config=self.config, generate=_mock_generate,
        )
        self.assertEqual(20.0, full["metadata"]["latency"]["retrievalMs"])
        self.assertAlmostEqual(
            20.0,
            full["metadata"]["latency"]["totalMs"] - full["metadata"]["latency"]["answerMs"],
        )
        self.assertEqual(12.0, reranker_off["metadata"]["latency"]["retrievalMs"])
        self.assertEqual(0.0, no_rag["metadata"]["latency"]["retrievalMs"])

    def test_production_control_requires_retrieval_latency_evidence(self):
        row = _retrieval_row("s-1")
        del row["metadata"]["retrievalLatency"]
        with self.assertRaisesRegex(ValueError, "retrievalLatency.finalProductionPathMs"):
            _generate_row(
                row=row, mode=FULL_RAG, shuffled_contexts=_shuffled_contexts([row]), index=0,
                config=self.config, generate=_mock_generate,
            )

    def test_context_budget_truncates_oversized_context_and_records_evidence(self):
        row = _retrieval_row("s-1", retrieved=[{"chunkId": "large", "content": "x" * 100}])
        config = AnswerHarnessConfig(run_id="budget", context_token_budget=10)
        result = _generate_row(
            row=row, mode=FULL_RAG, shuffled_contexts=_shuffled_contexts([row]), index=0,
            config=config, generate=_mock_generate,
        )
        self.assertEqual(20, len(result["retrievedContexts"][0]["text"]))
        self.assertTrue(result["metadata"]["contextBudget"]["truncated"])
        self.assertEqual(10, result["metadata"]["contextBudget"]["includedApproxTokens"])
        self.assertEqual(50, result["metadata"]["contextBudget"]["originalApproxTokens"])

    def test_context_budget_preserves_chunk_order_and_provenance(self):
        contexts = [
            {"chunkId": "first", "content": "a" * 8},
            {"chunkId": "second", "content": "b" * 8},
        ]
        packed = _apply_context_budget(contexts, 6)
        self.assertEqual(["first", "second"], [context["chunkId"] for context in packed])
        self.assertEqual(8, len(packed[0]["content"]))
        self.assertEqual(4, len(packed[1]["content"]))
        self.assertTrue(packed[1]["contextBudgetTruncated"])


class TestCitationExtraction(unittest.TestCase):
    def test_extracts_marker_citations(self):
        response = "Revenue was $42 million [1] according to the filing."
        contexts = [{"chunkId": "chunk-1"}, {"chunkId": "chunk-2"}]
        citations = _extract_citations(response, contexts)
        self.assertEqual(2, len(citations))
        self.assertTrue(citations[0]["supported"])

    def test_empty_contexts_produces_empty_citations(self):
        citations = _extract_citations("No citations.", [])
        self.assertEqual([], citations)


class TestRunAnswerHarness(unittest.TestCase):
    def setUp(self):
        self.config = AnswerHarnessConfig(run_id="smoke-test", _generate=_mock_generate)

    def test_writes_all_artifacts(self):
        rows = [_retrieval_row(f"s-{i}") for i in range(3)]
        with tempfile.TemporaryDirectory() as tmp:
            output_root = Path(tmp) / "out"
            output_root.mkdir()
            run_dir = run_answer_harness(rows=rows, output_root=output_root, config=self.config)
            for name in ("manifest.json", "samples.jsonl", "failures.jsonl", "report.json"):
                self.assertTrue((run_dir / name).exists(), f"Missing: {name}")

    def test_output_rows_validate_against_schema(self):
        schema_path = (
            Path(__file__).resolve().parents[3]
            / "chatagent" / "bootstrap" / "src" / "test" / "resources"
            / "eval" / "v2" / "schemas" / "eval-doc-ingestion-dataset-record.schema.json"
        )
        schema = load_json(schema_path)
        rows = [_retrieval_row("s-1")]
        with tempfile.TemporaryDirectory() as tmp:
            output_root = Path(tmp) / "out"
            output_root.mkdir()
            run_dir = run_answer_harness(rows=rows, output_root=output_root, config=self.config)
            with open(run_dir / "samples.jsonl", encoding="utf-8") as f:
                for line in f:
                    if line.strip():
                        record = json.loads(line)
                        validate(record, schema)

    def test_respects_max_samples(self):
        rows = [_retrieval_row(f"s-{i}") for i in range(10)]
        config = AnswerHarnessConfig(run_id="smoke-max", max_samples=2, _generate=_mock_generate)
        with tempfile.TemporaryDirectory() as tmp:
            output_root = Path(tmp) / "out"
            output_root.mkdir()
            run_dir = run_answer_harness(rows=rows, output_root=output_root, config=config)
            with open(run_dir / "samples.jsonl", encoding="utf-8") as f:
                all_rows = [json.loads(line) for line in f if line.strip()]
            # Current B3.4 default is full-rag only; legacy controls must be requested explicitly.
            self.assertEqual(2, len(all_rows))
            source_ids = set()
            for r in all_rows:
                sid = r["sampleId"]
                self.assertTrue(sid.endswith("-full-rag"))
                source_ids.add(sid[: -len("-full-rag")])
            self.assertEqual(2, len(source_ids))

    def test_empty_llm_response_is_failed_not_generated(self):
        rows = [_retrieval_row("s-1")]
        config = AnswerHarnessConfig(run_id="empty-response", _generate=_mock_empty_generate)
        with tempfile.TemporaryDirectory() as tmp:
            output_root = Path(tmp) / "out"
            output_root.mkdir()
            run_dir = run_answer_harness(rows=rows, output_root=output_root, config=config)
            metrics = load_json(run_dir / "metrics.json")
            failures = [
                json.loads(line)
                for line in (run_dir / "failures.jsonl").read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            samples = [
                json.loads(line)
                for line in (run_dir / "samples.jsonl").read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            self.assertEqual("warn", metrics["status"])
            self.assertEqual(0, metrics["generatedRows"])
            self.assertEqual(1, metrics["failedRows"])
            self.assertEqual([], samples)
            self.assertEqual("llm_empty_response", failures[0]["errorCategory"])

    def test_filters_to_calibration_development_by_default(self):
        rows = [
            _retrieval_row("cal", split="calibration"),
            _retrieval_row("dev", split="development"),
            _retrieval_row("hold", split="holdout"),
        ]
        config = AnswerHarnessConfig(run_id="smoke-splits", _generate=_mock_generate)
        with tempfile.TemporaryDirectory() as tmp:
            output_root = Path(tmp) / "out"
            output_root.mkdir()
            run_dir = run_answer_harness(rows=rows, output_root=output_root, config=config)
            all_rows = [json.loads(line) for line in (run_dir / "samples.jsonl").read_text(encoding="utf-8").splitlines() if line]
            self.assertNotIn("holdout", {row["split"] for row in all_rows})

    def test_explicit_holdout_split_is_allowed_for_final_verification(self):
        rows = [_retrieval_row("hold", split="holdout")]
        config = AnswerHarnessConfig(run_id="smoke-holdout", splits=("holdout",), _generate=_mock_generate)
        with tempfile.TemporaryDirectory() as tmp:
            output_root = Path(tmp) / "out"
            output_root.mkdir()
            run_dir = run_answer_harness(rows=rows, output_root=output_root, config=config)
            all_rows = [json.loads(line) for line in (run_dir / "samples.jsonl").read_text(encoding="utf-8").splitlines() if line]
            self.assertEqual({"holdout"}, {row["split"] for row in all_rows})

    def test_resumes_completed_rows_from_matching_checkpoint(self):
        rows = [_retrieval_row("resume-1"), _retrieval_row("resume-2")]
        calls = 0

        def interrupt_after_first(messages, config):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise KeyboardInterrupt()
            return _mock_generate(messages, config)

        with tempfile.TemporaryDirectory() as tmp:
            output_root = Path(tmp) / "out"
            output_root.mkdir()
            interrupted = AnswerHarnessConfig(run_id="smoke-resume", _generate=interrupt_after_first)
            with self.assertRaises(KeyboardInterrupt):
                run_answer_harness(rows=rows, output_root=output_root, config=interrupted)

            checkpoint_dir = output_root / ".smoke-resume.checkpoint"
            self.assertTrue(checkpoint_dir.exists())
            checkpoint_rows = (checkpoint_dir / "samples.jsonl").read_text(encoding="utf-8").splitlines()
            self.assertEqual(1, len(checkpoint_rows))

            resumed_calls = 0

            def resume_generate(messages, config):
                nonlocal resumed_calls
                resumed_calls += 1
                return _mock_generate(messages, config)

            resumed = AnswerHarnessConfig(run_id="smoke-resume", _generate=resume_generate)
            run_dir = run_answer_harness(rows=rows, output_root=output_root, config=resumed)
            final_rows = (run_dir / "samples.jsonl").read_text(encoding="utf-8").splitlines()
            self.assertEqual(2, len(final_rows))
            self.assertEqual(1, resumed_calls)
            self.assertFalse(checkpoint_dir.exists())

    def test_rejects_checkpoint_from_different_config(self):
        rows = [_retrieval_row("resume")]

        def interrupt_immediately(messages, config):
            raise KeyboardInterrupt()

        with tempfile.TemporaryDirectory() as tmp:
            output_root = Path(tmp) / "out"
            output_root.mkdir()
            interrupted = AnswerHarnessConfig(run_id="smoke-mismatch", _generate=interrupt_immediately)
            with self.assertRaises(KeyboardInterrupt):
                run_answer_harness(rows=rows, output_root=output_root, config=interrupted)

            mismatched = AnswerHarnessConfig(
                run_id="smoke-mismatch",
                control_modes=(NO_RAG,),
                _generate=_mock_generate,
            )
            with self.assertRaisesRegex(ValueError, "checkpoint config mismatch"):
                run_answer_harness(rows=rows, output_root=output_root, config=mismatched)


class TestConfigValidation(unittest.TestCase):
    def test_rejects_unknown_control_mode(self):
        with self.assertRaises(ValueError):
            AnswerHarnessConfig(run_id="test", control_modes=("no-rag", "imaginary-mode"))

    def test_rejects_empty_control_modes(self):
        with self.assertRaises(ValueError):
            AnswerHarnessConfig(run_id="test", control_modes=())

    def test_rejects_unsafe_run_id(self):
        with self.assertRaises(ValueError):
            AnswerHarnessConfig(run_id="../escape")

    def test_clean_base_url_strips_env_file_semicolon(self):
        self.assertEqual("https://api.deepseek.com", _clean_base_url("'https://api.deepseek.com';"))

    def test_answer_provider_prefers_two_zhipu_keys_before_zai_and_deepseek(self):
        with patch.dict(
            os.environ,
            {
                "CHATAGENT_ZHIPUAI_API_KEY": "fake-zhipu-primary",
                "CHATAGENT_ZHIPUAI_API_KEY_2": "fake-zhipu-secondary",
                "CHATAGENT_ZAI_CODING_API_KEY": "fake-zai-key",
                "CHATAGENT_DEEPSEEK_API_KEY": "fake-deepseek-key",
            },
            clear=True,
        ):
            self.assertEqual("fake-zhipu-primary", _answer_provider_api_key("zhipu"))
        with patch.dict(
            os.environ,
            {
                "CHATAGENT_ZHIPUAI_API_KEY_2": "fake-zhipu-secondary",
                "CHATAGENT_ZAI_CODING_API_KEY": "fake-zai-key",
                "CHATAGENT_DEEPSEEK_API_KEY": "fake-deepseek-key",
            },
            clear=True,
        ):
            self.assertEqual("fake-zhipu-secondary", _answer_provider_api_key("zhipu"))
            self.assertEqual("fake-zai-key", _answer_provider_api_key("zai"))
            self.assertEqual("fake-deepseek-key", _answer_provider_api_key("deepseek"))

    def test_answer_provider_uses_zai_base_url_alias(self):
        with patch.dict(
            os.environ,
            {"CHATAGENT_ZAI_CODING_BASE_URL": "https://zai.example.test/api/paas/v4"},
            clear=True,
        ):
            self.assertEqual("https://zai.example.test/api/paas/v4", _answer_provider_base_url("zai"))

    def test_zai_answer_generation_disables_provider_thinking(self):
        captured: dict = {}

        class FakeCompletions:
            def create(self, **kwargs):
                captured.update(kwargs)
                message = types.SimpleNamespace(content="42")
                choice = types.SimpleNamespace(message=message)
                return types.SimpleNamespace(choices=[choice], usage=None)

        class FakeOpenAI:
            def __init__(self, **kwargs):
                self.chat = types.SimpleNamespace(completions=FakeCompletions())

        fake_openai = types.SimpleNamespace(OpenAI=FakeOpenAI)
        with patch.dict(sys.modules, {"openai": fake_openai}):
            response, _tokens = _generate_answer(
                [{"role": "user", "content": "Question: What is revenue?"}],
                AnswerHarnessConfig(
                    run_id="zai-thinking",
                    llm_provider="zai",
                    llm_model="glm-5.2",
                    llm_api_key="fake-key",
                    llm_base_url="https://zai.example.test/api/coding/paas/v4",
                ),
            )

        self.assertEqual("42", response)
        self.assertEqual({"thinking": {"type": "disabled"}}, captured["extra_body"])

    def test_non_zai_answer_generation_does_not_send_provider_thinking(self):
        captured: dict = {}

        class FakeCompletions:
            def create(self, **kwargs):
                captured.update(kwargs)
                message = types.SimpleNamespace(content="42")
                choice = types.SimpleNamespace(message=message)
                return types.SimpleNamespace(choices=[choice], usage=None)

        class FakeOpenAI:
            def __init__(self, **kwargs):
                self.chat = types.SimpleNamespace(completions=FakeCompletions())

        fake_openai = types.SimpleNamespace(OpenAI=FakeOpenAI)
        with patch.dict(sys.modules, {"openai": fake_openai}):
            _generate_answer(
                [{"role": "user", "content": "Question: What is revenue?"}],
                AnswerHarnessConfig(
                    run_id="generic-thinking",
                    llm_provider="openai",
                    llm_model="generic",
                    llm_api_key="fake-key",
                    llm_base_url="https://example.test/v1",
                ),
            )

        self.assertNotIn("extra_body", captured)

    def test_answer_provenance_loads_dataset_and_split_hashes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dataset_manifest = root / "dataset.json"
            split_manifest = root / "split.json"
            dataset_manifest.write_text(json.dumps({"datasetHash": "sha256:dataset"}), encoding="utf-8")
            split_manifest.write_text(
                json.dumps({"splits": {"calibration": {"groupHash": "sha256:cal"}}}),
                encoding="utf-8",
            )
            dataset_hash, split_hashes = run_eval._answer_provenance(dataset_manifest, split_manifest)
            self.assertEqual("sha256:dataset", dataset_hash)
            self.assertEqual({"calibration": "sha256:cal"}, split_hashes)

    def test_answer_provenance_requires_both_manifests(self):
        with self.assertRaisesRegex(ValueError, "provided together"):
            run_eval._answer_provenance(Path("dataset.json"), None)

    def test_doc_ingestion_answer_cli_wires_llm_generation_limits(self):
        captured: dict[str, AnswerHarnessConfig] = {}

        def fake_harness(*, rows, output_root, config):
            captured["config"] = config
            return output_root / config.run_id

        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(run_eval, "run_answer_harness", side_effect=fake_harness):
                status = run_eval.main(
                    [
                        "doc-ingestion-answer",
                        "--output-root",
                        str(Path(tmp) / "out"),
                        "--run-id",
                        "cli-answer-limits",
                        "--llm-provider",
                        "llamacpp",
                        "--llm-model",
                        "qwen3.5-9b-ragas",
                        "--llm-base-url",
                        "http://127.0.0.1:8080/v1",
                        "--llm-temperature",
                        "0.1",
                        "--llm-max-tokens",
                        "384",
                    ]
                )

        self.assertEqual(0, status)
        self.assertEqual("llamacpp", captured["config"].llm_provider)
        self.assertEqual("http://127.0.0.1:8080/v1", captured["config"].llm_base_url)
        self.assertEqual(0.1, captured["config"].llm_temperature)
        self.assertEqual(384, captured["config"].llm_max_tokens)
        self.assertEqual((FULL_RAG,), captured["config"].control_modes)

    def test_doc_ingestion_answer_cli_defaults_to_shared_zhipu_config(self):
        captured: dict[str, AnswerHarnessConfig] = {}

        def fake_harness(*, rows, output_root, config):
            captured["config"] = config
            return output_root / config.run_id

        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(
                os.environ,
                {
                    "CHATAGENT_ZHIPUAI_API_KEY": "fake-zhipu-key",
                    "CHATAGENT_ZHIPUAI_BASE_URL": "https://example.test/api/paas/v4",
                    "CHATAGENT_ZHIPUAI_MODEL": "glm-4.5-air",
                },
                clear=True,
            ):
                with patch.object(run_eval, "run_answer_harness", side_effect=fake_harness):
                    status = run_eval.main(
                        [
                            "doc-ingestion-answer",
                            "--output-root",
                            str(Path(tmp) / "out"),
                            "--run-id",
                            "cli-answer-zhipu-defaults",
                        ]
                    )

        self.assertEqual(0, status)
        self.assertEqual("zhipu", captured["config"].llm_provider)
        self.assertEqual("glm-4.5-air", captured["config"].llm_model)
        self.assertEqual("https://example.test/api/paas/v4", captured["config"].llm_base_url)
        self.assertEqual((FULL_RAG,), captured["config"].control_modes)

    def test_doc_ingestion_answer_cli_defaults_to_zai_when_zhipu_keys_absent(self):
        captured: dict[str, AnswerHarnessConfig] = {}

        def fake_harness(*, rows, output_root, config):
            captured["config"] = config
            return output_root / config.run_id

        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(
                os.environ,
                {
                    "CHATAGENT_ZAI_CODING_API_KEY": "fake-zai-key",
                    "CHATAGENT_ZAI_CODING_BASE_URL": "https://zai.example.test/api/paas/v4",
                    "CHATAGENT_ZAI_CODING_MODEL": "glm-4.5-air",
                },
                clear=True,
            ):
                with patch.object(run_eval, "run_answer_harness", side_effect=fake_harness):
                    status = run_eval.main(
                        [
                            "doc-ingestion-answer",
                            "--output-root",
                            str(Path(tmp) / "out"),
                            "--run-id",
                            "cli-answer-zai-defaults",
                        ]
                    )

        self.assertEqual(0, status)
        self.assertEqual("zai", captured["config"].llm_provider)
        self.assertEqual("glm-4.5-air", captured["config"].llm_model)
        self.assertEqual("https://zai.example.test/api/paas/v4", captured["config"].llm_base_url)


if __name__ == "__main__":
    unittest.main()
