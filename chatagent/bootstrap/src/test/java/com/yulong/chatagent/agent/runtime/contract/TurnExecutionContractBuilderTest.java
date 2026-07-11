package com.yulong.chatagent.agent.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.application.ConfidenceStatus;
import com.yulong.chatagent.intent.application.IntentCandidateEvidence;
import com.yulong.chatagent.intent.application.IntentDecision;
import com.yulong.chatagent.intent.application.IntentDecisionSource;
import com.yulong.chatagent.intent.application.IntentRouteOutcome;
import com.yulong.chatagent.intent.application.IntentUnderstandingResult;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract construction tests across the implemented turn-contract phases.
 *
 * <p>They assert the conservative derivation from the resolved intent and that
 * the contract is present and internally consistent for each turn kind. These
 * tests would fail if the builder regressed the intent->contract mapping.</p>
 */
class TurnExecutionContractBuilderTest {

    private final TurnExecutionContractBuilder builder = ContractTestSupport.contractBuilder();

    @Test
    void shouldRequireRetrievalAndCitationForKbIntentWithScopedKbs() {
        IntentResolution resolution = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("leaf").name("年假").build()),
                List.of("kb-1"),
                ScopePolicy.STRICT,
                List.of(),
                null
        );

        TurnExecutionContract contract = builder.build(resolution, "年假怎么申请？", "年假 申请 流程", AgentExecutionMode.REACT);

        assertThat(contract.version()).isEqualTo(TurnExecutionContract.VERSION);
        assertThat(contract.analysis().primaryIntent()).isEqualTo(IntentLabel.KB_QA);
        assertThat(contract.analysis().sourceNeed()).isEqualTo(SourceNeed.KB);
        assertThat(contract.retrieval().mode()).isEqualTo(RetrievalMode.REQUIRED_BEFORE_ANSWER);
        assertThat(contract.retrieval().source()).isEqualTo(RetrievalSource.INTENT_KB);
        assertThat(contract.retrieval().scopedKbIds()).containsExactly("kb-1");
        assertThat(contract.retrieval().fallbackPolicy()).isEqualTo(RetrievalFallbackPolicy.NONE);
        assertThat(contract.retrieval().citationRequired()).isTrue();
        assertThat(contract.answer().citationRequired()).isTrue();
        assertThat(contract.queryPlan().mode()).isEqualTo(QueryPlanMode.SINGLE_QUERY);
        assertThat(contract.queryPlan().queries()).hasSize(1);
        assertThat(contract.queryPlan().queries().get(0).source()).isEqualTo(RetrievalSource.INTENT_KB);
        assertThat(contract.intent().kind()).isEqualTo(IntentKind.KB);
        assertThat(contract.intent().label()).isEqualTo(IntentLabel.KB_QA);
        assertThat(contract.ordering()).isEqualTo(OrderingPolicy.SESSION_SERIAL);
        assertThat(contract.executionMode()).isEqualTo(AgentExecutionMode.REACT);
    }

    @Test
    void shouldUseFallbackPolicyForKbIntentWithFallbackAllowedScope() {
        IntentResolution resolution = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("leaf").name("报销").build()),
                List.of(),
                ScopePolicy.FALLBACK_ALLOWED,
                List.of(),
                null
        );

        TurnExecutionContract contract = builder.build(resolution, "报销流程", "报销 流程", null);

        // Empty scoped KBs with FALLBACK_ALLOWED points at agent default KBs with AGENT_DEFAULT_KB fallback.
        assertThat(contract.retrieval().source()).isEqualTo(RetrievalSource.AGENT_DEFAULT_KB);
        assertThat(contract.retrieval().fallbackPolicy()).isEqualTo(RetrievalFallbackPolicy.AGENT_DEFAULT_KB);
        // executionMode null defaults to REACT.
        assertThat(contract.executionMode()).isEqualTo(AgentExecutionMode.REACT);
    }

    @Test
    void shouldDisableRetrievalForToolIntent() {
        IntentResolution resolution = new IntentResolution(
                IntentKind.TOOL,
                List.of(IntentNodeDTO.builder().id("leaf").name("查天气").build()),
                List.of(),
                ScopePolicy.STRICT,
                List.of("webSearchTool"),
                null
        );

        TurnExecutionContract contract = builder.build(resolution, "今天天气怎么样", "今天 天气", AgentExecutionMode.REACT);

        assertThat(contract.analysis().primaryIntent()).isEqualTo(IntentLabel.ACTION_REQUEST);
        assertThat(contract.analysis().toolNeed()).isEqualTo(ToolNeed.REQUIRED);
        assertThat(contract.retrieval().mode()).isEqualTo(RetrievalMode.DISABLED);
        assertThat(contract.queryPlan().mode()).isEqualTo(QueryPlanMode.NONE);
        assertThat(contract.tools().allowedTools()).containsExactly("webSearchTool");
    }

    @Test
    void shouldProduceGeneralChatContractForPassthroughTurnWithoutResolution() {
        TurnExecutionContract contract = builder.build(null, "你好", null, AgentExecutionMode.REACT);

        assertThat(contract.analysis().primaryIntent()).isEqualTo(IntentLabel.GENERAL_CHAT);
        assertThat(contract.analysis().sourceNeed()).isEqualTo(SourceNeed.NONE);
        assertThat(contract.analysis().confidence()).isEqualTo(0.3d);
        assertThat(contract.retrieval().mode()).isEqualTo(RetrievalMode.DISABLED);
        assertThat(contract.queryPlan().mode()).isEqualTo(QueryPlanMode.NONE);
        assertThat(contract.intent().kind()).isNull();
        assertThat(contract.intent().resolution()).isNull();
        assertThat(contract.memory().recallEnabled()).isTrue();
    }

    @Test
    void shouldPreserveOriginalUserTextForAuditingAndFuturePreservationChecks() {
        IntentResolution resolution = kbResolution();

        TurnExecutionContract contract = builder.build(resolution, "年假怎么申请？", "年假 申请", AgentExecutionMode.REACT);

        assertThat(contract.analysis().originalUserText()).isEqualTo("年假怎么申请？");
    }

    @Test
    void shouldClassifyMixedSourceWhenKbIntentAlsoNamesUploadedFile() {
        // Phase 2 acceptance: a mixed KB+file request should produce distinct source-specific query specs.
        IntentResolution resolution = kbResolution();
        String original = "Compare the policy with my uploaded spreadsheet report.xlsx.";

        TurnExecutionContract contract = builder.build(resolution, original, "policy comparison", AgentExecutionMode.REACT);

        assertThat(contract.analysis().sourceNeed()).isEqualTo(SourceNeed.MIXED);
        // Phase 2 emits MULTI_QUERY (not DECOMPOSED) with distinct per-source query texts.
        assertThat(contract.queryPlan().mode()).isEqualTo(QueryPlanMode.MULTI_QUERY);
        assertThat(contract.queryPlan().operation()).isEqualTo(QueryOperation.COMPARE);
        assertThat(contract.queryPlan().queries()).hasSize(2);
        assertThat(contract.queryPlan().queries()).extracting(QuerySpec::source)
                .contains(RetrievalSource.INTENT_KB, RetrievalSource.SESSION_FILES);
        // The two query texts must be distinct (P2 finding: no label-only decomposition).
        assertThat(contract.queryPlan().queries().get(0).text())
                .isNotEqualTo(contract.queryPlan().queries().get(1).text());
    }

    @Test
    void shouldDeriveWebSourceAndCurrentTimeSensitivityForLatestRequest() {
        // ATC-PLAN-F01: latest/current must reach WEB + CURRENT through the public builder entry.
        // WEB turns require the web-search tool, do NOT use RAG, and map to WEB_SEARCH source.
        TurnExecutionContract contract = builder.build(null, "What is the latest news on the product?", null, AgentExecutionMode.REACT);

        assertThat(contract.analysis().sourceNeed()).isEqualTo(SourceNeed.WEB);
        assertThat(contract.analysis().timeSensitivity()).isEqualTo(TimeSensitivity.CURRENT);
        assertThat(contract.analysis().toolNeed()).isEqualTo(ToolNeed.REQUIRED);
        assertThat(contract.queryPlan().mode()).isEqualTo(QueryPlanMode.SINGLE_QUERY);
        assertThat(contract.queryPlan().queries().get(0).source()).isEqualTo(RetrievalSource.WEB_SEARCH);
        // WEB does NOT use RAG retrieval.
        assertThat(contract.retrieval().mode()).isEqualTo(RetrievalMode.DISABLED);
        assertThat(contract.tools().retrievalVisible()).isFalse();
    }

    @Test
    void shouldDeriveWebSourceForChineseCurrentnessRequest() {
        // P2 regression: Chinese currentness through the public builder entry.
        TurnExecutionContract contract = builder.build(null, "今天的价格是多少？", null, AgentExecutionMode.REACT);

        assertThat(contract.analysis().sourceNeed()).isEqualTo(SourceNeed.WEB);
        assertThat(contract.analysis().timeSensitivity()).isEqualTo(TimeSensitivity.CURRENT);
    }

    @Test
    void shouldNotMisclassifyBareFileWordAsSessionFile() {
        // P2 regression: "file a tax return" / "excel at Java" must NOT be FILE.
        TurnExecutionContract contract = builder.build(null, "How do I file a tax return?", null, AgentExecutionMode.REACT);

        assertThat(contract.analysis().sourceNeed()).isNotEqualTo(SourceNeed.FILE);
    }

    @Test
    void shouldCarryIndependentOrderedMultiIntentRoutesIntoContract() throws Exception {
        IntentUnderstandingResult understanding = multiIntentUnderstanding();
        IntentResolution leave = kbResolution("leave", "kb-leave", ScopePolicy.STRICT);
        IntentResolution expense = kbResolution(
                "expense", "kb-expense", ScopePolicy.FALLBACK_ALLOWED);

        TurnExecutionContract contract = builder.buildForRoutes(
                List.of(leave, expense), "leave and expense", "leave and expense",
                AgentExecutionMode.REACT, understanding);

        assertThat(contract.analysis().intentDecision()).isEqualTo(understanding.decision());
        assertThat(contract.analysis().primaryIntent()).isEqualTo(IntentLabel.MULTI_INTENT);
        assertThat(contract.analysis().secondaryIntents()).contains(IntentLabel.MULTI_INTENT);
        assertThat(contract.analysis().confidence()).isEqualTo(0.94d);
        assertThat(contract.queryPlan().mode()).isEqualTo(QueryPlanMode.MULTI_QUERY);
        assertThat(contract.queryPlan().queries()).extracting(QuerySpec::text)
                .containsExactly("leave and expense", "leave and expense");

        // Legacy scalar fields remain primary-only and never contain a union.
        assertThat(contract.retrieval().scopedKbIds()).containsExactly("kb-leave");
        assertThat(contract.retrieval().fallbackPolicy()).isEqualTo(RetrievalFallbackPolicy.NONE);
        assertThat(contract.retrieval().routes()).hasSize(2);
        RetrievalRoutePlan leaveRoute = contract.retrieval().routes().get(0);
        RetrievalRoutePlan expenseRoute = contract.retrieval().routes().get(1);
        assertThat(leaveRoute.key()).isEqualTo("q0");
        assertThat(leaveRoute.queryIndex()).isZero();
        assertThat(leaveRoute.intentNodeId()).isEqualTo("leave");
        assertThat(leaveRoute.source()).isEqualTo(RetrievalSource.INTENT_KB);
        assertThat(leaveRoute.scopedKbIds()).containsExactly("kb-leave");
        assertThat(leaveRoute.fallbackPolicy()).isEqualTo(RetrievalFallbackPolicy.NONE);
        assertThat(expenseRoute.key()).isEqualTo("q1");
        assertThat(expenseRoute.queryIndex()).isEqualTo(1);
        assertThat(expenseRoute.intentNodeId()).isEqualTo("expense");
        assertThat(expenseRoute.source()).isEqualTo(RetrievalSource.INTENT_KB);
        assertThat(expenseRoute.scopedKbIds()).containsExactly("kb-expense");
        assertThat(expenseRoute.fallbackPolicy())
                .isEqualTo(RetrievalFallbackPolicy.AGENT_DEFAULT_KB);

        ObjectMapper objectMapper = new ObjectMapper();
        RetrievalPlan restored = objectMapper.readValue(
                objectMapper.writeValueAsString(contract.retrieval()), RetrievalPlan.class);
        assertThat(restored).isEqualTo(contract.retrieval());
    }

    @Test
    void shouldRejectLegacySingleResolutionBuildForMultiIntent() {
        assertThatThrownBy(() -> builder.build(
                kbResolution("leave", "kb-leave", ScopePolicy.STRICT),
                "leave and expense",
                "leave and expense",
                AgentExecutionMode.REACT,
                multiIntentUnderstanding()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MULTI_INTENT resolutions");
    }

    @Test
    void shouldDeserializeLegacyRetrievalPlanWithoutRoutes() throws Exception {
        RetrievalPlan restored = new ObjectMapper().readValue("""
                {
                  "mode": "REQUIRED_BEFORE_ANSWER",
                  "source": "INTENT_KB",
                  "scopedKbIds": ["kb-legacy"],
                  "fallbackPolicy": "NONE",
                  "citationRequired": true
                }
                """, RetrievalPlan.class);

        assertThat(restored.scopedKbIds()).containsExactly("kb-legacy");
        assertThat(restored.routes()).isEmpty();
    }

    @Test
    void shouldNotEscapeToDefaultKbWhenStrictScopeIsEmpty() {
        // ATC-P03-F01: strict empty scope remains required but cannot escape to defaults.
        IntentResolution strictEmpty = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("leaf").name("policy").build()),
                List.of(), // empty scope
                ScopePolicy.STRICT,
                List.of(),
                null
        );
        TurnExecutionContract contract = builder.build(strictEmpty, "policy question", "policy", AgentExecutionMode.REACT);
        assertThat(contract.retrieval().mode()).isEqualTo(RetrievalMode.REQUIRED_BEFORE_ANSWER);
        assertThat(contract.retrieval().source()).isEqualTo(RetrievalSource.INTENT_KB);
        assertThat(contract.retrieval().scopedKbIds()).isEmpty();
        assertThat(contract.retrieval().fallbackPolicy()).isEqualTo(RetrievalFallbackPolicy.NONE);
    }

    @Test
    void shouldFallbackToDefaultKbWhenFallbackAllowedScopeIsEmpty() {
        // ATC-P03-F01: FALLBACK_ALLOWED + empty scope still falls back legitimately.
        IntentResolution fallbackEmpty = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("leaf").name("policy").build()),
                List.of(),
                ScopePolicy.FALLBACK_ALLOWED,
                List.of(),
                null
        );
        TurnExecutionContract contract = builder.build(fallbackEmpty, "policy question", "policy", AgentExecutionMode.REACT);
        assertThat(contract.retrieval().source()).isEqualTo(RetrievalSource.AGENT_DEFAULT_KB);
        assertThat(contract.retrieval().fallbackPolicy()).isEqualTo(RetrievalFallbackPolicy.AGENT_DEFAULT_KB);
    }

    @Test
    void shouldRemoveBusinessKbRetrievalFromGeneralOutcome() {
        IntentUnderstandingResult understanding = understanding(
                IntentRouteOutcome.GENERAL_CHAT, SourceNeed.KB);

        TurnExecutionContract contract = builder.build(
                null, "What is a knowledge base?", "knowledge base",
                AgentExecutionMode.REACT, understanding);

        assertThat(contract.analysis().sourceNeed()).isEqualTo(SourceNeed.KB);
        assertThat(contract.queryPlan().mode()).isEqualTo(QueryPlanMode.NONE);
        assertThat(contract.retrieval().mode()).isEqualTo(RetrievalMode.DISABLED);
        assertThat(contract.tools().retrievalVisible()).isFalse();
    }

    @Test
    void shouldKeepOnlySessionFileRetrievalForOutOfDomainMixedOutcome() {
        IntentUnderstandingResult understanding = understanding(
                IntentRouteOutcome.OUT_OF_DOMAIN, SourceNeed.MIXED);

        TurnExecutionContract contract = builder.build(
                null,
                "Compare the knowledge base with my uploaded report.pdf",
                "uploaded report.pdf",
                AgentExecutionMode.REACT,
                understanding);

        assertThat(contract.queryPlan().queries())
                .extracting(QuerySpec::source)
                .containsExactly(RetrievalSource.SESSION_FILES);
        assertThat(contract.retrieval().source()).isEqualTo(RetrievalSource.SESSION_FILES);
        assertThat(contract.tools().retrievalVisible()).isTrue();
    }

    private IntentUnderstandingResult understanding(IntentRouteOutcome outcome, SourceNeed sourceNeed) {
        IntentDecision decision = new IntentDecision(
                outcome,
                null,
                List.of(),
                List.of(),
                List.of(),
                IntentDecisionSource.DETERMINISTIC,
                0.95d,
                ConfidenceStatus.CALIBRATED,
                "v1",
                List.of("test"));
        return new IntentUnderstandingResult(
                decision,
                sourceNeed,
                TimeSensitivity.STATIC,
                ActionRisk.READ_ONLY,
                List.of(),
                false,
                false);
    }

    private IntentResolution kbResolution() {
        return kbResolution("leaf", "kb-1", ScopePolicy.STRICT);
    }

    private IntentResolution kbResolution(String nodeId, String kbId, ScopePolicy scopePolicy) {
        return new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id(nodeId).name(nodeId).build()),
                List.of(kbId),
                scopePolicy,
                List.of(),
                null
        );
    }

    private IntentUnderstandingResult multiIntentUnderstanding() {
        IntentDecision decision = new IntentDecision(
                IntentRouteOutcome.MULTI_INTENT,
                "leave",
                List.of("expense"),
                List.of(
                        new IntentCandidateEvidence("leave", "HR > Leave", 1.5d, 0.2d, 1,
                                List.of("semantic_match")),
                        new IntentCandidateEvidence("expense", "Finance > Expense", 1.3d, 1.3d, 2,
                                List.of("semantic_match"))),
                List.of(),
                IntentDecisionSource.CLASSIFIER,
                0.94d,
                ConfidenceStatus.CALIBRATED,
                "v1",
                List.of("classifier_schema_valid"));
        return new IntentUnderstandingResult(
                decision, SourceNeed.KB, TimeSensitivity.STATIC, ActionRisk.READ_ONLY,
                List.of(IntentLabel.MULTI_INTENT), true, false);
    }
}
