package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationOrchestratorServiceImplTransactionTest {

    @Test
    void handleUserTurnShouldRemainTransactional() throws NoSuchMethodException {
        Method method = ConversationOrchestratorServiceImpl.class
                .getMethod("handleUserTurn", CreateChatMessageRequest.class);

        assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
    }
}
