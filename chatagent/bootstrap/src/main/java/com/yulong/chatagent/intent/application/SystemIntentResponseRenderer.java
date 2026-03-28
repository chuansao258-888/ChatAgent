package com.yulong.chatagent.intent.application;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Renders direct orchestrator responses for SYSTEM intents.
 */
@Component
public class SystemIntentResponseRenderer {

    public String render(IntentResolution intentResolution, String userInput) {
        if (intentResolution == null) {
            return userInput;
        }
        String template = intentResolution.systemPromptOverride();
        if (!StringUtils.hasText(template)) {
            return "这个问题已被配置为系统直答，但当前还没有设置回复模板。";
        }
        return template
                .replace("{{userInput}}", userInput == null ? "" : userInput)
                .replace("{{intentPath}}", intentResolution.pathLabel());
    }
}
