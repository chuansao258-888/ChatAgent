package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.access.RequireRoleInterceptor;
import com.yulong.chatagent.admin.application.ChatRoutingAdminFacadeService;
import com.yulong.chatagent.admin.model.response.GetChatRoutingStateResponse;
import com.yulong.chatagent.admin.model.vo.ChatRoutingCandidateVO;
import com.yulong.chatagent.exception.GlobalExceptionHandler;
import com.yulong.chatagent.user.application.AuthenticatedUserSnapshotCache;
import com.yulong.chatagent.user.application.JwtTokenService;
import com.yulong.chatagent.user.config.JwtAuthenticationInterceptor;
import com.yulong.chatagent.user.config.UserWebMvcConfig;
import com.yulong.chatagent.user.converter.UserConverter;
import com.yulong.chatagent.user.model.UserStatus;
import com.yulong.chatagent.user.model.dto.JwtClaims;
import com.yulong.chatagent.user.model.dto.UserDTO;
import com.yulong.chatagent.user.port.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiChatAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiImageAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ChatRoutingAdminControllerJwtIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "auth.jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "auth.jwt.issuer=chatagent-test",
                "auth.jwt.access-ttl-minutes=30",
                "management.endpoint.health.group.routing.include=*"
        }
)
@AutoConfigureMockMvc
class ChatRoutingAdminControllerJwtIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ChatRoutingAdminFacadeService chatRoutingAdminFacadeService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthenticatedUserSnapshotCache authenticatedUserSnapshotCache;

    @BeforeEach
    void setUp() {
        Mockito.reset(chatRoutingAdminFacadeService, userRepository);
        authenticatedUserSnapshotCache.invalidate("admin-1");
        authenticatedUserSnapshotCache.invalidate("user-1");
        when(userRepository.findById("admin-1")).thenReturn(activeUser("admin-1", "tester-admin", "admin"));
        when(userRepository.findById("user-1")).thenReturn(activeUser("user-1", "tester-user", "user"));
    }

    @AfterEach
    void tearDown() {
        authenticatedUserSnapshotCache.invalidate("admin-1");
        authenticatedUserSnapshotCache.invalidate("user-1");
        Mockito.reset(chatRoutingAdminFacadeService, userRepository);
    }

    @Test
    void shouldAllowAdminTokenThroughJwtAndRoleInterceptors() throws Exception {
        when(chatRoutingAdminFacadeService.getRoutingState()).thenReturn(GetChatRoutingStateResponse.builder()
                .defaultModel("deepseek-chat")
                .registeredModels(new String[]{"deepseek-chat", "glm-4.6"})
                .candidates(new ChatRoutingCandidateVO[]{
                        ChatRoutingCandidateVO.builder()
                                .id("deepseek-chat")
                                .springClientKey("deepseek-chat")
                                .registered(true)
                                .circuitState("CLOSED")
                                .build()
                })
                .build());

        mockMvc.perform(get("/api/admin/chat-routing/state")
                        .header("Authorization", bearer(adminToken("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.defaultModel").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.candidates[0].id").value("deepseek-chat"));

        verify(chatRoutingAdminFacadeService).getRoutingState();
    }

    @Test
    void shouldRejectMissingAccessTokenBeforeController() throws Exception {
        mockMvc.perform(get("/api/admin/chat-routing/state"))
                .andExpect(status().isUnauthorized())
                .andExpect(status().reason("Missing access token"));

        verifyNoInteractions(chatRoutingAdminFacadeService);
    }

    @Test
    void shouldRejectInvalidAccessTokenBeforeController() throws Exception {
        mockMvc.perform(get("/api/admin/chat-routing/state")
                        .header("Authorization", bearer("definitely-not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(status().reason("Invalid access token"));

        verifyNoInteractions(chatRoutingAdminFacadeService);
    }

    @Test
    void shouldRejectNonAdminRoleAfterJwtAuthentication() throws Exception {
        mockMvc.perform(delete("/api/admin/chat-routing/candidates/deepseek-chat/override")
                        .header("Authorization", bearer(adminToken("user"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("Required role: ADMIN"));

        verifyNoInteractions(chatRoutingAdminFacadeService);
    }

    @Test
    void shouldBindOverrideRequestThroughFullJwtChain() throws Exception {
        mockMvc.perform(put("/api/admin/chat-routing/candidates/override")
                        .header("Authorization", bearer(adminToken("admin")))
                        .contentType("application/json")
                        .content("""
                                {
                                  "candidateId": "glm-4",
                                  "enabled": true,
                                  "priority": 30,
                                  "supportsThinking": true,
                                  "thinkingStrategy": "ZHIPU_THINKING_FLAG",
                                  "thinkingModel": "glm-4.6"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(chatRoutingAdminFacadeService).updateCandidateOverride(org.mockito.ArgumentMatchers.argThat(request ->
                "glm-4".equals(request.getCandidateId())
                        && Boolean.TRUE.equals(request.getEnabled())
                        && Integer.valueOf(30).equals(request.getPriority())
                        && Boolean.TRUE.equals(request.getSupportsThinking())
                        && "ZHIPU_THINKING_FLAG".equals(request.getThinkingStrategy())
                        && "glm-4.6".equals(request.getThinkingModel())
        ));
    }

    private String adminToken(String role) {
        String userId = "admin".equals(role) ? "admin-1" : "user-1";
        String username = "admin".equals(role) ? "tester-admin" : "tester-user";
        return jwtTokenService.generateAccessToken(JwtClaims.builder()
                .userId(userId)
                .username(username)
                .role(role)
                .build());
    }

    private static UserDTO activeUser(String userId, String username, String role) {
        return UserDTO.builder()
                .id(userId)
                .username(username)
                .role(role)
                .status(UserStatus.ACTIVE.name())
                .deleted(false)
                .build();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            RabbitAutoConfiguration.class,
            DeepSeekChatAutoConfiguration.class,
            ZhiPuAiChatAutoConfiguration.class,
            ZhiPuAiEmbeddingAutoConfiguration.class,
            ZhiPuAiImageAutoConfiguration.class
    })
    @Import({
            ChatRoutingAdminController.class,
            JwtAuthenticationInterceptor.class,
            AuthenticatedUserSnapshotCache.class,
            RequireRoleInterceptor.class,
            UserWebMvcConfig.class,
            JwtTokenService.class,
            UserConverter.class,
            GlobalExceptionHandler.class,
            TestBeans.class
    })
    static class TestApplication {
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        ChatRoutingAdminFacadeService chatRoutingAdminFacadeService() {
            return Mockito.mock(ChatRoutingAdminFacadeService.class);
        }

        @Bean
        UserRepository userRepository() {
            return Mockito.mock(UserRepository.class);
        }
    }
}
