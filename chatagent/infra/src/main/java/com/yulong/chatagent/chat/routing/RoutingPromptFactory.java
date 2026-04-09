package com.yulong.chatagent.chat.routing;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class RoutingPromptFactory {

    private final ModelCapabilityResolver capabilityResolver;

    public RoutingPromptFactory(ModelCapabilityResolver capabilityResolver) {
        this.capabilityResolver = capabilityResolver;
    }

    public Prompt create(Prompt source,
                         String systemPrompt,
                         List<ToolCallback> tools,
                         ModelTarget target,
                         boolean deepThinking) {
        List<Message> messages = new ArrayList<>();
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.addAll(source.getInstructions());

        ChatOptions options = createOptions(source.getOptions(), target, deepThinking, tools);
        return new Prompt(messages, options);
    }

    private ChatOptions createOptions(ChatOptions sourceOptions,
                                      ModelTarget target,
                                      boolean deepThinking,
                                      List<ToolCallback> tools) {
        ChatOptions copied = copyOptionsForTarget(sourceOptions, target);
        if (copied instanceof ToolCallingChatOptions toolOptions) {
            toolOptions.setToolCallbacks(tools == null ? List.of() : List.copyOf(tools));
        }
        applyThinkingOptions(copied, target, deepThinking);
        return copied;
    }

    private ChatOptions copyOptionsForTarget(ChatOptions sourceOptions, ModelTarget target) {
        if (target == null || target.candidate() == null) {
            return sourceOptions == null ? null : sourceOptions.copy();
        }
        String key = target.candidate().getSpringClientKey();

        if (!StringUtils.hasText(key)) {
            return sourceOptions == null ? null : sourceOptions.copy();
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        if (normalized.contains("glm") || normalized.contains("zhipu")) {
            if (sourceOptions instanceof ZhiPuAiChatOptions zhiPuAiChatOptions) {
                return zhiPuAiChatOptions.copy();
            }
            if (sourceOptions instanceof ToolCallingChatOptions toolCallingChatOptions) {
                return ModelOptionsUtils.copyToTarget(toolCallingChatOptions,
                        ToolCallingChatOptions.class,
                        ZhiPuAiChatOptions.class);
            }
            return sourceOptions == null ? ZhiPuAiChatOptions.builder().build()
                    : ModelOptionsUtils.copyToTarget(sourceOptions, ChatOptions.class, ZhiPuAiChatOptions.class);
        }

        if (normalized.contains("deepseek")) {
            if (sourceOptions instanceof DeepSeekChatOptions deepSeekChatOptions) {
                return deepSeekChatOptions.copy();
            }
            if (sourceOptions instanceof ToolCallingChatOptions toolCallingChatOptions) {
                return ModelOptionsUtils.copyToTarget(toolCallingChatOptions,
                        ToolCallingChatOptions.class,
                        DeepSeekChatOptions.class);
            }
            return sourceOptions == null ? DeepSeekChatOptions.builder().build()
                    : ModelOptionsUtils.copyToTarget(sourceOptions, ChatOptions.class, DeepSeekChatOptions.class);
        }

        return sourceOptions == null ? null : sourceOptions.copy();
    }

    private void applyThinkingOptions(ChatOptions options, ModelTarget target, boolean deepThinking) {
        if (!deepThinking || options == null || target == null || target.candidate() == null) {
            return;
        }
        ChatRoutingProperties.CandidateConfig candidate = target.candidate();
        if (!capabilityResolver.supportsThinking(candidate)) {
            return;
        }

        String strategy = candidate.getThinkingStrategy();
        if (!StringUtils.hasText(strategy) || "NONE".equalsIgnoreCase(strategy)) {
            if (options instanceof ZhiPuAiChatOptions zhiPuAiChatOptions) {
                zhiPuAiChatOptions.setThinking(ZhiPuAiApi.ChatCompletionRequest.Thinking.enabled());
            }
            return;
        }

        if ("ZHIPU_THINKING_FLAG".equalsIgnoreCase(strategy) && options instanceof ZhiPuAiChatOptions zhiPuAiChatOptions) {
            zhiPuAiChatOptions.setThinking(ZhiPuAiApi.ChatCompletionRequest.Thinking.enabled());
            return;
        }

        if ("MODEL_OVERRIDE".equalsIgnoreCase(strategy)
                && options instanceof DeepSeekChatOptions deepSeekChatOptions
                && StringUtils.hasText(candidate.getThinkingModel())) {
            deepSeekChatOptions.setModel(candidate.getThinkingModel());
        }
    }
}
