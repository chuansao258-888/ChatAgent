package com.yulong.chatagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.chat.routing.StreamCallback;
import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.conversation.model.request.UpdateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.rag.model.CitationMetadata;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMessageBridgeImplTest {

    @Mock
    private SseService sseService;

    @Mock
    private ChatMessageFacadeService chatMessageFacadeService;

    private CurrentTurnCitationHolder currentTurnCitationHolder;
    private AgentMessageBridgeImpl agentMessageBridge;

    @BeforeEach
    void setUp() {
        currentTurnCitationHolder = new CurrentTurnCitationHolder();
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
        agentMessageBridge = new AgentMessageBridgeImpl(
                sseService,
                new ChatMessageConverter(new ObjectMapper()),
                chatMessageFacadeService,
                currentTurnCitationHolder,
                new ChatRoutingProperties(),
                meterRegistryProvider
        );
        lenient().when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("msg-1").build());
    }

    @Test
    void shouldAttachCitationsToFinalAssistantMessageAndClearHolder() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(citation("doc-1")));
        var contract = com.yulong.chatagent.agent.runtime.contract.ContractTestSupport.contractBuilder()
                .build(null, "uploaded report", "uploaded report",
                        com.yulong.chatagent.agent.runtime.AgentExecutionMode.REACT);
        currentTurnCitationHolder.recordRetrievalResult(
                "session-1", "turn-1",
                com.yulong.chatagent.rag.model.RetrievalExecutionResult.noHit(
                        com.yulong.chatagent.agent.runtime.contract.RetrievalSource.SESSION_FILES,
                        List.of(com.yulong.chatagent.agent.runtime.contract.RetrievalSource.SESSION_FILES),
                        false, List.of()),
                contract);
        AssistantMessage assistantMessage = org.mockito.Mockito.mock(AssistantMessage.class);
        when(assistantMessage.getText()).thenReturn("Final answer [1]");
        when(assistantMessage.getToolCalls()).thenReturn(List.of());

        agentMessageBridge.persistAndPublish("session-1", "turn-1", assistantMessage);

        ArgumentCaptor<ChatMessageDTO> dtoCaptor = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService).createChatMessage(dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getMetadata().getCitations()).hasSize(1);
        assertThat(dtoCaptor.getValue().getMetadata().getCitations().get(0).documentId()).isEqualTo("doc-1");
        assertThat(dtoCaptor.getValue().getMetadata().getRetrieval().retrievalOutcome())
                .isEqualTo("MISS");
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).isEmpty();
        assertThat(currentTurnCitationHolder.peekRetrievalMetadata("session-1", "turn-1")).isNull();

        ArgumentCaptor<SseMessage> sseCaptor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService).publish(org.mockito.ArgumentMatchers.eq("session-1"), sseCaptor.capture());
        assertThat(sseCaptor.getValue().getPayload().getMessage().getMetadata().getCitations()).hasSize(1);
    }

    @Test
    void shouldAttachOnlyReferencedCitationsAndRenumberFinalAssistantMessage() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(
                citation("doc-1"),
                citation("doc-2"),
                citation("doc-3")
        ));
        AssistantMessage assistantMessage = org.mockito.Mockito.mock(AssistantMessage.class);
        when(assistantMessage.getText()).thenReturn("Use the Nebula source [3] and the launch note [1].");
        when(assistantMessage.getToolCalls()).thenReturn(List.of());

        agentMessageBridge.persistAndPublish("session-1", "turn-1", assistantMessage);

        ArgumentCaptor<ChatMessageDTO> dtoCaptor = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService).createChatMessage(dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getContent())
                .isEqualTo("Use the Nebula source [1] and the launch note [2].");
        assertThat(dtoCaptor.getValue().getMetadata().getCitations())
                .extracting(CitationMetadata::documentId)
                .containsExactly("doc-3", "doc-1");
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).isEmpty();
    }

    @Test
    void shouldRemapCitationWhenDistinctiveEvidenceTokenBelongsToAnotherSnippet() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(
                citation(
                        "kb-doc",
                        RagSourceType.KNOWLEDGE_BASE,
                        "Kestrel Readiness.md",
                        "Warehouse readiness code ADAPT-B-KB-20195957. Carrier contact Robin Hale."),
                citation(
                        "file-doc",
                        RagSourceType.SESSION_FILE,
                        "kestrel-dock-note-20195957.md",
                        "The check phrase is ADAPT-B-FILE-20195957. The unloading window starts at 14:20.")
        ));
        AssistantMessage assistantMessage = org.mockito.Mockito.mock(AssistantMessage.class);
        when(assistantMessage.getText())
                .thenReturn("The check phrase on the dock note is ADAPT-B-FILE-20195957 [1].");
        when(assistantMessage.getToolCalls()).thenReturn(List.of());

        agentMessageBridge.persistAndPublish("session-1", "turn-1", assistantMessage);

        ArgumentCaptor<ChatMessageDTO> dtoCaptor = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService).createChatMessage(dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getContent())
                .isEqualTo("The check phrase on the dock note is ADAPT-B-FILE-20195957 [1].");
        assertThat(dtoCaptor.getValue().getMetadata().getCitations())
                .singleElement()
                .satisfies(citation -> {
                    assertThat(citation.documentId()).isEqualTo("file-doc");
                    assertThat(citation.sourceType()).isEqualTo(RagSourceType.SESSION_FILE);
                });
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).isEmpty();
    }

    @Test
    void shouldRemoveOrphanCitationReferencesWithoutCurrentTurnEvidence() {
        AssistantMessage assistantMessage = org.mockito.Mockito.mock(AssistantMessage.class);
        when(assistantMessage.getText()).thenReturn("The readiness code is MIXED-B-KB [1].");
        when(assistantMessage.getToolCalls()).thenReturn(List.of());

        agentMessageBridge.persistAndPublish("session-1", "turn-1", assistantMessage);

        ArgumentCaptor<ChatMessageDTO> dtoCaptor = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService).createChatMessage(dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getContent())
                .isEqualTo("The readiness code is MIXED-B-KB.");
        assertThat(dtoCaptor.getValue().getMetadata().getCitations()).isNull();
    }

    @Test
    void shouldNotAttachCitationsToIntermediateAssistantToolCallMessage() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(citation("doc-1")));
        AssistantMessage assistantMessage = org.mockito.Mockito.mock(AssistantMessage.class);
        when(assistantMessage.getText()).thenReturn("Let me search");
        when(assistantMessage.getToolCalls()).thenReturn(List.of(
                new AssistantMessage.ToolCall("tool-call-1", "function", "SessionFileSearchTool", "{\"query\":\"vacation\"}")
        ));

        agentMessageBridge.persistAndPublish("session-1", "turn-1", assistantMessage);

        ArgumentCaptor<ChatMessageDTO> dtoCaptor = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService).createChatMessage(dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getMetadata().getCitations()).isNull();
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).hasSize(1);
    }

    @Test
    void streamFinalResponseDoneEventShouldCarryTurnId() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(true), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("Final answer");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });

        String finalText = agentMessageBridge.streamFinalResponse(
                "session-1", "turn-1", new Prompt("final"), llmService, true);

        assertThat(finalText).isEqualTo("Final answer");

        ArgumentCaptor<SseMessage> sseCaptor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService, atLeastOnce()).publish(eq("session-1"), sseCaptor.capture());

        SseMessage done = sseCaptor.getAllValues().stream()
                .filter(message -> message.getType() == SseMessage.Type.AI_DONE)
                .findFirst()
                .orElseThrow();
        assertThat(done.getPayload().getDone()).isTrue();
        assertThat(done.getPayload().getTurnId()).isEqualTo("turn-1");
    }

    @Test
    void streamFinalResponseShouldAttachOnlyReferencedCitationsAndRenumberContent() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(
                citation("doc-1"),
                citation("doc-2"),
                citation("doc-3")
        ));
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("The independent archive code is MIXED-A-UNBOUND [3].");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });

        String finalText = agentMessageBridge.streamFinalResponse(
                "session-1", "turn-1", new Prompt("final"), llmService, false);

        assertThat(finalText).isEqualTo("The independent archive code is MIXED-A-UNBOUND [1].");
        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService).updateChatMessage(eq("msg-1"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getContent())
                .isEqualTo("The independent archive code is MIXED-A-UNBOUND [1].");
        assertThat(updateCaptor.getValue().getMetadata().getCitations())
                .extracting(CitationMetadata::documentId)
                .containsExactly("doc-3");

        ArgumentCaptor<SseMessage> sseCaptor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService, atLeastOnce()).publish(eq("session-1"), sseCaptor.capture());
        assertThat(sseCaptor.getAllValues())
                .anyMatch(message -> message.getType() == SseMessage.Type.AI_GENERATED_CONTENT
                        && message.getPayload().getMessage() != null
                        && "The independent archive code is MIXED-A-UNBOUND [1]."
                        .equals(message.getPayload().getMessage().getContent())
                        && message.getPayload().getMessage().getMetadata().getCitations() != null
                        && message.getPayload().getMessage().getMetadata().getCitations().size() == 1);
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).isEmpty();
    }

    @Test
    void streamFinalResponseShouldRepairRetrievedAnswerWithoutCitationMarkers() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(citation(
                "doc-1",
                RagSourceType.KNOWLEDGE_BASE,
                "Ridgewater launch notes",
                "Verification Code: MIXED-A-KB. Escalation Owner: Ada Cho.")));
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("The handoff code is MIXED-A-KB and Ada Cho owns escalation.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("The handoff code is MIXED-A-KB and Ada Cho owns escalation [1].");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(
                        new UserMessage("Do you remember the Ridgewater handoff code and escalation contact?"),
                        new SystemMessage("""
                                Retrieved evidence:
                                [1] Source: Ridgewater launch notes
                                Verification Code: MIXED-A-KB
                                Escalation Owner: Ada Cho
                                """)))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("MIXED-A-KB").contains("Ada Cho").contains("[1]");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("retrieved evidence without citation markers")
                .contains("cite it inline with the exact [n] marker");

        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService).updateChatMessage(eq("msg-2"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getContent())
                .isEqualTo("The handoff code is MIXED-A-KB and Ada Cho owns escalation [1].");
        assertThat(updateCaptor.getValue().getMetadata().getCitations())
                .extracting(CitationMetadata::documentId)
                .containsExactly("doc-1");
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).isEmpty();
    }

    @Test
    void streamFinalResponseShouldRepairPlainRetrievedFactWithoutCitationMarkers() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(citation(
                "doc-1",
                RagSourceType.KNOWLEDGE_BASE,
                "Leave Policy",
                "Employees can apply for annual leave in Workday after manager approval.")));
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("Employees can apply for annual leave in Workday after manager approval.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("Employees can apply for annual leave in Workday after manager approval [1].");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("What does the leave policy say about annual leave?")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("Workday").contains("[1]");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");
        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService).updateChatMessage(eq("msg-2"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getMetadata().getCitations())
                .extracting(CitationMetadata::documentId)
                .containsExactly("doc-1");
    }

    @Test
    void streamFinalResponseShouldNotRepairWebFallbackAnswerWhenLocalCitationIsUnused() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(citation(
                "doc-1",
                RagSourceType.KNOWLEDGE_BASE,
                "Priority KB",
                "The private local marker is PRIORITY-KB-LOCAL.")));
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("The public marker is E2E-WEB-SEARCH-BEACON-ORCHARD-GREEN. Source: https://example.test/e2e/beacon-orchard-status");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(
                        new UserMessage("Check local sources first, then web search for the Beacon Orchard public status marker."),
                        new SystemMessage("""
                                Web search results:
                                1. Example Labs Beacon Orchard status
                                   URL: https://example.test/e2e/beacon-orchard-status
                                   Snippet: The latest public status marker is E2E-WEB-SEARCH-BEACON-ORCHARD-GREEN.
                                """)))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("E2E-WEB-SEARCH-BEACON-ORCHARD-GREEN");
        verify(llmService, times(1)).streamChat(any(Prompt.class), eq(false), any(StreamCallback.class));
        verify(chatMessageFacadeService, times(0)).deleteChatMessage(any());

        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService).updateChatMessage(eq("msg-1"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getContent()).contains("E2E-WEB-SEARCH-BEACON-ORCHARD-GREEN");
        assertThat(updateCaptor.getValue().getMetadata().getCitations()).isNull();
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).isEmpty();
    }

    @Test
    void streamFinalResponseShouldDropCitationsUsedOnlyForLocalMissBeforeModelKnowledge() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(
                citation(
                        "doc-1",
                        RagSourceType.KNOWLEDGE_BASE,
                        "priority-kb.md",
                        "The private local marker is PRIORITY-KB-LOCAL."),
                citation(
                        "doc-2",
                        RagSourceType.SESSION_FILE,
                        "priority-session.txt",
                        "The uploaded session-only marker is PRIORITY-FILE-LOCAL.")));
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("""
                            Local sources: No evidence found. Neither the bound knowledge base nor the uploaded file contain any reference to E2E-NO-WEB-RESULTS [1][2].

                            Public web search: No results found.

                            General knowledge answer: A release candidate is a near-final build prepared for final validation before release.
                            """);
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("If neither local nor web has evidence, explain what a release candidate is.")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText)
                .contains("release candidate")
                .doesNotContain("[1]")
                .doesNotContain("[2]");
        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService).updateChatMessage(eq("msg-1"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getMetadata().getCitations()).isNull();
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).isEmpty();
    }

    @Test
    void streamFinalResponseShouldDropCitationWhenLocalMissSaysDoesNotAppear() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(citation(
                "doc-1",
                RagSourceType.KNOWLEDGE_BASE,
                "priority-10turn-kb.md",
                "Private Rollout Marker: PRIORITY10-ANCHOR-LOCAL. Internal Decision Code: PRIORITY10-RIDGE-LOCAL.")));
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("""
                            Internal notes: Correct - the token E2E-NO-WEB-RESULTS-10TURN-LOCAL does not appear anywhere in our session file or knowledge base [1].

                            Public sources: No web search results were found for this token either.

                            What is a release candidate? A release candidate is a near-final version prepared for final validation before release.
                            """);
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("If local and public sources do not have it, what is a release candidate?")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText)
                .contains("release candidate")
                .doesNotContain("[1]");
        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService).updateChatMessage(eq("msg-1"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getMetadata().getCitations()).isNull();
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).isEmpty();
    }

    @Test
    void streamFinalResponseShouldRepairUnsupportedCitationMarkerWhenEvidenceExists() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(citation(
                "doc-1",
                RagSourceType.KNOWLEDGE_BASE,
                "Ridgewater launch notes",
                "The handoff code is MIXED-A-KB.")));
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("The handoff code is MIXED-A-KB [9].");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("The handoff code is MIXED-A-KB [1].");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("What is the Ridgewater handoff code?")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("MIXED-A-KB [1]");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");
        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService).updateChatMessage(eq("msg-2"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getMetadata().getCitations())
                .extracting(CitationMetadata::documentId)
                .containsExactly("doc-1");
    }

    @Test
    void streamFinalResponseShouldRollbackNoUserContradictionAndRepair() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("I understand you'd like me to help you with something. However, I don't see a specific question or task in your message. Could you please let me know what you need help with?");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("A visible Playwright browser helps debug interactions; the trade-off is slower, heavier runs.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(
                        new SystemMessage("Final answer prompt"),
                        new UserMessage("Explain one Playwright visible browser advantage and one trade-off.")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("visible Playwright browser").contains("trade-off");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("claimed no user question exists")
                .contains("Explain one Playwright visible browser advantage");

        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService).updateChatMessage(eq("msg-2"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getContent()).contains("visible Playwright browser");

        ArgumentCaptor<SseMessage> sseCaptor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService, atLeastOnce()).publish(eq("session-1"), sseCaptor.capture());
        assertThat(sseCaptor.getAllValues())
                .anyMatch(message -> message.getType() == SseMessage.Type.TURN_ROLLBACK)
                .anyMatch(message -> message.getType() == SseMessage.Type.AI_DONE
                        && "msg-2".equals(message.getMetadata().getChatMessageId()));
    }

    @Test
    void streamFinalResponseShouldRepairWhenCurrentUserRequestIsOnlySystemAnchor() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("I don't see a user question in the current conversation.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("For release reviews, I will remember the badge label.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new SystemMessage("""
                        # Context

                        - Current user request: For future reviews, please remember that I prefer the badge label.
                        - Attached session files: none
                        - Relevant long-term memory: none

                        The current user request is authoritative.
                        """)))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("badge label");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("current user request is:")
                .contains("badge label")
                .doesNotContain("Attached session files");
    }

    @Test
    void streamFinalResponseShouldRepairFullRunNoUserContradictionFromSystemAnchor() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("I understand you'd like me to help you with something. However, I don't see a specific question or task in your message. Could you please let me know what you need assistance with? I'm here to help with information retrieval, answering questions, or completing tasks using the available tools and knowledge bases.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("会前先准备好变更范围和关键上下文；会议中用议程和时间盒控制讨论节奏。");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new SystemMessage("""
                        # Context

                        - Current user request: 我在安排明天的代码评审。请给我两条简短建议：一条关于会前准备，一条关于控制会议时间。
                        - Attached session files: none
                        - Relevant long-term memory: none

                        The current user request is authoritative.
                        """)))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("变更范围").contains("时间盒");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("claimed no user question exists")
                .contains("代码评审");
    }

    @Test
    void streamFinalResponseShouldRepairNoPreviousMessagesContradictionFromSystemAnchor() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("""
                            I understand you'd like me to review the conversation history. However, I don't see any previous messages in our current conversation - this appears to be the start of our interaction.

                            Could you please share what you'd like to discuss or ask about? I'm here to help with any questions or tasks you have.
                            """);
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("常见原因是登录后的会话状态没有隔离；优先排查 storage state、cookie 清理和失败前后的 trace 日志。");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new SystemMessage("""
                        # Context

                        - Current user request: Background: our browser test is flaky after the login suite. 我现在要向团队解释这个问题，请用中文简要说明一个常见原因和一个优先排查动作。
                        - Attached session files: none
                        - Relevant long-term memory: none

                        The current user request is authoritative.
                        """)))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("会话状态").contains("排查");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("claimed no user question exists")
                .contains("browser test is flaky");
    }

    @Test
    void streamFinalResponseShouldRepairAnyUserMessageContradictionFromSystemAnchor() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("I don't see any user message in the conversation history. Could you please let me know what you'd like help with?");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("A visible browser makes Playwright easier to debug because you can observe the UI; the trade-off is that it is slower and uses more resources than headless mode.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new SystemMessage("""
                        # Context

                        - Current user request: I'm choosing a verification strategy for a small web app. In two or three sentences, explain one practical advantage of running Playwright with a visible browser and one trade-off.
                        - Attached session files: none
                        - Relevant long-term memory: none

                        The current user request is authoritative.
                        """)))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("visible browser").contains("trade-off");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("claimed no user question exists")
                .contains("visible browser");
    }

    @Test
    void streamFinalResponseShouldRepairStalePreviousAnswer() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        String previousAnswer = "One practical tip for taking meeting notes is to record action items immediately.";
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent(previousAnswer);
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("Use short status sections with bullets for progress, risks, and next steps.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(
                        new UserMessage("Give me one practical tip for taking meeting notes."),
                        new AssistantMessage(previousAnswer),
                        new UserMessage("How can I make a weekly status update easier to scan?")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("status sections").contains("bullets");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("repeated an earlier assistant answer")
                .contains("weekly status update");
    }


    @Test
    void streamFinalResponseShouldRepairUserVisibleToolCallMarkup() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("""
                            Let me pull up the relevant prep notes.

                            <tool_call>
                            {\"name\":\"SessionFileSearchTool\",\"arguments\":{\"query\":\"Ridgewater quick prep\"}}
                            </tool_call>
                            """);
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("Confirm the owner, review the agenda, and verify the room before the prep meeting.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(
                        new SystemMessage("Final answer prompt"),
                        new UserMessage("I'm running Ridgewater with Maya. We started in Maple. Can you give me a quick prep list?")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText)
                .contains("Confirm the owner")
                .doesNotContain("<tool_call>", "SessionFileSearchTool");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("tool-call markup")
                .contains("Do not emit XML, JSON, function-call blocks, or internal tool names");
    }




    @Test
    void streamFinalResponseShouldAllowStaleRoomWhenUserAsksForComparison() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("Old card: Cedar-1. Current room: Iris-1.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(
                        new UserMessage("The room card lists Cedar-1."),
                        new UserMessage("That card is old; we're using Iris-1 now."),
                        new UserMessage("Old card versus current room?")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("Cedar-1", "Iris-1");
        verify(chatMessageFacadeService, times(0)).deleteChatMessage(any());
        verify(llmService, times(1)).streamChat(any(Prompt.class), eq(false), any(StreamCallback.class));
    }


    @Test
    void streamFinalResponseShouldAllowIdentifierRepeatedInTopicChangeRequest() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("可以，先把 Ridgewater-1E893584 的三项工作按阻塞影响排序。");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(
                        new UserMessage("Ridgewater-1E893584 is owned by Maya-1E893584."),
                        new AssistantMessage("Got it."),
                        new UserMessage("换个话题，Ridgewater-1E893584 的三条待办怎么排？")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("Ridgewater-1E893584");
        verify(chatMessageFacadeService, times(0)).deleteChatMessage(any());
        verify(llmService, times(1)).streamChat(any(Prompt.class), eq(false), any(StreamCallback.class));
    }




    @Test
    void streamFinalResponseShouldRepairEmptyFinalAnswer() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("Use a short title and a consistent label so the note is easy to search later.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("What's one simple way to keep a short note easy to find later?")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("short title").contains("label");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("produced no user-visible answer")
                .contains("short note");
    }



    @Test
    void streamFinalResponseShouldKeepCurrentSessionFileCitationInBoundaryAnswer() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(
                citation(
                        "meridian-doc",
                        RagSourceType.KNOWLEDGE_BASE,
                        "meridian-payroll-cutover-20195957.md",
                        "ADAPT-A-KB-20195957 Meridian payroll cutover owner is Dana Brooks."),
                citation(
                        "kestrel-file",
                        RagSourceType.SESSION_FILE,
                        "kestrel-dock-note-20195957.md",
                        "ADAPT-B-FILE-20195957 Kestrel dock readiness is green for Crate-20195957.")
        ));
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("Leave Meridian out. Keep the Kestrel handoff focused on dock readiness [2].");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(
                        new UserMessage("I am wrapping the Kestrel-20195957 warehouse handoff."),
                        new UserMessage("If Meridian comes up in this handoff, should I keep it separate?")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText)
                .isEqualTo("Leave Meridian out. Keep the Kestrel handoff focused on dock readiness [1].");
        verify(llmService).streamChat(any(Prompt.class), eq(false), any(StreamCallback.class));
        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService, atLeastOnce()).updateChatMessage(eq("msg-1"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getMetadata().getCitations())
                .singleElement()
                .satisfies(citation -> {
                    assertThat(citation.documentId()).isEqualTo("kestrel-file");
                    assertThat(citation.sourceType()).isEqualTo(RagSourceType.SESSION_FILE);
                    assertThat(citation.snippet()).doesNotContain("ADAPT-A-KB-20195957");
                });
    }

    @Test
    void streamFinalResponseShouldRemapCitationForDistinctiveTokenWithDigits() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(
                citation(
                        "priority-kb",
                        RagSourceType.KNOWLEDGE_BASE,
                        "priority-10turn-kb.md",
                        "Private Rollout Marker: PRIORITY10-ANCHOR-LOCAL. Internal Decision Code: PRIORITY10-RIDGE-LOCAL."),
                citation(
                        "priority-file",
                        RagSourceType.SESSION_FILE,
                        "priority-10turn-session.txt",
                        "The next checklist item before noon is PRIORITY10-NOON-LOCAL.")
        ));
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("From the attached session file, verify PRIORITY10-NOON-LOCAL before noon [1].");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("What exactly should I verify before noon from the attachment?")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).isEqualTo("From the attached session file, verify PRIORITY10-NOON-LOCAL before noon [1].");
        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService, atLeastOnce()).updateChatMessage(eq("msg-1"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getMetadata().getCitations())
                .singleElement()
                .satisfies(citation -> {
                    assertThat(citation.documentId()).isEqualTo("priority-file");
                    assertThat(citation.sourceType()).isEqualTo(RagSourceType.SESSION_FILE);
                    assertThat(citation.documentName()).isEqualTo("priority-10turn-session.txt");
                });
    }

    @Test
    void streamDecisionResponseShouldRemapCitationForDistinctiveTokenWithDigits() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(
                citation(
                        "priority-kb",
                        RagSourceType.KNOWLEDGE_BASE,
                        "priority-10turn-kb.md",
                        "Private Rollout Marker: PRIORITY10-ANCHOR-LOCAL. Internal Decision Code: PRIORITY10-RIDGE-LOCAL."),
                citation(
                        "priority-file",
                        RagSourceType.SESSION_FILE,
                        "priority-10turn-session.txt",
                        "The next checklist item before noon is PRIORITY10-NOON-LOCAL.")
        ));
        LLMService llmService = mock(LLMService.class);
        String finalContent = "From the attachment, verify PRIORITY10-NOON-LOCAL before noon [1].";
        when(llmService.streamDecisionWithRouting(any(Prompt.class), eq("sys"), eq(List.of()), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(3);
                    callback.onContent(finalContent);
                    callback.onComplete();
                    return new BufferedStreamingResponse(
                            new ChatResponse(List.of(new Generation(new AssistantMessage(finalContent)))),
                            List.of());
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("What exactly should I verify before noon from the attachment?")))
                .build();

        agentMessageBridge.streamDecisionResponse(
                "session-1",
                "turn-1",
                prompt,
                "sys",
                List.of(),
                llmService);

        ArgumentCaptor<UpdateChatMessageRequest> updateCaptor = ArgumentCaptor.forClass(UpdateChatMessageRequest.class);
        verify(chatMessageFacadeService, atLeastOnce()).updateChatMessage(eq("msg-1"), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getContent())
                .isEqualTo("From the attachment, verify PRIORITY10-NOON-LOCAL before noon [1].");
        assertThat(updateCaptor.getValue().getMetadata().getCitations())
                .singleElement()
                .satisfies(citation -> {
                    assertThat(citation.documentId()).isEqualTo("priority-file");
                    assertThat(citation.sourceType()).isEqualTo(RagSourceType.SESSION_FILE);
                });
    }

    @Test
    void streamFinalResponseShouldRepairSystemPromptLeakageForOrdinaryTurn() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("""
                            ## System Configuration Summary
                            Core Identity: ChatAgent
                            Tool Strategy: use tools when needed.
                            Final Answer Module: answer the user.
                            """);
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("会前先让大家看变更范围；会议中用议程和时间盒控制讨论。");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(
                        new SystemMessage("Final answer prompt"),
                        new UserMessage("我在安排代码评审，请给我一条准备建议和一条控时建议。")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("会前").contains("时间盒");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("summarized system prompts or configuration")
                .contains("代码评审");
    }

    @Test
    void streamFinalResponseShouldAllowSystemPromptSummaryWhenUserAsksForIt() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("""
                            ## System Configuration Summary
                            Core Identity: ChatAgent
                            Language Matching: answer in the user's latest language.
                            Tool Strategy: use tools when helpful.
                            """);
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("Please summarize the system prompts and configuration.")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("System Configuration Summary").contains("Tool Strategy");
        verify(chatMessageFacadeService, times(1)).updateChatMessage(eq("msg-1"), any(UpdateChatMessageRequest.class));
        verify(chatMessageFacadeService, times(0)).deleteChatMessage(any());
        verify(llmService, times(1)).streamChat(any(Prompt.class), eq(false), any(StreamCallback.class));
    }

    @Test
    void streamFinalResponseShouldRepairLanguageMismatchForChineseRequest() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("Prepare the review scope before the meeting and keep the agenda time-boxed.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("会前准备好变更范围；会议中按议程控制每个主题的时间。");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("请用两句话帮我安排明天的代码评审。")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("会前").contains("时间");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("requested Chinese language");
    }

    @Test
    void streamFinalResponseShouldRepairChineseAnswerForEnglishRequest() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("先确定评审范围，再用时间盒控制讨论节奏。");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("Start by sharing the review scope, then keep each agenda item time-boxed.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("Give me two concise code review meeting tips.")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("review scope").contains("time-boxed");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("included Chinese text");
    }

    @Test
    void streamFinalResponseShouldRepairEnglishAnswerWithChineseContamination() {
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(
                        CreateChatMessageResponse.builder().chatMessageId("msg-1").build(),
                        CreateChatMessageResponse.builder().chatMessageId("msg-2").build());
        LLMService llmService = mock(LLMService.class);
        when(llmService.streamChat(any(Prompt.class), eq(false), any(StreamCallback.class)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("""
                            Bring the incident identifier, timeline, logs, and current impact.
                            Current impact status: 明确哪些服务或用户受影响以及严重程度。
                            """);
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                })
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("Bring the incident identifier, timeline, logs, and current impact.");
                    callback.onComplete();
                    return (reactor.core.Disposable) () -> {
                    };
                });
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("What should I bring to a short incident triage?")))
                .build();

        String finalText = agentMessageBridge.streamFinalResponse("session-1", "turn-1", prompt, llmService, false);

        assertThat(finalText).contains("incident identifier").doesNotContain("明确哪些");
        verify(chatMessageFacadeService).deleteChatMessage("msg-1");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmService, times(2)).streamChat(promptCaptor.capture(), eq(false), any(StreamCallback.class));
        assertThat(promptCaptor.getAllValues().get(1).getInstructions().get(0).getText())
                .contains("included Chinese text")
                .contains("calls for English")
                .contains("entirely in English");
    }

    private CitationMetadata citation(String documentId) {
        return citation(
                documentId,
                RagSourceType.KNOWLEDGE_BASE,
                "Handbook.pdf",
                "Employees can apply for leave in Workday.");
    }

    private CitationMetadata citation(String documentId,
                                      RagSourceType sourceType,
                                      String documentName,
                                      String snippet) {
        return new CitationMetadata(
                sourceType,
                "kb-1",
                documentId,
                documentName,
                "Leave Policy",
                2,
                snippet,
                0.95d,
                "reranker",
                false
        );
    }
}
