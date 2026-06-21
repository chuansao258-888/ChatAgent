package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import com.yulong.chatagent.intent.model.ScopePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
        when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
        intentRouter = new IntentRouter(TestPromptLoader.create(), intentTreeCacheManager, chatModelRouter, 0.45d, 0.2d, 2, "classifier-model", meterRegistryProvider);
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
    void shouldClarifyVagueQueryWhenRootCandidateMatches() {
        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(
                        node("domain-finance", null, IntentNodeLevel.DOMAIN, "报销制度", List.of("报销"), null),
                        node("domain-hr", null, IntentNodeLevel.DOMAIN, "请假制度", List.of("请假"), null)
                ),
                Map.of()
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(snapshot);

        IntentRoutingResult result = intentRouter.route("assistant-1", "报销");

        assertThat(result.requiresClarification()).isTrue();
        assertThat(result.clarificationCandidates())
                .extracting(com.yulong.chatagent.support.dto.IntentNodeDTO::getId)
                .contains("domain-finance");
    }

    @Test
    void shouldClarifyVagueEnglishQueryWhenRootCandidatesMatch() {
        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(
                        node("domain-a", null, IntentNodeLevel.DOMAIN, "Operations Alpha", List.of("operations"), null),
                        node("domain-b", null, IntentNodeLevel.DOMAIN, "Operations Beta", List.of("operations"), null)
                ),
                Map.of()
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(snapshot);
        when(chatModelRouter.route("classifier-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content()).thenReturn("domain-a");

        IntentRoutingResult result = intentRouter.route("assistant-1", "operations");

        assertThat(result.requiresClarification()).isTrue();
        assertThat(result.clarificationCandidates())
                .extracting(com.yulong.chatagent.support.dto.IntentNodeDTO::getId)
                .containsExactly("domain-a", "domain-b");
    }

    @Test
    void shouldClarifyChildLayerWhenSelectedParentHasNoMatchingLeaf() {
        var domain = node("domain-a", null, IntentNodeLevel.DOMAIN, "Ridgewater Desk", List.of("operations"), null);
        var category = node("category-a", "domain-a", IntentNodeLevel.CATEGORY, "Workbench", List.of(), null);
        var launchNotes = node("topic-launch", "category-a", IntentNodeLevel.TOPIC,
                "Ridgewater Launch Notes", List.of("handoff code"), IntentKind.KB);
        var roomCard = node("topic-room", "category-a", IntentNodeLevel.TOPIC,
                "Uploaded Room Card", List.of("old room card"), IntentKind.TOOL);
        var timeQuestions = node("topic-time", "category-a", IntentNodeLevel.TOPIC,
                "Time Questions", List.of("time zone"), IntentKind.TOOL);
        launchNotes.setSortOrder(0);
        roomCard.setSortOrder(1);
        timeQuestions.setSortOrder(2);
        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(domain, category, launchNotes, roomCard, timeQuestions),
                Map.of("topic-launch", List.of("kb-launch"))
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(snapshot);
        when(chatModelRouter.route("classifier-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content()).thenReturn("NONE");

        IntentRoutingResult result = intentRouter.route("assistant-1", "operations", "domain-a");

        assertThat(result.requiresClarification()).isTrue();
        assertThat(result.parentPath()).isEqualTo("Ridgewater Desk > Workbench");
        assertThat(result.clarificationCandidates())
                .extracting(com.yulong.chatagent.support.dto.IntentNodeDTO::getId)
                .containsExactly("topic-launch", "topic-room");
    }

    @Test
    void shouldReturnNoneForVagueQueryWhenRootCandidatesDoNotMatch() {
        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(
                        node("domain-finance", null, IntentNodeLevel.DOMAIN, "财务制度", List.of("报销"), null),
                        node("domain-hr", null, IntentNodeLevel.DOMAIN, "人事制度", List.of("请假"), null)
                ),
                Map.of()
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(snapshot);
        when(chatModelRouter.route("classifier-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content()).thenReturn("NONE");

        IntentRoutingResult result = intentRouter.route("assistant-1", "天气");

        assertThat(result.hasResolution()).isFalse();
        assertThat(result.requiresClarification()).isFalse();
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

    @Test
    void shouldExposeWebSearchToolMetadataAndGuidanceToClassifier() {
        com.yulong.chatagent.support.dto.IntentNodeDTO webSearch = node(
                "topic-web",
                null,
                IntentNodeLevel.TOPIC,
                "外部搜索",
                List.of("current public facts", "latest release"),
                IntentKind.TOOL
        );
        webSearch.setDescription("Use native web search for public information that may have changed.");
        webSearch.setAllowedTools(List.of("webSearchTool"));
        webSearch.setScopePolicy(ScopePolicy.STRICT);
        com.yulong.chatagent.support.dto.IntentNodeDTO knowledgeBase = node(
                "topic-kb",
                null,
                IntentNodeLevel.TOPIC,
                "内部知识库",
                List.of("内部制度", "公司政策"),
                IntentKind.KB
        );
        knowledgeBase.setDescription("Use internal knowledge bases and uploaded documents.");

        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(webSearch, knowledgeBase),
                Map.of("topic-kb", List.of("kb-internal"))
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(snapshot);
        when(chatModelRouter.route("classifier-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content()).thenReturn("topic-web");
        clearInvocations(chatClient);

        IntentRoutingResult result = intentRouter.route("assistant-1", "今天 OpenAI 有什么重要变化？请给来源");

        assertThat(result.hasResolution()).isTrue();
        assertThat(result.resolution().kind()).isEqualTo(IntentKind.TOOL);
        assertThat(result.resolution().allowedTools()).containsExactly("webSearchTool");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient).prompt(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Kind: TOOL")
                .contains("AllowedTools: webSearchTool")
                .contains("webSearchTool")
                .contains("Web search is represented by the existing TOOL intent kind")
                .contains("latest/current/today/recent");
    }

    @Test
    void shouldPreferKnowledgeBaseForNonCurrentInternalFileQuestions() {
        com.yulong.chatagent.support.dto.IntentNodeDTO webSearch = node(
                "topic-web",
                null,
                IntentNodeLevel.TOPIC,
                "外部搜索",
                List.of("最新新闻", "联网搜索"),
                IntentKind.TOOL
        );
        webSearch.setAllowedTools(List.of("webSearchTool"));
        com.yulong.chatagent.support.dto.IntentNodeDTO knowledgeBase = node(
                "topic-kb",
                null,
                IntentNodeLevel.TOPIC,
                "内部文件",
                List.of("内部文件", "上传文档", "知识库"),
                IntentKind.KB
        );

        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(webSearch, knowledgeBase),
                Map.of("topic-kb", List.of("kb-internal"))
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(snapshot);

        IntentRoutingResult result = intentRouter.route("assistant-1", "内部文件里的报销政策是什么？");

        assertThat(result.hasResolution()).isTrue();
        assertThat(result.resolution().kind()).isEqualTo(IntentKind.KB);
        assertThat(result.resolution().scopedKbIds()).containsExactly("kb-internal");
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
