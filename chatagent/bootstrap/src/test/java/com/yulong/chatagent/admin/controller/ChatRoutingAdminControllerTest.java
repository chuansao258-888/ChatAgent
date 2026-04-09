package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.access.RequireRoleInterceptor;
import com.yulong.chatagent.admin.application.ChatRoutingAdminFacadeService;
import com.yulong.chatagent.admin.model.request.UpdateChatRoutingCandidateOverrideRequest;
import com.yulong.chatagent.admin.model.response.GetChatRoutingStateResponse;
import com.yulong.chatagent.admin.model.vo.ChatRoutingCandidateVO;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatRoutingAdminControllerTest {

    private ChatRoutingAdminFacadeService chatRoutingAdminFacadeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatRoutingAdminFacadeService = mock(ChatRoutingAdminFacadeService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatRoutingAdminController(chatRoutingAdminFacadeService))
                .addInterceptors(new RequireRoleInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldReturnRoutingStateForAdminUser() throws Exception {
        UserContext.set(adminUser());
        when(chatRoutingAdminFacadeService.getRoutingState()).thenReturn(GetChatRoutingStateResponse.builder()
                .defaultModel("deepseek-chat")
                .deepThinkingModel("glm-4")
                .firstPacketTimeoutSeconds(60)
                .streamTotalTimeoutSeconds(120)
                .httpConnectTimeoutSeconds(10)
                .httpReadTimeoutSeconds(65)
                .registeredModels(new String[]{"deepseek-chat", "glm-4.6"})
                .orphanOverrideCandidateIds(new String[]{"orphan-model"})
                .candidates(new ChatRoutingCandidateVO[]{
                        ChatRoutingCandidateVO.builder()
                                .id("deepseek-chat")
                                .springClientKey("deepseek-chat")
                                .runtimeOverrideActive(true)
                                .configuredEnabled(true)
                                .effectiveEnabled(false)
                                .configuredPriority(10)
                                .effectivePriority(5)
                                .configuredSupportsThinking(false)
                                .effectiveSupportsThinking(true)
                                .configuredThinkingStrategy("NONE")
                                .effectiveThinkingStrategy("MODEL_OVERRIDE")
                                .configuredThinkingModel(null)
                                .effectiveThinkingModel("deepseek-reasoner")
                                .registered(true)
                                .circuitState("CLOSED")
                                .consecutiveFailures(0)
                                .reopenInMs(0L)
                                .halfOpenStartMs(null)
                                .probeGeneration(3L)
                                .build()
                })
                .build());

        mockMvc.perform(get("/api/admin/chat-routing/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.defaultModel").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.registeredModels[1]").value("glm-4.6"))
                .andExpect(jsonPath("$.data.orphanOverrideCandidateIds[0]").value("orphan-model"))
                .andExpect(jsonPath("$.data.candidates[0].id").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.candidates[0].effectiveEnabled").value(false))
                .andExpect(jsonPath("$.data.candidates[0].effectiveThinkingModel").value("deepseek-reasoner"))
                .andExpect(jsonPath("$.data.candidates[0].probeGeneration").value(3));

        verify(chatRoutingAdminFacadeService).getRoutingState();
    }

    @Test
    void shouldBindOverrideRequestBodyForAdminUser() throws Exception {
        UserContext.set(adminUser());

        mockMvc.perform(put("/api/admin/chat-routing/candidates/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidateId": "deepseek-chat",
                                  "enabled": false,
                                  "priority": 5,
                                  "supportsThinking": true,
                                  "thinkingStrategy": "MODEL_OVERRIDE",
                                  "thinkingModel": "deepseek-reasoner"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"));

        ArgumentCaptor<UpdateChatRoutingCandidateOverrideRequest> requestCaptor =
                ArgumentCaptor.forClass(UpdateChatRoutingCandidateOverrideRequest.class);
        verify(chatRoutingAdminFacadeService).updateCandidateOverride(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getCandidateId()).isEqualTo("deepseek-chat");
        assertThat(requestCaptor.getValue().getEnabled()).isFalse();
        assertThat(requestCaptor.getValue().getPriority()).isEqualTo(5);
        assertThat(requestCaptor.getValue().getSupportsThinking()).isTrue();
        assertThat(requestCaptor.getValue().getThinkingStrategy()).isEqualTo("MODEL_OVERRIDE");
        assertThat(requestCaptor.getValue().getThinkingModel()).isEqualTo("deepseek-reasoner");
    }

    @Test
    void shouldBindCandidateIdPathVariableWhenClearingOverride() throws Exception {
        UserContext.set(adminUser());

        mockMvc.perform(delete("/api/admin/chat-routing/candidates/deepseek-chat/override"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"));

        verify(chatRoutingAdminFacadeService).clearCandidateOverride("deepseek-chat");
    }

    @Test
    void shouldRejectNonAdminUserBeforeCallingFacade() throws Exception {
        UserContext.set(LoginUser.builder()
                .userId("user-1")
                .username("Normal User")
                .role("user")
                .build());

        mockMvc.perform(get("/api/admin/chat-routing/state"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("Required role: ADMIN"));

        verifyNoInteractions(chatRoutingAdminFacadeService);
    }

    private static LoginUser adminUser() {
        return LoginUser.builder()
                .userId("admin-1")
                .username("Admin")
                .role("admin")
                .build();
    }
}
