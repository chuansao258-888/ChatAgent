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
}
