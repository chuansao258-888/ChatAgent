package com.yulong.chatagent.agent.runtime.contract;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 unit tests for {@link TurnExecutionContractBuilder}.
 *
 * <p>They assert the conservative derivation from the resolved intent and that
 * the contract is present and internally consistent for each turn kind. These
 * tests would fail if the builder regressed the intent->contract mapping.</p>
 */
class TurnExecutionContractBuilderTest {

    private final TurnExecutionContractBuilder builder = new TurnExecutionContractBuilder();

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

    private IntentResolution kbResolution() {
        return new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("leaf").name("年假").build()),
                List.of("kb-1"),
                ScopePolicy.STRICT,
                List.of(),
                null
        );
    }
}
