package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.UpdateChatRoutingCandidateOverrideRequest;
import com.yulong.chatagent.admin.model.response.GetChatRoutingStateResponse;
import com.yulong.chatagent.admin.model.vo.ChatRoutingCandidateVO;
import com.yulong.chatagent.chat.ChatClientRegistry;
import com.yulong.chatagent.chat.routing.ChatModelProviderRegistry;
import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import com.yulong.chatagent.chat.routing.ModelCapabilityResolver;
import com.yulong.chatagent.chat.routing.ModelHealthStore;
import com.yulong.chatagent.chat.routing.RoutingRuntimeOverridesStore;
import com.yulong.chatagent.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatRoutingAdminFacadeServiceImplTest {

    @Test
    void shouldAssembleStateWithEffectiveOverridesCircuitSnapshotAndOrphans() {
        Fixture fixture = fixture();
        fixture.healthStore.markFailure("glm-4");
        fixture.overrides.upsert(new RoutingRuntimeOverridesStore.CandidateOverride(
                "orphan-model", true, 1, null, null, null));

        UpdateChatRoutingCandidateOverrideRequest request = new UpdateChatRoutingCandidateOverrideRequest();
        request.setCandidateId("deepseek-chat");
        request.setEnabled(false);
        request.setPriority(5);
        request.setThinkingStrategy("model_override");
        request.setThinkingModel("deepseek-reasoner");

        fixture.facade.updateCandidateOverride(request);

        GetChatRoutingStateResponse state = fixture.facade.getRoutingState();

        assertThat(state.getRegisteredModels()).containsExactly("deepseek-chat", "glm-4.6");
        assertThat(state.getOrphanOverrideCandidateIds()).containsExactly("orphan-model");
        assertThat(state.getCandidates()).extracting(ChatRoutingCandidateVO::getId)
                .containsExactly("deepseek-chat", "glm-4");

        ChatRoutingCandidateVO deepseek = state.getCandidates()[0];
        assertThat(deepseek.getRuntimeOverrideActive()).isTrue();
        assertThat(deepseek.getConfiguredEnabled()).isTrue();
        assertThat(deepseek.getEffectiveEnabled()).isFalse();
        assertThat(deepseek.getConfiguredPriority()).isEqualTo(10);
        assertThat(deepseek.getEffectivePriority()).isEqualTo(5);
        assertThat(deepseek.getEffectiveSupportsThinking()).isTrue();
        assertThat(deepseek.getEffectiveThinkingStrategy()).isEqualTo("MODEL_OVERRIDE");
        assertThat(deepseek.getEffectiveThinkingModel()).isEqualTo("deepseek-reasoner");
        assertThat(deepseek.getRegistered()).isTrue();
        assertThat(deepseek.getCircuitState()).isEqualTo("CLOSED");

        ChatRoutingCandidateVO glm = state.getCandidates()[1];
        assertThat(glm.getRuntimeOverrideActive()).isFalse();
        assertThat(glm.getRegistered()).isTrue();
        assertThat(glm.getCircuitState()).isEqualTo("OPEN");
        assertThat(glm.getReopenInMs()).isPositive();

        fixture.facade.clearCandidateOverride("deepseek-chat");

        ChatRoutingCandidateVO clearedDeepseek = fixture.facade.getRoutingState().getCandidates()[0];
        assertThat(clearedDeepseek.getRuntimeOverrideActive()).isFalse();
        assertThat(clearedDeepseek.getEffectiveEnabled()).isTrue();
        assertThat(clearedDeepseek.getEffectivePriority()).isEqualTo(10);
        assertThat(clearedDeepseek.getEffectiveThinkingStrategy()).isEqualTo("NONE");
    }

    @Test
    void shouldRejectInvalidOverrides() {
        Fixture fixture = fixture();

        UpdateChatRoutingCandidateOverrideRequest unknown = new UpdateChatRoutingCandidateOverrideRequest();
        unknown.setCandidateId("missing");
        unknown.setEnabled(true);
        assertThatThrownBy(() -> fixture.facade.updateCandidateOverride(unknown))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Unknown routing candidate");

        UpdateChatRoutingCandidateOverrideRequest negativePriority = new UpdateChatRoutingCandidateOverrideRequest();
        negativePriority.setCandidateId("deepseek-chat");
        negativePriority.setPriority(-1);
        assertThatThrownBy(() -> fixture.facade.updateCandidateOverride(negativePriority))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("priority must be greater than or equal to 0");

        UpdateChatRoutingCandidateOverrideRequest missingModel = new UpdateChatRoutingCandidateOverrideRequest();
        missingModel.setCandidateId("deepseek-chat");
        missingModel.setThinkingStrategy("MODEL_OVERRIDE");
        assertThatThrownBy(() -> fixture.facade.updateCandidateOverride(missingModel))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("thinkingModel is required");
    }

    private static Fixture fixture() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.getHealth().setFailureThreshold(1);
        properties.setCandidates(List.of(
                candidate("deepseek-chat", "deepseek-chat", 10, false, "NONE"),
                candidate("glm-4", "glm-4.6", 20, true, "ZHIPU_THINKING_FLAG")
        ));
        RoutingRuntimeOverridesStore overrides = new RoutingRuntimeOverridesStore();
        ModelHealthStore healthStore = new ModelHealthStore(properties);
        ModelCapabilityResolver capabilityResolver =
                new ModelCapabilityResolver(mock(ChatModelProviderRegistry.class));
        ChatClientRegistry chatClientRegistry = mock(ChatClientRegistry.class);
        when(chatClientRegistry.availableModels()).thenReturn(Set.of("glm-4.6", "deepseek-chat"));
        when(chatClientRegistry.supports("deepseek-chat")).thenReturn(true);
        when(chatClientRegistry.supports("glm-4.6")).thenReturn(true);

        ChatRoutingAdminFacadeServiceImpl facade = new ChatRoutingAdminFacadeServiceImpl(
                properties,
                overrides,
                healthStore,
                capabilityResolver,
                chatClientRegistry);
        return new Fixture(facade, overrides, healthStore);
    }

    private static ChatRoutingProperties.CandidateConfig candidate(String id,
                                                                   String springClientKey,
                                                                   int priority,
                                                                   boolean supportsThinking,
                                                                   String thinkingStrategy) {
        ChatRoutingProperties.CandidateConfig candidate = new ChatRoutingProperties.CandidateConfig();
        candidate.setId(id);
        candidate.setSpringClientKey(springClientKey);
        candidate.setPriority(priority);
        candidate.setEnabled(true);
        candidate.setSupportsThinking(supportsThinking);
        candidate.setThinkingStrategy(thinkingStrategy);
        return candidate;
    }

    private record Fixture(
            ChatRoutingAdminFacadeServiceImpl facade,
            RoutingRuntimeOverridesStore overrides,
            ModelHealthStore healthStore
    ) {
    }
}
