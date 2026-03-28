package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationTurnPreparationServiceTest {

    @Mock
    private IntentTreeCacheManager intentTreeCacheManager;

    @Mock
    private PendingIntentResolutionStore pendingIntentResolutionStore;

    @Mock
    private ClarificationResolver clarificationResolver;

    @Mock
    private ClarificationResponseBuilder clarificationResponseBuilder;

    @Mock
    private IntentRouter intentRouter;

    @Mock
    private QueryRewriter queryRewriter;

    @Mock
    private SystemIntentResponseRenderer systemIntentResponseRenderer;

    @Test
    void shouldReturnRetryClarificationWhenPendingReplyCannotBeResolved() {
        ConversationTurnPreparationService service = new ConversationTurnPreparationService(
                intentTreeCacheManager,
                pendingIntentResolutionStore,
                clarificationResolver,
                clarificationResponseBuilder,
                intentRouter,
                queryRewriter,
                systemIntentResponseRenderer
        );
        IntentNodeDTO candidate = IntentNodeDTO.builder()
                .id("topic-1")
                .name("年假政策")
                .enabled(true)
                .build();
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(
                new IntentTreeSnapshot("assistant-1", 1, List.of(candidate), java.util.Map.of())
        );
        when(pendingIntentResolutionStore.get("session-1")).thenReturn(PendingIntentResolution.builder()
                .sessionId("session-1")
                .candidateNodeIds(List.of("topic-1"))
                .originalQuery("请问假期怎么休")
                .parentPath("人事制度")
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
        when(clarificationResolver.resolve("不知道", List.of(candidate))).thenReturn(null);
        when(clarificationResponseBuilder.build(List.of(candidate), "人事制度", true)).thenReturn("retry");

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", "不知道");

        assertThat(result.isDirectReply()).isTrue();
        assertThat(result.directReply()).isEqualTo("retry");
    }

    @Test
    void shouldRenderSystemIntentDirectly() {
        ConversationTurnPreparationService service = new ConversationTurnPreparationService(
                intentTreeCacheManager,
                pendingIntentResolutionStore,
                clarificationResolver,
                clarificationResponseBuilder,
                intentRouter,
                queryRewriter,
                systemIntentResponseRenderer
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(
                new IntentTreeSnapshot("assistant-1", 1, List.of(IntentNodeDTO.builder().id("root").build()), java.util.Map.of())
        );
        IntentResolution resolution = new IntentResolution(
                IntentKind.SYSTEM,
                List.of(IntentNodeDTO.builder().name("公司信息").build()),
                List.of(),
                ScopePolicy.STRICT,
                List.of(),
                "template"
        );
        when(intentRouter.route("assistant-1", "公司地址")).thenReturn(IntentRoutingResult.resolved(resolution));
        when(systemIntentResponseRenderer.render(resolution, "公司地址")).thenReturn("上海市...");

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", "公司地址");

        assertThat(result.isDirectReply()).isTrue();
        assertThat(result.directReply()).isEqualTo("上海市...");
        verify(pendingIntentResolutionStore).get("session-1");
    }

    @Test
    void shouldClearExpiredPendingClarificationWhenCandidatesAreGone() {
        ConversationTurnPreparationService service = new ConversationTurnPreparationService(
                intentTreeCacheManager,
                pendingIntentResolutionStore,
                clarificationResolver,
                clarificationResponseBuilder,
                intentRouter,
                queryRewriter,
                systemIntentResponseRenderer
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(
                new IntentTreeSnapshot(
                        "assistant-1",
                        2,
                        List.of(IntentNodeDTO.builder().id("other-topic").name("报销制度").build()),
                        java.util.Map.of()
                )
        );
        when(pendingIntentResolutionStore.get("session-1")).thenReturn(PendingIntentResolution.builder()
                .sessionId("session-1")
                .candidateNodeIds(List.of("topic-1"))
                .originalQuery("年假怎么申请")
                .parentPath("人事制度")
                .build());
        when(clarificationResponseBuilder.build(List.of(), "人事制度", true)).thenReturn("expired");

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", "第二个");

        assertThat(result.isDirectReply()).isTrue();
        assertThat(result.directReply()).isEqualTo("expired");
        verify(pendingIntentResolutionStore).delete("session-1");
    }
}
