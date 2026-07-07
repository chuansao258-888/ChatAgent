package com.yulong.chatagent.loadtest;

import com.yulong.chatagent.chat.ChatClientRegistry;
import com.yulong.chatagent.chat.ChatModelRouter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-process stub {@link ChatModelRouter} active only under the
 * {@code load-test} profile.
 *
 * <p>Auxiliary callers (intent, memory extractor, summarizer, reranker,
 * ingestion) resolve a {@link ChatClient} by name through {@code ChatModelRouter}
 * and call it directly, bypassing {@code RoutingLLMService}. This stub returns a
 * cached {@link ChatClient} wrapping {@link StubChatModel} so none of those
 * auxiliary calls hit a blackholed provider under the profile.</p>
 *
 * <p>Note: a plain-chat turn against an agent with no intent tree short-circuits
 * to {@code passthrough()} in {@code ConversationTurnPreparationService} before
 * any auxiliary model call is made, so this stub may be unused. It is harmless
 * insurance for any auxiliary call that does slip through.</p>
 */
@Profile("load-test")
@Primary
@Component
public class StubChatModelRouter extends ChatModelRouter {

    private final ChatClient stubChatClient;

    public StubChatModelRouter(ChatClientRegistry chatClientRegistry, LoadTestProperties properties) {
        // The registry + default model satisfy the parent constructor; the stub
        // overrides route(...) regardless of the requested model id.
        super(chatClientRegistry, "glm-5.2");
        this.stubChatClient = ChatClient.create(new StubChatModel(
                properties.getMockTtftMs(), properties.getMockStreamTotalMs()));
    }

    @Override
    public ChatClient route(String requestedModel) {
        // Every auxiliary caller gets the same canned stub, regardless of the
        // model id it requested, so no provider HTTP is attempted.
        return stubChatClient;
    }
}
