package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.CurrentTurnKnowledgeHitHolder;
import com.yulong.chatagent.agent.runtime.contract.ContractTestSupport;
import com.yulong.chatagent.agent.runtime.contract.RetrievalSource;
import com.yulong.chatagent.rag.model.RetrievalExecutionOutcome;
import com.yulong.chatagent.rag.model.RetrievalExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunResultTest {

    @AfterEach
    void clear() {
        CurrentTurnKnowledgeHitHolder.clear();
    }

    @Test
    void shouldCarryTypedRetrievalSnapshotAndKeepFourArgumentConstructor() {
        CurrentTurnKnowledgeHitHolder.reset();
        var contract = ContractTestSupport.contractBuilder().build(
                null, "uploaded report", "uploaded report", AgentExecutionMode.REACT);
        CurrentTurnKnowledgeHitHolder.recordRetrievalResult(
                RetrievalExecutionResult.noHit(
                        RetrievalSource.SESSION_FILES,
                        List.of(RetrievalSource.SESSION_FILES), false, List.of()),
                contract);

        AgentRunResult result = AgentRunResult.success(12L, false);
        AgentRunResult legacy = new AgentRunResult(
                AgentRunResult.Status.SUCCESS, 1L, null, true);

        assertThat(result.retrieval()).isNotNull();
        assertThat(result.retrieval().retrievalOutcomeDetail())
                .isEqualTo(RetrievalExecutionOutcome.NO_HIT);
        assertThat(legacy.retrieval()).isNull();
    }

    @Test
    void partialAndBlockedAreDistinctFromSuccessAndError() {
        // PARTIAL/BLOCKED 是 ARRB Phase 1 新增的真实运行结果：不能被聚合成 SUCCESS，也不能算作 ERROR。
        AgentRunResult partial = AgentRunResult.partial(10L, true, "TOOL_BUDGET_EXHAUSTED");
        AgentRunResult blocked = AgentRunResult.blocked(11L, true, "CONFIRMATION_REQUIRED");

        assertThat(partial.status()).isEqualTo(AgentRunResult.Status.PARTIAL);
        assertThat(partial.errorType()).isEqualTo("TOOL_BUDGET_EXHAUSTED");
        assertThat(blocked.status()).isEqualTo(AgentRunResult.Status.BLOCKED);
        assertThat(blocked.errorType()).isEqualTo("CONFIRMATION_REQUIRED");

        // 现有 success/error 路径保持不变。
        assertThat(AgentRunResult.success(1L, true).status()).isEqualTo(AgentRunResult.Status.SUCCESS);
        assertThat(AgentRunResult.failure(1L, true, new RuntimeException("boom")).status())
                .isEqualTo(AgentRunResult.Status.ERROR);
        // 四种状态互不相等，确保指标/最终流式不会把 PARTIAL/BLOCKED 合并成 SUCCESS。
        assertThat(AgentRunResult.Status.values())
                .containsExactlyInAnyOrder(
                        AgentRunResult.Status.SUCCESS,
                        AgentRunResult.Status.PARTIAL,
                        AgentRunResult.Status.BLOCKED,
                        AgentRunResult.Status.ERROR);
    }
}
