package com.yulong.chatagent.chat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * Declares named {@link ChatClient} beans for every supported chat model.
 *
 * <p>The bean names are used as routing keys by {@code ChatModelRouter}.</p>
 */
public class MultiChatClientConfig {
    /**
     * Creates the default DeepSeek chat client.
     *
     * @param deepSeekChatModel DeepSeek Spring AI model
     * @return named chat client bean
     */
    @Bean("deepseek-chat")
    public ChatClient deepSeekChatClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.create(deepSeekChatModel);
    }

    /**
     * Creates the ZhipuAI chat client.
     *
     * @param zhiPuAiChatModel ZhipuAI Spring AI model
     * @return named chat client bean
     */
    @Bean("glm-4.6")
    public ChatClient zhiPuAiChatClient(ZhiPuAiChatModel zhiPuAiChatModel) {
        return ChatClient.create(zhiPuAiChatModel);
    }
}
