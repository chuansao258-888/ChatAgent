package com.yulong.chatagent.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ARRB ARRB-AC-031: complete tool arguments/results must never appear in INFO/WARN logs.
 * These tests pin the log-privacy contract for the shared tool-execution path so a future
 * regression that re-introduces raw arguments or tool-response bodies into INFO/WARN fails.
 */
class AgentLogPrivacyTest {

    /**
     * A tool whose result body contains a sentinel that must never reach INFO/WARN logs.
     */
    static final class SecretReturningTool {
        @org.springframework.ai.tool.annotation.Tool(name = "echoSecret", description = "Echo a sentinel result.")
        public String echoSecret() {
            return "RESULT_BODY_SECRET_must_not_log";
        }
    }

    private ListAppender<ILoggingEvent> execAppender;
    private Logger execLogger;

    @BeforeEach
    void attachExecAppender() {
        execLogger = (Logger) LoggerFactory.getLogger(AgentToolExecutionEngine.class);
        execAppender = new ListAppender<>();
        execAppender.start();
        execLogger.addAppender(execAppender);
    }

    @AfterEach
    void detachAppenders() {
        if (execAppender != null && execLogger != null) {
            execLogger.detachAppender(execAppender);
        }
    }

    @Test
    void toolExecutionInfoLogDoesNotContainToolResponseBody() {
        // Drive the real ReAct execution path: model requests echoSecret, the callback returns
        // a result body containing a sentinel. The INFO log must record only metadata, never the body.
        ChatMemory chatMemory = mock(ChatMemory.class);
        AgentMessageBridge messageBridge = mock(AgentMessageBridge.class);
        when(chatMemory.get("session-1")).thenReturn(List.of(new UserMessage("run tool")));

        ToolCallback toolCallback = MethodToolCallbackProvider.builder()
                .toolObjects(new SecretReturningTool())
                .build()
                .getToolCallbacks()[0];

        AgentToolExecutionEngine engine = new AgentToolExecutionEngine(
                List.of(toolCallback),
                DefaultToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build(),
                "turn-1",
                messageBridge);

        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "echoSecret", "{}")))
                        .build()
        )));

        engine.execute(chatMemory, "session-1", chatResponse);

        // Confirm the tool actually ran and produced the sentinel body, so the privacy assertion is meaningful.
        org.mockito.ArgumentCaptor<ToolResponseMessage> captor =
                org.mockito.ArgumentCaptor.forClass(ToolResponseMessage.class);
        org.mockito.Mockito.verify(messageBridge).persistAndPublish(eq("session-1"), eq("turn-1"), captor.capture());
        assertThat(captor.getValue().getResponses().get(0).responseData()).contains("RESULT_BODY_SECRET_must_not_log");

        // Now assert every INFO/WARN log line is free of the sentinel tool-result body.
        List<String> infoWarn = execAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO || e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(infoWarn).isNotEmpty();
        assertThat(infoWarn).allSatisfy(line -> assertThat(line).doesNotContain("RESULT_BODY_SECRET_must_not_log"));
        // The structured metadata (count, outcome, duration) must still be present for observability.
        assertThat(infoWarn).anySatisfy(line -> {
            assertThat(line).contains("responses=1");
            assertThat(line).contains("outcome=success");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolCallingInfoLogDoesNotContainArguments() throws Exception {
        // logToolCalls is private; invoke it reflectively with a tool call whose arguments carry a sentinel,
        // and assert the emitted INFO line records only the name/count, never the raw arguments.
        ListAppender<ILoggingEvent> thinkAppender = new ListAppender<>();
        thinkAppender.start();
        Logger thinkLogger = (Logger) LoggerFactory.getLogger(AgentThinkingEngine.class);
        thinkLogger.addAppender(thinkAppender);
        try {
            List<AssistantMessage.ToolCall> calls = List.of(new AssistantMessage.ToolCall(
                    "call-1", "function", "webSearch", "{\"q\":\"ARGUMENT_SECRET_must_not_log\"}"));

            Method logToolCalls = AgentThinkingEngine.class.getDeclaredMethod("logToolCalls", List.class);
            logToolCalls.setAccessible(true);
            // Instantiate a minimal engine shell purely to call the logger method; the method only uses the logger.
            AgentThinkingEngine engine = new AgentThinkingEngine(
                    null, null, null, List.of(), null, null, "turn-1", null, 4);
            logToolCalls.invoke(engine, calls);

            List<String> infoLines = thinkAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(infoLines).isNotEmpty();
            assertThat(infoLines).allSatisfy(line -> {
                assertThat(line).doesNotContain("ARGUMENT_SECRET_must_not_log");
                assertThat(line).contains("webSearch");
            });
        } finally {
            thinkLogger.detachAppender(thinkAppender);
        }
    }
}
