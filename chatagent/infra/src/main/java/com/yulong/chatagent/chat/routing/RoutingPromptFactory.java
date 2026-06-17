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

/**
 * 把外部 Prompt、system prompt、工具列表与候选模型重组成路由层可调用的 Prompt。
 *
 * <p>核心是按目标厂商复制/转换 ChatOptions、重新挂载运行时工具，并在 deepThinking 时按候选策略
 * 注入 thinking 开关或模型覆盖（智谱 thinking flag / DeepSeek model override）。</p>
 */
@Component
public class RoutingPromptFactory {

    // thinking 参数只有在模型确实支持时才注入，避免给不认识该参数的模型制造请求错误。
    private final ModelCapabilityResolver capabilityResolver;

    public RoutingPromptFactory(ModelCapabilityResolver capabilityResolver) {
        this.capabilityResolver = capabilityResolver;
    }

    public Prompt create(Prompt source,
                         String systemPrompt,
                         List<ToolCallback> tools,
                         ModelTarget target,
                         boolean deepThinking) {
        // 重新组装 message：外部 systemPrompt 放在最前面，原 Prompt 的用户/历史消息随后追加。
        List<Message> messages = new ArrayList<>();
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.addAll(source.getInstructions());

        // options 需要按目标厂商复制/转换，后面才能正确设置工具调用和 thinking 参数。
        ChatOptions options = createOptions(source.getOptions(), target, deepThinking, tools);
        return new Prompt(messages, options);
    }

    private ChatOptions createOptions(ChatOptions sourceOptions,
                                      ModelTarget target,
                                      boolean deepThinking,
                                      List<ToolCallback> tools) {
        // 先复制成目标厂商对应的 Options 类型，避免直接修改原 Prompt options。
        ChatOptions copied = copyOptionsForTarget(sourceOptions, target);
        // 如果目标 options 支持工具调用，就把本次运行时工具列表重新挂进去。
        if (copied instanceof ToolCallingChatOptions toolOptions) {
            toolOptions.setToolCallbacks(tools == null ? List.of() : List.copyOf(tools));
        }
        // 最后根据 deepThinking 和候选配置注入 thinking 开关或模型覆盖。
        applyThinkingOptions(copied, target, deepThinking);
        return copied;
    }

    private ChatOptions copyOptionsForTarget(ChatOptions sourceOptions, ModelTarget target) {
        // 缺少 target 时无法判断厂商，只能普通 copy。
        if (target == null || target.candidate() == null) {
            return sourceOptions == null ? null : sourceOptions.copy();
        }
        String key = target.candidate().getSpringClientKey();

        if (!StringUtils.hasText(key)) {
            return sourceOptions == null ? null : sourceOptions.copy();
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        // 智谱模型需要 ZhiPuAiChatOptions，后续才能调用 setThinking(...)。
        if (normalized.contains("glm") || normalized.contains("zhipu")) {
            if (sourceOptions instanceof ZhiPuAiChatOptions zhiPuAiChatOptions) {
                return zhiPuAiChatOptions.copy();
            }
            // ToolCallingChatOptions 含工具相关字段，用它作为源类型复制，尽量保留工具调用配置。
            if (sourceOptions instanceof ToolCallingChatOptions toolCallingChatOptions) {
                return ModelOptionsUtils.copyToTarget(toolCallingChatOptions,
                        ToolCallingChatOptions.class,
                        ZhiPuAiChatOptions.class);
            }
            return sourceOptions == null ? ZhiPuAiChatOptions.builder().build()
                    : ModelOptionsUtils.copyToTarget(sourceOptions, ChatOptions.class, ZhiPuAiChatOptions.class);
        }

        // DeepSeek 模型需要 DeepSeekChatOptions，后续 MODEL_OVERRIDE 可直接 setModel(...)。
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
        // 普通请求不注入 thinking；缺少 options/target/candidate 也无法安全注入。
        if (!deepThinking || options == null || target == null || target.candidate() == null) {
            return;
        }
        ChatRoutingProperties.CandidateConfig candidate = target.candidate();
        // 能力判断失败时直接跳过，防止给不支持 thinking 的模型塞厂商私有参数。
        if (!capabilityResolver.supportsThinking(candidate)) {
            return;
        }

        String strategy = candidate.getThinkingStrategy();
        // 默认策略下，智谱可以通过 thinking flag 打开思考。
        // DeepSeek 独立 reasoner 候选通常已经在 ChatClient 层绑定到 reasoner 模型，因此这里无需额外处理。
        if (!StringUtils.hasText(strategy) || "NONE".equalsIgnoreCase(strategy)) {
            if (options instanceof ZhiPuAiChatOptions zhiPuAiChatOptions) {
                zhiPuAiChatOptions.setThinking(ZhiPuAiApi.ChatCompletionRequest.Thinking.enabled());
            }
            return;
        }

        // 智谱显式策略：向请求 options 注入 thinking.enabled()。
        if ("ZHIPU_THINKING_FLAG".equalsIgnoreCase(strategy) && options instanceof ZhiPuAiChatOptions zhiPuAiChatOptions) {
            zhiPuAiChatOptions.setThinking(ZhiPuAiApi.ChatCompletionRequest.Thinking.enabled());
            return;
        }

        // 模型覆盖策略：适合用同一个候选配置在 deepThinking 时临时切换到 thinkingModel。
        if ("MODEL_OVERRIDE".equalsIgnoreCase(strategy)
                && options instanceof DeepSeekChatOptions deepSeekChatOptions
                && StringUtils.hasText(candidate.getThinkingModel())) {
            deepSeekChatOptions.setModel(candidate.getThinkingModel());
        }
    }
}
