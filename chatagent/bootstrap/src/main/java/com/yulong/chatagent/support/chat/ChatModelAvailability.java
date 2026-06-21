package com.yulong.chatagent.support.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Exposes whether any external chat model provider is configured at runtime.
 */
@Component
public class ChatModelAvailability {

    private final String deepSeekApiKey;
    private final String zaiCodingApiKey;

    public ChatModelAvailability(@Value("${spring.ai.deepseek.api-key:}") String deepSeekApiKey,
                                 @Value("${spring.ai.anthropic.api-key:}") String zaiCodingApiKey) {
        this.deepSeekApiKey = deepSeekApiKey;
        this.zaiCodingApiKey = zaiCodingApiKey;
    }

    public boolean hasConfiguredProvider() {
        return StringUtils.hasText(deepSeekApiKey)
                || StringUtils.hasText(zaiCodingApiKey);
    }
}
