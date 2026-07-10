package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.TurnContractProperties;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContractBuilder;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

    @Mock
    private TurnExecutionContractBuilder contractBuilder;

    private final TurnContractProperties contractProperties = new TurnContractProperties();

    private ConversationTurnPreparationService newService() {
        return new ConversationTurnPreparationService(
                intentTreeCacheManager,
                pendingIntentResolutionStore,
                clarificationResolver,
                clarificationResponseBuilder,
                intentRouter,
                queryRewriter,
                systemIntentResponseRenderer,
                contractBuilder,
                contractProperties
        );
    }

    @Test
    void shouldReturnRetryClarificationWhenPendingReplyCannotBeResolved() {
        ConversationTurnPreparationService service = newService();
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
        when(clarificationResponseBuilder.build(List.of(candidate), "人事制度", true, "不知道")).thenReturn("retry");

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", "不知道");

        assertThat(result.isDirectReply()).isTrue();
        assertThat(result.directReply()).isEqualTo("retry");
    }

    @Test
    void shouldRouteSelectedClarificationWithOriginalQueryAndClearPending() {
        ConversationTurnPreparationService service = newService();
        IntentNodeDTO alpha = IntentNodeDTO.builder()
                .id("topic-1")
                .name("Operations Alpha")
                .enabled(true)
                .build();
        IntentNodeDTO beta = IntentNodeDTO.builder()
                .id("topic-2")
                .name("Operations Beta")
                .enabled(true)
                .build();
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(
                new IntentTreeSnapshot("assistant-1", 1, List.of(alpha, beta), java.util.Map.of())
        );
        when(pendingIntentResolutionStore.get("session-1")).thenReturn(PendingIntentResolution.builder()
                .sessionId("session-1")
                .candidateNodeIds(List.of("topic-1", "topic-2"))
                .originalQuery("operations")
                .parentPath("")
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
        when(clarificationResolver.resolve("第二项吧", List.of(alpha, beta))).thenReturn(beta);
        IntentResolution resolution = new IntentResolution(
                IntentKind.SYSTEM,
                List.of(beta),
                List.of(),
                ScopePolicy.STRICT,
                List.of(),
                "template"
        );
        when(intentRouter.route("assistant-1", "operations", "topic-2"))
                .thenReturn(IntentRoutingResult.resolved(resolution));
        when(systemIntentResponseRenderer.render(resolution, "operations")).thenReturn("resolved beta");

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", "第二项吧");

        assertThat(result.isDirectReply()).isTrue();
        assertThat(result.directReply()).isEqualTo("resolved beta");
        verify(pendingIntentResolutionStore).delete("session-1");
        verify(intentRouter).route("assistant-1", "operations", "topic-2");
    }

    @Test
    void shouldAbandonPendingClarificationForSubstantiveContextualFollowUp() {
        ConversationTurnPreparationService service = newService();
        IntentNodeDTO launchNotes = IntentNodeDTO.builder()
                .id("launch-notes")
                .name("Ridgewater Launch Notes")
                .enabled(true)
                .build();
        IntentNodeDTO roomCard = IntentNodeDTO.builder()
                .id("room-card")
                .name("Uploaded Room Card")
                .enabled(true)
                .build();
        List<IntentNodeDTO> candidates = List.of(launchNotes, roomCard);
        String followUp = "Please continue with one practical way to keep the Ridgewater handoff clear.";
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(
                new IntentTreeSnapshot("assistant-1", 1, candidates, java.util.Map.of())
        );
        when(pendingIntentResolutionStore.get("session-1")).thenReturn(PendingIntentResolution.builder()
                .sessionId("session-1")
                .candidateNodeIds(List.of("launch-notes", "room-card"))
                .originalQuery("operations")
                .parentPath("Ridgewater Desk > Workbench")
                .build());
        when(clarificationResolver.resolve(followUp, candidates)).thenReturn(null);

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", followUp);

        assertThat(result.isDirectReply()).isFalse();
        assertThat(result.intentResolution()).isNull();
        assertThat(result.rewrittenInput()).isEqualTo(followUp);
        verify(pendingIntentResolutionStore).delete("session-1");
        verify(intentRouter, never()).route(any(), any());
        verify(clarificationResponseBuilder, never()).build(any(), any(), any(Boolean.class), any());
    }

    @Test
    void shouldKeepPendingClarificationForVaguePronounSelection() {
        ConversationTurnPreparationService service = newService();
        IntentNodeDTO launchNotes = IntentNodeDTO.builder()
                .id("launch-notes")
                .name("Ridgewater Launch Notes")
                .enabled(true)
                .build();
        IntentNodeDTO roomCard = IntentNodeDTO.builder()
                .id("room-card")
                .name("Uploaded Room Card")
                .enabled(true)
                .build();
        List<IntentNodeDTO> candidates = List.of(launchNotes, roomCard);
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(
                new IntentTreeSnapshot("assistant-1", 1, candidates, java.util.Map.of())
        );
        when(pendingIntentResolutionStore.get("session-1")).thenReturn(PendingIntentResolution.builder()
                .sessionId("session-1")
                .candidateNodeIds(List.of("launch-notes", "room-card"))
                .originalQuery("operations")
                .parentPath("Ridgewater Desk > Workbench")
                .build());
        when(clarificationResolver.resolve("that one", candidates)).thenReturn(null);
        when(clarificationResponseBuilder.build(candidates, "Ridgewater Desk > Workbench", true, "that one"))
                .thenReturn("retry");

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", "that one");

        assertThat(result.isDirectReply()).isTrue();
        assertThat(result.directReply()).isEqualTo("retry");
        verify(pendingIntentResolutionStore, never()).delete("session-1");
    }

    @Test
    void shouldRenderSystemIntentDirectly() {
        ConversationTurnPreparationService service = newService();
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
    void shouldBypassNewClarificationForContextualFollowUpQuestion() {
        ConversationTurnPreparationService service = newService();
        IntentNodeDTO launchNotes = IntentNodeDTO.builder()
                .id("launch-notes")
                .name("Ridgewater Launch Notes")
                .enabled(true)
                .build();
        IntentNodeDTO roomCard = IntentNodeDTO.builder()
                .id("room-card")
                .name("Uploaded Room Card")
                .enabled(true)
                .build();
        String followUp = "Only current values: who owns it now, and which room should be on the invite?";
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(
                new IntentTreeSnapshot("assistant-1", 1, List.of(launchNotes, roomCard), java.util.Map.of())
        );
        when(pendingIntentResolutionStore.get("session-1")).thenReturn(null);
        when(intentRouter.route("assistant-1", followUp))
                .thenReturn(IntentRoutingResult.clarification(List.of(launchNotes, roomCard), "Ridgewater Desk > Workbench"));

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", followUp);

        assertThat(result.isDirectReply()).isFalse();
        assertThat(result.intentResolution()).isNull();
        assertThat(result.rewrittenInput()).isEqualTo(followUp);
        verify(pendingIntentResolutionStore).delete("session-1");
        verify(pendingIntentResolutionStore, never()).save(any());
    }

    @Test
    void shouldBypassNewClarificationForPointPersonFollowUp() {
        ConversationTurnPreparationService service = newService();
        IntentNodeDTO launchNotes = IntentNodeDTO.builder()
                .id("launch-notes")
                .name("Ridgewater Launch Notes")
                .enabled(true)
                .build();
        IntentNodeDTO archive = IntentNodeDTO.builder()
                .id("archive")
                .name("Operations Archive")
                .enabled(true)
                .build();
        String followUp = "Who's on point?";
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(
                new IntentTreeSnapshot("assistant-1", 1, List.of(launchNotes, archive), java.util.Map.of())
        );
        when(pendingIntentResolutionStore.get("session-1")).thenReturn(null);
        when(intentRouter.route("assistant-1", followUp))
                .thenReturn(IntentRoutingResult.clarification(List.of(launchNotes, archive), "Ridgewater Desk"));

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", followUp);

        assertThat(result.isDirectReply()).isFalse();
        assertThat(result.intentResolution()).isNull();
        assertThat(result.rewrittenInput()).isEqualTo(followUp);
        verify(pendingIntentResolutionStore).delete("session-1");
        verify(pendingIntentResolutionStore, never()).save(any());
    }

    @Test
    void shouldClearExpiredPendingClarificationWhenCandidatesAreGone() {
        ConversationTurnPreparationService service = newService();
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
        when(clarificationResponseBuilder.build(List.of(), "人事制度", true, "年假怎么申请")).thenReturn("expired");

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", "第二个");

        assertThat(result.isDirectReply()).isTrue();
        assertThat(result.directReply()).isEqualTo("expired");
        verify(pendingIntentResolutionStore).delete("session-1");
    }

    @Test
    void shouldCarryExecutionContractOnDispatchedTurnWhenEnabled() {
        // Phase 1 warn 模式：开启时每个 dispatch 结果都应带上 contract。
        contractProperties.setEnabled(true);
        ConversationTurnPreparationService service = newService();
        IntentResolution resolution = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("leaf").name("年假").build()),
                List.of("kb-1"),
                ScopePolicy.STRICT,
                List.of(),
                null
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(
                new IntentTreeSnapshot("assistant-1", 1, List.of(IntentNodeDTO.builder().id("leaf").name("年假").build()), java.util.Map.of())
        );
        when(pendingIntentResolutionStore.get("session-1")).thenReturn(null);
        when(intentRouter.route("assistant-1", "年假怎么申请")).thenReturn(IntentRoutingResult.resolved(resolution));
        when(queryRewriter.rewrite("年假怎么申请", resolution)).thenReturn("年假 申请");
        TurnExecutionContract builtContract = new TurnExecutionContractBuilder().build(resolution, "年假怎么申请", "年假 申请", null);
        when(contractBuilder.build(resolution, "年假怎么申请", "年假 申请", null)).thenReturn(builtContract);

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", "年假怎么申请");

        assertThat(result.isDirectReply()).isFalse();
        assertThat(result.executionContract()).isSameAs(builtContract);
        assertThat(result.intentResolution()).isEqualTo(resolution);
        assertThat(result.rewrittenInput()).isEqualTo("年假 申请");
        verify(contractBuilder).build(resolution, "年假怎么申请", "年假 申请", null);
    }

    @Test
    void shouldOmitExecutionContractWhenDisabled() {
        // 紧急回滚开关：关闭时不构建 contract，行为与 legacy 完全一致。
        contractProperties.setEnabled(false);
        ConversationTurnPreparationService service = newService();
        IntentResolution resolution = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("leaf").name("年假").build()),
                List.of("kb-1"),
                ScopePolicy.STRICT,
                List.of(),
                null
        );
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(
                new IntentTreeSnapshot("assistant-1", 1, List.of(IntentNodeDTO.builder().id("leaf").name("年假").build()), java.util.Map.of())
        );
        when(pendingIntentResolutionStore.get("session-1")).thenReturn(null);
        when(intentRouter.route("assistant-1", "年假怎么申请")).thenReturn(IntentRoutingResult.resolved(resolution));
        when(queryRewriter.rewrite("年假怎么申请", resolution)).thenReturn("年假 申请");

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", "年假怎么申请");

        assertThat(result.isDirectReply()).isFalse();
        assertThat(result.executionContract()).isNull();
        assertThat(result.intentResolution()).isEqualTo(resolution);
        assertThat(result.rewrittenInput()).isEqualTo("年假 申请");
        verify(contractBuilder, never()).build(any(), anyString(), anyString(), any());
    }

    @Test
    void shouldCarryContractOnPassthroughWhenIntentTreeEmpty() {
        // passthrough turn（空 intent tree）仍然会被 orchestrator dispatch 进 Agent runtime，
        // 所以 enabled=true 时也必须带 contract，否则 warn 模式覆盖出现空缺。
        contractProperties.setEnabled(true);
        ConversationTurnPreparationService service = newService();
        when(intentTreeCacheManager.loadActiveSnapshot("assistant-1")).thenReturn(
                new IntentTreeSnapshot("assistant-1", 0, List.of(), java.util.Map.of())
        );
        TurnExecutionContract passthroughContract = new TurnExecutionContractBuilder().build(null, "你好", "你好", null);
        when(contractBuilder.build(null, "你好", "你好", null)).thenReturn(passthroughContract);

        TurnPreparationResult result = service.prepare("assistant-1", "session-1", "你好");

        assertThat(result.isDirectReply()).isFalse();
        assertThat(result.executionContract()).isSameAs(passthroughContract);
        verify(pendingIntentResolutionStore).delete("session-1");
    }
}
