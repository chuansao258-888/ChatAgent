package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * SYSTEM 意图的直答渲染器。
 *
 * SYSTEM 意图不会进入 AgentRuntime，也不会走工具/知识库；
 * ConversationTurnPreparationService 识别到 SYSTEM 后，会直接调用这里生成回复。
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
            // 后台没有配置专属模板时，使用统一兜底回复。
            return promptLoader.load(PromptConstants.FALLBACK_SYSTEM_INTENT);
        }
        // 支持两个轻量变量，方便后台在 systemPromptOverride 里配置模板化直答。
        return template
                .replace("{{userInput}}", userInput == null ? "" : userInput)
                .replace("{{intentPath}}", intentResolution.pathLabel());
    }
}
