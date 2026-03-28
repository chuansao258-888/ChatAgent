package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentRouterTest {

    @Mock
    private IntentTreeCacheManager intentTreeCacheManager;

    @Mock
    private ChatModelRouter chatModelRouter;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    private IntentRouter intentRouter;

    @BeforeEach
    void setUp() {
        intentRouter = new IntentRouter(intentTreeCacheManager, chatModelRouter, 0.45d, 0.2d, 2, "classifier-model");
    }

    @Test
    void shouldResolveLeafKnowledgeBaseIntentFromActiveSnapshotUsingHeuristic() {
        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(
                        node("domain-hr", null, IntentNodeLevel.DOMAIN, "人事制度", List.of("人事", "员工手册"), null),
                        node("topic-leave", "domain-hr", IntentNodeLevel.TOPIC, "年假政策", List.of("年假", "休假"), IntentKind.KB)
                ),
                Map.of("topic-leave", List.of("kb-leave"))
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(snapshot);

        // Heuristic should match "年假政策" very strongly (score > 1.2)
        IntentRoutingResult result = intentRouter.route("assistant-1", "年假政策是怎样的");

        assertThat(result.hasResolution()).isTrue();
        assertThat(result.resolution().kind()).isEqualTo(IntentKind.KB);
        assertThat(result.resolution().scopedKbIds()).containsExactly("kb-leave");
        assertThat(result.resolution().pathLabel()).isEqualTo("人事制度 > 年假政策");
    }

    @Test
    void shouldFallbackToLlmAndResolveNodeId() {
        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(
                        node("topic-a", null, IntentNodeLevel.TOPIC, "业务A", List.of("业务"), IntentKind.KB),
                        node("topic-b", null, IntentNodeLevel.TOPIC, "业务B", List.of("业务"), IntentKind.KB)
                ),
                Map.of("topic-b", List.of("kb-b"))
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(snapshot);
        when(chatModelRouter.route("classifier-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content()).thenReturn("topic-b");

        // Heuristic score will be low or ambiguous, triggering LLM
        IntentRoutingResult result = intentRouter.route("assistant-1", "我想办理业务");

        assertThat(result.hasResolution()).isTrue();
        assertThat(result.resolution().path().get(0).getId()).isEqualTo("topic-b");
    }

    @Test
    void shouldReturnNoneWhenLlmReturnsNone() {
        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(
                        node("topic-a", null, IntentNodeLevel.TOPIC, "业务A", List.of("业务A"), IntentKind.KB)
                ),
                Map.of()
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(snapshot);
        when(chatModelRouter.route("classifier-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content()).thenReturn("NONE");

        // Single candidate "业务A", but LLM says NONE for an irrelevant query.
        IntentRoutingResult result = intentRouter.route("assistant-1", "随机不相关问题");

        assertThat(result.hasResolution()).isFalse();
        assertThat(result.requiresClarification()).isFalse();
    }

    @Test
    void shouldAskForClarificationWhenLlmReturnsAmbiguous() {
        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(
                        node("topic-a", null, IntentNodeLevel.TOPIC, "业务A", List.of("业务"), IntentKind.KB),
                        node("topic-b", null, IntentNodeLevel.TOPIC, "业务B", List.of("业务"), IntentKind.KB)
                ),
                Map.of()
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(snapshot);
        when(chatModelRouter.route("classifier-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content()).thenReturn("AMBIGUOUS");

        IntentRoutingResult result = intentRouter.route("assistant-1", "模糊业务");

        assertThat(result.requiresClarification()).isTrue();
        assertThat(result.clarificationCandidates()).hasSize(2);
    }

    @Test
    void shouldFallbackToHeuristicWhenLlmFails() {
        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(
                        node("topic-a", null, IntentNodeLevel.TOPIC, "业务A", List.of("业务A"), IntentKind.KB)
                ),
                Map.of()
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(snapshot);
        when(chatModelRouter.route("classifier-model")).thenReturn(chatClient);
        
        // Simulating LLM failure
        when(chatClient.prompt(anyString())).thenThrow(new RuntimeException("LLM Down"));

        // Query avoids the exact "业务A" substring so it won't hit the 1.2 strong-match shortcut,
        // but still has enough overlap to pass the minimum heuristic score after LLM failure.
        IntentRoutingResult result = intentRouter.route("assistant-1", "业务 A");

        assertThat(result.hasResolution()).isTrue();
        assertThat(result.resolution().path().get(0).getId()).isEqualTo("topic-a");
    }

    private com.yulong.chatagent.support.dto.IntentNodeDTO node(String id,
                                                                String parentId,
                                                                IntentNodeLevel level,
                                                                String name,
                                                                List<String> examples,
                                                                IntentKind intentKind) {
        return com.yulong.chatagent.support.dto.IntentNodeDTO.builder()
                .id(id)
                .agentId("assistant-1")
                .parentId(parentId)
                .version(1)
                .status(IntentNodeStatus.PUBLISHED)
                .nodeLevel(level)
                .name(name)
                .examples(examples)
                .intentKind(intentKind)
                .enabled(true)
                .sortOrder(0)
                .build();
    }
}
