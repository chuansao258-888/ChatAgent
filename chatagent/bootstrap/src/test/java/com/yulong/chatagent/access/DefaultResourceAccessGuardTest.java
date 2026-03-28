package com.yulong.chatagent.access;

import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.exception.ClientException;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultResourceAccessGuardTest {

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    private DefaultResourceAccessGuard resourceAccessGuard;

    @BeforeEach
    void setUp() {
        resourceAccessGuard = new DefaultResourceAccessGuard(chatSessionRepository, knowledgeBaseRepository);
    }

    @Test
    void shouldReturnSessionWhenOwnedByCurrentUser() {
        LoginUser user = LoginUser.builder()
                .userId("user-1")
                .role("user")
                .build();
        ChatSessionDTO chatSession = ChatSessionDTO.builder()
                .id("session-1")
                .userId("user-1")
                .build();
        when(chatSessionRepository.findById("session-1")).thenReturn(chatSession);

        assertThat(resourceAccessGuard.assertCanReadSession(user, "session-1")).isEqualTo(chatSession);
    }

    @Test
    void shouldHideSessionWhenOwnedByAnotherUser() {
        LoginUser user = LoginUser.builder()
                .userId("user-1")
                .role("user")
                .build();
        when(chatSessionRepository.findById("session-1")).thenReturn(ChatSessionDTO.builder()
                .id("session-1")
                .userId("user-2")
                .build());

        assertThatThrownBy(() -> resourceAccessGuard.assertCanReadSession(user, "session-1"))
                .isInstanceOf(BizException.class)
                .hasMessage("Chat session not found: session-1");
    }

    @Test
    void shouldReturnKnowledgeBaseForAdminUser() {
        LoginUser admin = LoginUser.builder()
                .userId("admin-1")
                .role("admin")
                .build();
        KnowledgeBaseDTO knowledgeBase = KnowledgeBaseDTO.builder()
                .id("kb-1")
                .status("ACTIVE")
                .build();
        when(knowledgeBaseRepository.findById("kb-1")).thenReturn(knowledgeBase);

        assertThat(resourceAccessGuard.assertCanManageKnowledgeBase(admin, "kb-1")).isEqualTo(knowledgeBase);
    }

    @Test
    void shouldRejectNonAdminKnowledgeBaseManagement() {
        LoginUser user = LoginUser.builder()
                .userId("user-1")
                .role("user")
                .build();

        assertThatThrownBy(() -> resourceAccessGuard.assertCanManageKnowledgeBase(user, "kb-1"))
                .isInstanceOf(ClientException.class)
                .extracting("errorCode")
                .isEqualTo(BaseErrorCode.FORBIDDEN);
    }
}
