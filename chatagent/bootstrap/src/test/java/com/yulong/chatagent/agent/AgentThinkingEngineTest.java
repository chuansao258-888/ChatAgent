package com.yulong.chatagent.agent;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentThinkingEngineTest {

    private static final String SESSION_ID = "session-1";
    private static final String TURN_ID = "turn-1";

    @Mock
    private LLMService llmService;

    @Mock
    private AgentMessageBridge messageBridge;

    @Mock
    private ChatMemory chatMemory;

    @Test
    void shouldStreamFinalAnswerDirectlyWhenNoToolsAreAvailable() {
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage("hello")));
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), anyBoolean()))
                .thenReturn("direct final answer");

        AgentThinkingEngine engine = engineWithTools(List.of());

        ChatResponse response = engine.think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText()).isEqualTo("direct final answer");
        assertThat(response.getResult().getOutput().getToolCalls()).isEmpty();
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(messageBridge).streamFinalResponse(
                eq(SESSION_ID), eq(TURN_ID), promptCaptor.capture(), same(llmService), anyBoolean());
        assertThat(promptCaptor.getValue().getInstructions().get(0).getText())
                .contains("Current user request: hello")
                .contains("The current user request is authoritative")
                .contains("Treat the latest user-role message in conversation history as the current")
                .contains("never claim that the user has not")
                .contains("sent a message")
                .contains("provide their first question")
                .contains("Preserve its language")
                .contains("choose the memory that matches the latest")
                .contains("Do not reuse an answer to an earlier question")
                .contains("Use retrieved evidence only when it directly matches")
                .contains("Ignore unrelated retrieval results")
                .contains("Complete the latest user task directly")
                .contains("Do not introduce ChatAgent")
                .contains("I'm ready to help")
                .contains("invalid when the latest user asked a task")
                .contains("If the latest request is self-contained or changes topic")
                .contains("Do not mention or personalize with names, projects")
                .contains("request explicitly refers back to them")
                .contains("generic advice, a reusable template")
                .contains("Never copy prior internal identifiers")
                .contains("rather than repeating a response to the previous question");
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldPersistAndPublishToolDecisionWhenModelReturnsToolCalls() {
        ToolCallback tool = namedTool("localTool");
        List<AssistantMessage.ToolCall> toolCalls = List.of(new AssistantMessage.ToolCall(
                "tool-call-1",
                "function",
                "localTool",
                "{\"query\":\"budget\"}"
        ));
        AssistantMessage output = mock(AssistantMessage.class);
        when(output.getToolCalls()).thenReturn(toolCalls);
        BufferedStreamingResponse decision = new BufferedStreamingResponse(response(output), List.of());
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage("search my files")));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                contains("Decision Module"),
                eq(List.of(tool)),
                same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false),
                isNull(),
                isNull()
        )).thenReturn(decision);

        ChatResponse response = engineWithTools(List.of(tool)).think(chatMemory, SESSION_ID);

        // ARRB Phase 1: assistant 工具调用消息现在经过共享 ToolCallPreflight 规范化后再持久化，
        // 因此落库的是规范化重建消息（稳定 id、参数上限），而不是原始 mock 对象本身。
        // 这里验证规范化后保留了原始的 tool call 内容。
        ArgumentCaptor<AssistantMessage> captured = ArgumentCaptor.forClass(AssistantMessage.class);
        verify(messageBridge).persistAndPublish(eq(SESSION_ID), eq(TURN_ID), captured.capture());
        AssistantMessage persisted = captured.getValue();
        assertThat(persisted.getToolCalls()).hasSize(1);
        assertThat(persisted.getToolCalls().get(0).id()).isEqualTo("tool-call-1");
        assertThat(persisted.getToolCalls().get(0).name()).isEqualTo("localTool");
        assertThat(persisted.getToolCalls().get(0).arguments()).isEqualTo("{\"query\":\"budget\"}");
        assertThat(response.getResult().getOutput().getToolCalls()).hasSize(1);
        verify(messageBridge, never()).streamFinalResponse(any(), any(), any(Prompt.class), same(llmService), anyBoolean());
    }

    @Test
    void shouldPassKnowledgeSourcePriorityIntoDecisionPromptWhenLocalAndWebToolsAreAvailable() {
        ToolCallback sessionFileTool = mock(ToolCallback.class);
        ToolCallback webSearchTool = mock(ToolCallback.class);
        String question = "If the release status is not in my notes, check the latest public status.";
        AssistantMessage directDecision = new AssistantMessage("Check local notes first, then use web search if needed.");
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                contains("Decision Module"),
                eq(List.of(sessionFileTool, webSearchTool)),
                same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false),
                isNull(),
                isNull()
        )).thenReturn(new BufferedStreamingResponse(response(directDecision), List.of()));
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), eq(false)))
                .thenReturn("I will check the local notes first, then public status if the notes do not cover it.");

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool, webSearchTool),
                "Attached session files: release-notes.md; Bound knowledge bases: Release Desk"
        ).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText())
                .contains("local notes first");
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageBridge).collectDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                promptCaptor.capture(),
                systemPromptCaptor.capture(),
                eq(List.of(sessionFileTool, webSearchTool)),
                same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false),
                isNull(),
                isNull()
        );
        assertThat(promptCaptor.getValue().getInstructions().get(0).getText())
                .contains(question);
        assertThat(systemPromptCaptor.getValue())
                .contains("Current user request: " + question)
                .contains("attached session files and")
                .contains("scoped/bound knowledge bases")
                .contains("web-search-capable tool or MCP search")
                .contains("agent's own knowledge")
                .contains("local retrieval already failed")
                .contains("Web search has priority over model knowledge");
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldSuppressUnsupportedSessionFileSearchForSelfContainedPlanningPrompt() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "I'm coordinating the Ridgewater partner rehearsal. Ada owns the follow-up, "
                + "and the first room was Atlas Hall. Give me a short prep checklist in English.";
        AssistantMessage mistakenToolDecision = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "tool-call-1",
                        "function",
                        "SessionFileSearchTool",
                        "{\"query\":\"Ridgewater partner rehearsal checklist\"}"
                )))
                .build();
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                contains("Decision Module"),
                eq(List.of(sessionFileTool)),
                same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false),
                isNull(),
                isNull()
        )).thenReturn(new BufferedStreamingResponse(response(mistakenToolDecision), List.of()));
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), eq(false)))
                .thenReturn("Confirm Ada's follow-up, review the room note, and align the agenda.");

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Bound knowledge bases: E2E Reference Pack"
        ).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText())
                .isEqualTo("Confirm Ada's follow-up, review the room note, and align the agenda.");
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldSuppressSessionFileSearchForGenericHandoffAdvice() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "Generic question: how do I make any handoff easy to scan?";
        AssistantMessage mistakenToolDecision = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "tool-call-1",
                        "function",
                        "SessionFileSearchTool",
                        "{\"query\":\"handoff easy to scan\"}"
                )))
                .build();
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                contains("Decision Module"),
                eq(List.of(sessionFileTool)),
                same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false),
                isNull(),
                isNull()
        )).thenReturn(new BufferedStreamingResponse(response(mistakenToolDecision), List.of()));
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), eq(false)))
                .thenReturn("Use clear headings, bullets, owners, actions, and deadlines.");

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Bound knowledge bases: Ridgewater Launch Notes"
        ).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText())
                .isEqualTo("Use clear headings, bullets, owners, actions, and deadlines.");
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldSuppressSessionFileSearchForNaturalHandoffClarityAdvice() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "How do I keep the handoff clear?";
        AssistantMessage mistakenToolDecision = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "tool-call-1",
                        "function",
                        "SessionFileSearchTool",
                        "{\"query\":\"handoff clear\"}"
                )))
                .build();
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                contains("Decision Module"),
                eq(List.of(sessionFileTool)),
                same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false),
                isNull(),
                isNull()
        )).thenReturn(new BufferedStreamingResponse(response(mistakenToolDecision), List.of()));
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), eq(false)))
                .thenReturn("Keep the owner, next action, deadline, and current status visible.");

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Bound knowledge bases: Ridgewater Launch Notes"
        ).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText())
                .isEqualTo("Keep the owner, next action, deadline, and current status visible.");
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldClarifyAmbiguousTopicFragmentWithoutCallingModelOrTools() {
        ToolCallback sessionFileTool = mock(ToolCallback.class);
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(
                new UserMessage("Summarize the current Harborlight owner and locker."),
                new AssistantMessage("Harborlight is owned by Priya and uses Vault."),
                new UserMessage("operations")
        ));

        ChatResponse response = engineWithTools(List.of(sessionFileTool)).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText())
                .contains("clarify")
                .contains("operations context")
                .doesNotContain("Ridgewater")
                .doesNotContain("MIXED-A-KB");
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageBridge).persistAndPublish(eq(SESSION_ID), eq(TURN_ID), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) messageCaptor.getValue()).getToolCalls()).isEmpty();
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
        verify(messageBridge, never()).streamFinalResponse(any(), any(), any(Prompt.class), same(llmService), anyBoolean());
    }

    @Test
    void shouldNotLetLegacyShortFragmentGateVetoRequiredRetrieval() {
        ToolCallback retrievalTool = namedTool("SessionFileSearchTool");
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage("operations")));
        var resolution = new com.yulong.chatagent.intent.application.IntentResolution(
                com.yulong.chatagent.intent.model.IntentKind.KB,
                List.of(),
                List.of("kb-operations"),
                com.yulong.chatagent.intent.model.ScopePolicy.STRICT,
                List.of(),
                null);
        var contract = com.yulong.chatagent.agent.runtime.contract.ContractTestSupport.contractBuilder()
                .build(resolution, "operations", "operations",
                        com.yulong.chatagent.agent.runtime.AgentExecutionMode.REACT);

        engineWithTools(List.of(retrievalTool), "", contract).think(chatMemory, SESSION_ID);

        ArgumentCaptor<Message> persisted = ArgumentCaptor.forClass(Message.class);
        verify(messageBridge).persistAndPublish(eq(SESSION_ID), eq(TURN_ID), persisted.capture());
        assertThat(persisted.getValue())
                .isInstanceOfSatisfying(AssistantMessage.class, message ->
                        assertThat(message.getToolCalls())
                                .singleElement()
                                .satisfies(call -> assertThat(call.name())
                                        .isEqualTo("SessionFileSearchTool")));
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldFailBeforeModelWhenRequiredRetrievalCapabilityIsMissing() {
        ToolCallback unrelatedTool = namedTool("localTool");
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage("annual leave")));
        var resolution = new com.yulong.chatagent.intent.application.IntentResolution(
                com.yulong.chatagent.intent.model.IntentKind.KB,
                List.of(),
                List.of("kb-leave"),
                com.yulong.chatagent.intent.model.ScopePolicy.STRICT,
                List.of(),
                null);
        var contract = com.yulong.chatagent.agent.runtime.contract.ContractTestSupport.contractBuilder()
                .build(resolution, "annual leave", "annual leave",
                        com.yulong.chatagent.agent.runtime.AgentExecutionMode.REACT);

        assertThatThrownBy(() -> engineWithTools(List.of(unrelatedTool), "", contract)
                .think(chatMemory, SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SessionFileSearchTool");

        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
        verify(messageBridge, never()).streamFinalResponse(
                any(), any(), any(Prompt.class), same(llmService), anyBoolean());
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldStreamFinalAnswerWhenToolsAreAvailableButDecisionReturnsPureText() {
        ToolCallback tool = mock(ToolCallback.class);
        AssistantMessage output = new AssistantMessage("decision text that should stay internal");
        BufferedStreamingResponse decision = new BufferedStreamingResponse(response(output), List.of(
                new BufferedStreamingResponse.BufferedStreamEvent(
                        BufferedStreamingResponse.EventType.CONTENT,
                        "decision text that should stay internal"
                )
        ));
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage("answer directly")));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                contains("Decision Module"),
                eq(List.of(tool)),
                same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false),
                isNull(),
                isNull()
        )).thenReturn(decision);
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), eq(false)))
                .thenReturn("final answer from final module");

        ChatResponse response = engineWithTools(List.of(tool)).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText()).isEqualTo("final answer from final module");
        assertThat(response.getResult().getOutput().getToolCalls()).isEmpty();
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
        ArgumentCaptor<Prompt> finalPromptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(messageBridge).streamFinalResponse(
                eq(SESSION_ID), eq(TURN_ID), finalPromptCaptor.capture(), same(llmService), eq(false));
        assertThat(finalPromptCaptor.getValue().getInstructions().get(0).getText())
                .contains("Current user request: answer directly")
                .contains("The current user request is authoritative");
    }

    @Test
    void shouldIncludeLanguageMatchingInDecisionPrompt() {
        ToolCallback tool = mock(ToolCallback.class);
        AssistantMessage output = new AssistantMessage("direct answer");
        BufferedStreamingResponse decision = new BufferedStreamingResponse(response(output), List.of());
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage("Answer in English.")));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                any(),
                eq(List.of(tool)),
                same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false),
                isNull(),
                isNull()
        )).thenReturn(decision);
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), eq(false)))
                .thenReturn("direct answer");

        engineWithTools(List.of(tool)).think(chatMemory, SESSION_ID);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageBridge).collectDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                systemPromptCaptor.capture(),
                eq(List.of(tool)),
                same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false),
                isNull(),
                isNull()
        );
        assertThat(systemPromptCaptor.getValue())
                .contains("same language as the user's latest message")
                .contains("English input -> English output")
                .contains("Chinese input -> Chinese output")
                .contains("Mixed input -> prefer the dominant language")
                .contains("Current user request: Answer in English.")
                .contains("The current user request is the task to route or answer")
                .contains("background evidence only")
                .contains("Treat the latest user-role message in conversation history as the current")
                .contains("never claim that the user has not")
                .contains("sent a message")
                .contains("provide their first question")
                .contains("choose the memory that matches the latest")
                .contains("Do not reuse an answer to an earlier question")
                .contains("provided -> respond directly; do not call tools")
                .contains("facts the user just")
                .contains("provided -> respond directly")
                .contains("answer directly without tools unless the latest user explicitly asks")
                .contains("Tool availability alone is not a reason")
                .contains("Do not search session files or bound knowledge bases merely because")
                .contains("latest user message itself provides the relevant")
                .contains("project, owner, room, schedule")
                .contains("answer directly from those supplied")
                .contains("ordinary conversation, writing, explanation")
                .contains("Preserve explicit output constraints")
                .contains("Follow explicit short-answer or exact-text instructions")
                .contains("memory-status answer")
                .contains("retrieval result, or unrelated prior-session content")
                .contains("rather than repeating a response to the previous question")
                .contains("explicitly names an available tool and asks a factual question")
                .contains("mandatory tool-routing rules before considering a direct response")
                .contains("fact whose source is an attached session file")
                .contains("call `SessionFileSearchTool`")
                .contains("you MUST call that tool")
                .contains("do not answer directly from prior turns")
                .contains("Build the tool query from the factual subject, entities, and constraints")
                .contains("tool response contains numbered evidence")
                .contains("evidence is about a different entity or topic")
                .contains("ignore it and do not cite or substitute it")
                .contains("answer that question from the evidence and cite")
                .contains("Retrieve the file evidence first")
                .contains("Never repeat the previous assistant answer")
                .contains("switch to a capability summary")
                .contains("entities and constraints in the latest")
                .contains("same-offset zones are not interchangeable")
                .contains("explicitly requests English")
                .contains("must remain entirely in English")
                .contains("If the latest request is self-contained or changes topic")
                .contains("Do not mention or personalize with names, projects")
                .contains("request explicitly refers back to them")
                .contains("generic advice, a reusable template")
                .contains("Never copy prior internal identifiers")
                .contains("Do not introduce ChatAgent")
                .contains("standby message, or capability summary")
                .contains("I'm ready to help")
                .contains("invalid when the latest user asked a task");
    }

    @Test
    void shouldForceSessionFileSearchForNaturalAttachmentQuestion() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "I uploaded an image with a verification code. What code is shown in the image?";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Attached session files: vlm-evidence.png"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(output.getToolCalls().get(0).arguments()).contains(question);
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldForceSessionFileSearchForNaturalRoomCardContentQuestion() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "What did the old room card say?";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Attached session files: ridgewater-room-card.md"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(output.getToolCalls().get(0).arguments()).contains("old room card");
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldForceSessionFileSearchForNaturalFloorNoteQuestion() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "Who am I calling, what's still open, and which locker does the floor note point me to?";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Attached session files: kestrel-dock-note.md\nBound knowledge bases: Kestrel Readiness"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(output.getToolCalls().get(0).arguments()).contains("floor note");
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldForceSessionFileSearchForHyphenatedDockNoteQuestion() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "Give me that dock-note phrase with where it came from.";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Attached session files: kestrel-dock-note.md"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(output.getToolCalls().get(0).arguments()).contains("dock-note phrase");
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldNotForceSessionFileSearchForUnqualifiedConversationNote() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "That note helped; summarize our conversation in one sentence.";
        AssistantMessage directDecision = new AssistantMessage("Summarize directly.");
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                contains("Decision Module"),
                eq(List.of(sessionFileTool)),
                same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false),
                isNull(),
                isNull()
        )).thenReturn(new BufferedStreamingResponse(response(directDecision), List.of()));
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), eq(false)))
                .thenReturn("We clarified the next action and kept the summary brief.");

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Attached session files: unrelated.md"
        ).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText())
                .isEqualTo("We clarified the next action and kept the summary brief.");
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldNotForceSessionFileSearchForRoomCardComparisonFollowUp() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "Old card versus current room?";
        AssistantMessage directDecision = new AssistantMessage("Compare the previous file evidence with the current room.");
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                contains("Decision Module"),
                eq(List.of(sessionFileTool)),
                same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(false),
                isNull(),
                isNull()
        )).thenReturn(new BufferedStreamingResponse(response(directDecision), List.of()));
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), eq(false)))
                .thenReturn("The old card said Cedar; the current room is different.");

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Attached session files: ridgewater-room-card.md"
        ).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText())
                .isEqualTo("The old card said Cedar; the current room is different.");
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldForceSessionFileSearchForNaturalBoundKnowledgeQuestion() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "I am checking the internal reference pack. What code is listed for North America, "
                + "and which disability category has 3 participants and 3 completed ballots? Cite the supporting sources.";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Bound knowledge bases: E2E Reference Pack"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(output.getToolCalls().get(0).arguments()).contains("North America");
        assertThat(output.getToolCalls().get(0).arguments()).contains("supporting sources");
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldForceBoundKnowledgeSearchForNaturalHandoffContactQuestion() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "Do you remember the Ridgewater handoff code and escalation contact?";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Bound knowledge bases: Ridgewater Launch Notes"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(output.getToolCalls().get(0).arguments()).contains("Ridgewater");
        assertThat(output.getToolCalls().get(0).arguments()).contains("escalation contact");
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldForceBoundKnowledgeSearchForNaturalWarehouseReadinessQuestion() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "I'm covering the warehouse readiness check. What should I verify first?";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Bound knowledge bases: Warehouse Readiness"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(output.getToolCalls().get(0).arguments()).contains("warehouse readiness");
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldForceBoundKnowledgeSearchForNaturalArchiveCodeQuestion() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "What was the Nebula Cedar archive code again?";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Bound knowledge bases: Nebula Archive Notes"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(output.getToolCalls().get(0).arguments()).contains("Nebula Cedar");
        assertThat(output.getToolCalls().get(0).arguments()).contains("archive code");
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldForceLocalSearchBeforeWebForNaturalPriorityRequest() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        ToolCallback webSearchTool = mock(ToolCallback.class);
        String question = "Check our local KB and uploaded note first for the Beacon Orchard public status marker. "
                + "If the local sources do not contain it, use public web search and quote the exact latest marker with the source URL.";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool, webSearchTool),
                "Attached session files: priority-session.txt; Bound knowledge bases: Priority Release Desk"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(output.getToolCalls().get(0).arguments()).contains("Beacon Orchard public status marker");
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldRouteAttachedBriefingFollowUpToSessionFileSearch() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "Thanks. For the same attached briefing item as PRIORITY10-QUARTZ-LOCAL, who is listed as owner?";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(
                new UserMessage("What marker is only in the attached briefing?"),
                new AssistantMessage("The session-only marker is PRIORITY10-QUARTZ-LOCAL [1]."),
                new UserMessage(question)
        ));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Attached session files: priority-session.txt"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(output.getToolCalls().get(0).arguments()).contains("same attached briefing item");
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldRouteTxtAttachmentFilenameToSessionFileSearch() {
        ToolCallback sessionFileTool = namedTool("SessionFileSearchTool");
        String question = "Check the attached priority-session.txt; what marker is only in that briefing file?";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(question)));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Attached session files: priority-session.txt"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(output.getToolCalls().get(0).arguments()).contains("priority-session.txt");
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).collectDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService),
                any(), anyBoolean(), any(), any());
    }

    @Test
    void shouldAllowWebSearchAfterLocalSearchResponseForPriorityRequest() {
        ToolCallback sessionFileTool = mock(ToolCallback.class);
        ToolCallback webSearchTool = mock(ToolCallback.class);
        String question = "Check our local KB and uploaded note first for the Beacon Orchard public status marker. "
                + "If the local sources do not contain it, use public web search and quote the exact latest marker with the source URL.";
        AssistantMessage localToolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "local-call-1",
                        "function",
                        "SessionFileSearchTool",
                        "{\"query\":\"Beacon Orchard public status marker\"}"
                )))
                .build();
        ToolResponseMessage localMiss = mock(ToolResponseMessage.class);
        when(localMiss.getResponses()).thenReturn(List.of(
                new ToolResponseMessage.ToolResponse(
                        "local-call-1",
                        "SessionFileSearchTool",
                        "No relevant local evidence found for Beacon Orchard public status marker."
                )
        ));
        AssistantMessage webDecision = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "web-call-1",
                        "function",
                        "webSearch",
                        "{\"query\":\"Beacon Orchard public status marker\"}"
                )))
                .build();
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(
                new UserMessage(question),
                localToolCall,
                localMiss
        ));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), any(),
                eq(List.of(sessionFileTool, webSearchTool)), same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenReturn(new BufferedStreamingResponse(response(webDecision), List.of()));

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool, webSearchTool),
                "Attached session files: priority-session.txt; Bound knowledge bases: Priority Release Desk"
        ).think(chatMemory, SESSION_ID);

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("webSearch");
        assertThat(output.getToolCalls().get(0).arguments()).contains("Beacon Orchard public status marker");
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
    }

    @Test
    void shouldReturnToModelAfterSessionFileResponseInsteadOfForcingAgain() {
        ToolCallback sessionFileTool = mock(ToolCallback.class);
        ToolResponseMessage toolResponse = mock(ToolResponseMessage.class);
        when(toolResponse.getResponses()).thenReturn(List.of(
                new ToolResponseMessage.ToolResponse(
                        "call-1", "SessionFileSearchTool", "[1] retrieved evidence")
        ));
        AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "SessionFileSearchTool",
                        "{\"query\":\"uploaded image\"}"
                )))
                .build();
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(
                new UserMessage("What code is shown in the uploaded image?"),
                toolCallMessage,
                toolResponse
        ));
        AssistantMessage finalAnswer = new AssistantMessage("The code is VLM-CODE [1].");
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), any(),
                eq(List.of(sessionFileTool)), same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenReturn(new BufferedStreamingResponse(response(finalAnswer), List.of()));
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), eq(false)))
                .thenReturn("The code is VLM-CODE [1].");

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Attached session files: vlm-evidence.png"
        ).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText()).isEqualTo("The code is VLM-CODE [1].");
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldNotForceSessionFileSearchForUnrelatedFileTerminology() {
        ToolCallback sessionFileTool = mock(ToolCallback.class);
        AssistantMessage directAnswer = new AssistantMessage("A file descriptor identifies an open resource.");
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(
                new UserMessage("Explain how file descriptors work.")
        ));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), any(),
                eq(List.of(sessionFileTool)), same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenReturn(new BufferedStreamingResponse(response(directAnswer), List.of()));
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), eq(false)))
                .thenReturn("A file descriptor identifies an open resource.");

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Attached session files: unrelated-notes.pdf"
        ).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText())
                .isEqualTo("A file descriptor identifies an open resource.");
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldNotForceKnowledgeSearchForGeneralInternalDiscussion() {
        ToolCallback sessionFileTool = mock(ToolCallback.class);
        AssistantMessage directAnswer = new AssistantMessage("Keep the update brief and focused on decisions.");
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(
                new UserMessage("I am preparing an internal team update. Give me one concise writing tip.")
        ));
        when(messageBridge.collectDecisionResponse(
                eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), any(),
                eq(List.of(sessionFileTool)), same(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenReturn(new BufferedStreamingResponse(response(directAnswer), List.of()));
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), eq(false)))
                .thenReturn("Keep the update brief and focused on decisions.");

        ChatResponse response = engineWithTools(
                List.of(sessionFileTool),
                "Bound knowledge bases: E2E Reference Pack"
        ).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText())
                .isEqualTo("Keep the update brief and focused on decisions.");
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldNotSuppressRetrievalWhenContractRequiresIt() {
        // Phase 3 ATC-P03-AC-01: when the contract says REQUIRED_BEFORE_ANSWER,
        // the keyword gate must not suppress retrieval even without trigger words.
        ToolCallback sessionFileTool = mock(ToolCallback.class);
        when(sessionFileTool.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("SessionFileSearchTool").description("search").inputSchema("{}").build());
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(
                new UserMessage("年假怎么申请")  // no source keywords, but KB intent
        ));
        com.yulong.chatagent.intent.application.IntentResolution resolution =
                new com.yulong.chatagent.intent.application.IntentResolution(
                        com.yulong.chatagent.intent.model.IntentKind.KB,
                        List.of(),
                        List.of("kb-1"),
                        com.yulong.chatagent.intent.model.ScopePolicy.STRICT,
                        List.of(),
                        null);
        com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract contract =
                com.yulong.chatagent.agent.runtime.contract.ContractTestSupport.contractBuilder()
                        .build(resolution, "年假怎么申请", "年假怎么申请",
                                com.yulong.chatagent.agent.runtime.AgentExecutionMode.REACT);

        engineWithTools(List.of(sessionFileTool), "", contract)
                .think(chatMemory, SESSION_ID);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageBridge).persistAndPublish(eq(SESSION_ID), eq(TURN_ID), messageCaptor.capture());
        AssistantMessage persisted = (AssistantMessage) messageCaptor.getValue();
        assertThat(persisted.getToolCalls()).hasSize(1);
        assertThat(persisted.getToolCalls().get(0).name()).isEqualTo("SessionFileSearchTool");
        assertThat(persisted.getToolCalls().get(0).arguments()).contains("年假怎么申请");
    }

    @Test
    void shouldEmitEverySourceSpecificQueryForRequiredMixedRetrieval() {
        ToolCallback retrievalTool = mock(ToolCallback.class);
        when(retrievalTool.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("SessionFileSearchTool").description("search").inputSchema("{}").build());
        String request = "Compare the policy with my uploaded spreadsheet report.xlsx.";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(request)));
        com.yulong.chatagent.intent.application.IntentResolution resolution =
                new com.yulong.chatagent.intent.application.IntentResolution(
                        com.yulong.chatagent.intent.model.IntentKind.KB,
                        List.of(),
                        List.of("kb-1"),
                        com.yulong.chatagent.intent.model.ScopePolicy.STRICT,
                        List.of(),
                        null);
        com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract contract =
                com.yulong.chatagent.agent.runtime.contract.ContractTestSupport.contractBuilder()
                        .build(resolution, request, "policy comparison",
                                com.yulong.chatagent.agent.runtime.AgentExecutionMode.REACT);

        engineWithTools(List.of(retrievalTool), "", contract).think(chatMemory, SESSION_ID);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageBridge).persistAndPublish(eq(SESSION_ID), eq(TURN_ID), messageCaptor.capture());
        AssistantMessage persisted = (AssistantMessage) messageCaptor.getValue();
        assertThat(persisted.getToolCalls()).hasSize(2);
        assertThat(persisted.getToolCalls())
                .extracting(AssistantMessage.ToolCall::arguments)
                .doesNotHaveDuplicates()
                .anySatisfy(arguments -> assertThat(arguments).contains("report.xlsx"));
    }

    @Test
    void shouldKeepSameTextMultiIntentRoutesDistinctByOpaqueKey() {
        ToolCallback retrievalTool = namedTool("SessionFileSearchTool");
        String request = "Compare annual leave and travel expense rules.";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(request)));
        List<String> routeIds = List.of("leave", "expense");
        var contract = com.yulong.chatagent.agent.runtime.contract.ContractTestSupport.contractBuilder()
                .buildForRoutes(
                        routeIds.stream().map(this::kbResolution).toList(),
                        request,
                        request,
                        com.yulong.chatagent.agent.runtime.AgentExecutionMode.REACT,
                        multiUnderstanding(routeIds));

        engineWithTools(List.of(retrievalTool), "", contract).think(chatMemory, SESSION_ID);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageBridge).persistAndPublish(eq(SESSION_ID), eq(TURN_ID), messageCaptor.capture());
        AssistantMessage persisted = (AssistantMessage) messageCaptor.getValue();
        assertThat(persisted.getToolCalls()).hasSize(2);
        assertThat(persisted.getToolCalls())
                .extracting(AssistantMessage.ToolCall::arguments)
                .allSatisfy(arguments -> assertThat(arguments).contains(request))
                .anySatisfy(arguments -> assertThat(arguments).contains("\"routeKey\":\"q0\""))
                .anySatisfy(arguments -> assertThat(arguments).contains("\"routeKey\":\"q1\""))
                .doesNotHaveDuplicates();
    }

    @Test
    void shouldFailInsteadOfDroppingRoutesBeyondToolCallBudget() {
        ToolCallback retrievalTool = namedTool("SessionFileSearchTool");
        String request = "Compare all five policies.";
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage(request)));
        List<String> routeIds = List.of("a", "b", "c", "d", "e");
        var contract = com.yulong.chatagent.agent.runtime.contract.ContractTestSupport.contractBuilder()
                .buildForRoutes(
                        routeIds.stream().map(this::kbResolution).toList(),
                        request,
                        request,
                        com.yulong.chatagent.agent.runtime.AgentExecutionMode.REACT,
                        multiUnderstanding(routeIds));

        assertThatThrownBy(() -> engineWithTools(List.of(retrievalTool), "", contract)
                .think(chatMemory, SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool-call budget");
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    private com.yulong.chatagent.intent.application.IntentResolution kbResolution(String id) {
        return new com.yulong.chatagent.intent.application.IntentResolution(
                com.yulong.chatagent.intent.model.IntentKind.KB,
                List.of(com.yulong.chatagent.support.dto.IntentNodeDTO.builder()
                        .id(id).name(id).build()),
                List.of("kb-" + id),
                com.yulong.chatagent.intent.model.ScopePolicy.STRICT,
                List.of(),
                null);
    }

    private com.yulong.chatagent.intent.application.IntentUnderstandingResult multiUnderstanding(
            List<String> routeIds) {
        List<com.yulong.chatagent.intent.application.IntentCandidateEvidence> evidence =
                java.util.stream.IntStream.range(0, routeIds.size())
                        .mapToObj(index -> new com.yulong.chatagent.intent.application.IntentCandidateEvidence(
                                routeIds.get(index), routeIds.get(index), 1.0d, 0.0d,
                                index + 1, List.of("test")))
                        .toList();
        var decision = new com.yulong.chatagent.intent.application.IntentDecision(
                com.yulong.chatagent.intent.application.IntentRouteOutcome.MULTI_INTENT,
                routeIds.get(0), routeIds.subList(1, routeIds.size()), evidence, List.of(),
                com.yulong.chatagent.intent.application.IntentDecisionSource.CLASSIFIER,
                0.9d,
                com.yulong.chatagent.intent.application.ConfidenceStatus.CALIBRATED,
                "v1",
                List.of("test"));
        return new com.yulong.chatagent.intent.application.IntentUnderstandingResult(
                decision,
                com.yulong.chatagent.agent.runtime.contract.SourceNeed.KB,
                com.yulong.chatagent.agent.runtime.contract.TimeSensitivity.STATIC,
                com.yulong.chatagent.agent.runtime.contract.ActionRisk.READ_ONLY,
                List.of(com.yulong.chatagent.agent.runtime.contract.IntentLabel.MULTI_INTENT),
                false,
                false);
    }

    private AgentThinkingEngine engineWithTools(List<ToolCallback> tools) {
        return engineWithTools(tools, "session file summary");
    }

    private AgentThinkingEngine engineWithTools(List<ToolCallback> tools, String sessionFileSummary) {
        return engineWithTools(tools, sessionFileSummary, null);
    }

    private AgentThinkingEngine engineWithTools(List<ToolCallback> tools, String sessionFileSummary,
                                                 com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract contract) {
        return new AgentThinkingEngine(
                TestPromptLoader.create(),
                llmService,
                DefaultToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build(),
                tools,
                sessionFileSummary,
                "relevant long-term memory",
                TURN_ID,
                messageBridge,
                4,  // maxToolCallsPerStep
                contract
        );
    }

    private ToolCallback namedTool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name)
                .description("test tool")
                .inputSchema("{}")
                .build());
        return callback;
    }

    private static ChatResponse response(AssistantMessage output) {
        return new ChatResponse(List.of(new Generation(output)));
    }
}
