package com.yulong.chatagent.agent;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptPolicyTest {

    private final PromptLoader promptLoader = TestPromptLoader.create();

    @Test
    void ordinaryModePromptsShouldKeepStatusCorrectionsFieldScoped() {
        String decisionPrompt = promptLoader.load(PromptConstants.AGENT_DECISION_MODULE);
        String finalAnswerPrompt = promptLoader.load(PromptConstants.AGENT_FINAL_ANSWER);

        assertThat(decisionPrompt)
                .contains("status correction as field-scoped")
                .contains("preserve unrelated pending")
                .contains("Do not infer completion")
                .contains("a hold")
                .contains("a stop instruction");
        assertThat(finalAnswerPrompt)
                .contains("status correction as field-scoped")
                .contains("Preserve every unrelated pending")
                .contains("another item completed")
                .contains("was put on hold")
                .contains("received a stop instruction");
    }

    @Test
    void ordinaryModePromptsShouldTreatExplicitTopicCorrectionsAsHardBoundaries() {
        String decisionPrompt = promptLoader.load(PromptConstants.AGENT_DECISION_MODULE);
        String finalAnswerPrompt = promptLoader.load(PromptConstants.AGENT_FINAL_ANSWER);

        assertThat(decisionPrompt)
                .contains("I meant X, not Y")
                .contains("hard topic boundary")
                .contains("exclude Y's projects");
        assertThat(finalAnswerPrompt)
                .contains("I meant X, not Y")
                .contains("Answer X as a standalone request")
                .contains("omit Y's projects");
    }

    @Test
    void ordinaryModePromptsShouldRespectKnowledgeSourcePriority() {
        String decisionPrompt = promptLoader.load(PromptConstants.AGENT_DECISION_MODULE);
        String finalAnswerPrompt = promptLoader.load(PromptConstants.AGENT_FINAL_ANSWER);
        String toolStrategyPrompt = promptLoader.load(PromptConstants.AGENT_TOOL_STRATEGY);
        String mcpSafetyPrompt = promptLoader.load(PromptConstants.AGENT_MCP_TOOL_SAFETY);
        String webSearchSafetyPrompt = promptLoader.load(PromptConstants.AGENT_WEB_SEARCH_SAFETY);

        assertThat(decisionPrompt)
                .contains("attached session files")
                .contains("scoped/bound knowledge bases")
                .contains("web-search-capable tool or MCP search")
                .contains("agent's own knowledge")
                .contains("local retrieval already failed")
                .contains("same attached item")
                .contains("current-turn evidence and citations");
        assertThat(finalAnswerPrompt)
                .contains("session uploads and bound/scoped KB")
                .contains("evidence first")
                .contains("web-search-capable tool or MCP search evidence second")
                .contains("model knowledge last")
                .contains("state the limitation")
                .contains("guessing");
        assertThat(toolStrategyPrompt)
                .contains("first session uploads and bound/scoped knowledge")
                .contains("then a web-search-capable tool or MCP search tool")
                .contains("last, the model's")
                .contains("own knowledge")
                .contains("file-backed answer")
                .contains("fresh source cards");
        assertThat(mcpSafetyPrompt)
                .contains("MCP search tools sit after local session/KB retrieval")
                .contains("before model knowledge");
        assertThat(webSearchSafetyPrompt)
                .contains("local session/KB retrieval has no matching evidence")
                .contains("before relying on model knowledge");
    }

    @Test
    void ordinaryModePromptsShouldKeepCurrentContextBoundaryWhenRetrievalFindsOtherEntity() {
        String decisionPrompt = promptLoader.load(PromptConstants.AGENT_DECISION_MODULE);
        String finalAnswerPrompt = promptLoader.load(PromptConstants.AGENT_FINAL_ANSWER);

        assertThat(decisionPrompt)
                .contains("this handoff")
                .contains("hard constraint")
                .contains("Evidence for a different")
                .contains("project, document, person, or workflow")
                .contains("do not answer \"yes\" merely because an X source exists")
                .contains("no current-context evidence links X")
                .contains("to this handoff")
                .contains("quote approval codes, markers, contacts, risks");
        assertThat(finalAnswerPrompt)
                .contains("Preserve current-context boundaries")
                .contains("facts from a")
                .contains("different project, document, person, or workflow")
                .contains("retrieved source only proves that the other project exists")
                .contains("do not answer \"yes\"")
                .contains("current handoff summary")
                .contains("answer only the inclusion or")
                .contains("quote out-of-scope");
    }
}
