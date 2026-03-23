package com.yulong.chatagent.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Exposes whether any external chat model provider is configured at runtime.
 */
@Component
public class ChatModelAvailability {

    private final String deepSeekApiKey;
    private final String zhiPuAiApiKey;

    public ChatModelAvailability(@Value("${spring.ai.deepseek.api-key:}") String deepSeekApiKey,
                                 @Value("${spring.ai.zhipuai.api-key:}") String zhiPuAiApiKey) {
        this.deepSeekApiKey = deepSeekApiKey;
        this.zhiPuAiApiKey = zhiPuAiApiKey;
    }

    public boolean hasConfiguredProvider() {
        return StringUtils.hasText(deepSeekApiKey) || StringUtils.hasText(zhiPuAiApiKey);
    }
}
