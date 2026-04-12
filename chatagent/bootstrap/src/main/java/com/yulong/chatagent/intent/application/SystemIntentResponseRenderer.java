package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Renders direct orchestrator responses for SYSTEM intents.
 */
@Component
public class SystemIntentResponseRenderer {

    private final PromptLoader promptLoader;

    public SystemIntentResponseRenderer(PromptLoader promptLoader) {
        this.promptLoader = promptLoader;
    }

    public String render(IntentResolution intentResolution, String userInput) {
        if (intentResolution == null) {
            return userInput;
        }
        String template = intentResolution.systemPromptOverride();
        if (!StringUtils.hasText(template)) {
            return promptLoader.load(PromptConstants.FALLBACK_SYSTEM_INTENT);
        }
        return template
                .replace("{{userInput}}", userInput == null ? "" : userInput)
                .replace("{{intentPath}}", intentResolution.pathLabel());
    }
}
