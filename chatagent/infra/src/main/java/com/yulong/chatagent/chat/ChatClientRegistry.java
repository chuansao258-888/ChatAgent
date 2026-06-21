package com.yulong.chatagent.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.Map;

@Component
/**
 * Registry of named {@link ChatClient} beans exposed by the infra module.
 *
 * <p>中文说明：Spring 会把所有 ChatClient Bean 按 bean 名称注入到这个 Map。
 * 路由层通过 spring-client-key 查这里，拿到真正可调用的 ChatClient。</p>
 */
public class ChatClientRegistry {

    // key 是 @Bean 名称，例如 deepseek-v4-pro、glm-5.2；value 是实际 ChatClient。
    private final Map<String, ChatClient> chatClients;

    public ChatClientRegistry(Map<String, ChatClient> chatClients) {
        this.chatClients = chatClients;
    }

    public ChatClient get(String key) {
        // 普通 get 允许返回 null，适合调用方自己决定兜底策略。
        return chatClients.get(key);
    }

    /**
     * Returns a configured chat client or fails fast when the requested model
     * is not available in the Spring context.
     *
     * @param key model/client name
     * @return configured chat client
     */
    public ChatClient getRequired(String key) {
        // 路由选中模型后必须拿到客户端；拿不到说明配置和 Bean 注册不一致，应立即失败。
        ChatClient chatClient = chatClients.get(key);
        if (chatClient == null) {
            throw new IllegalStateException("No ChatClient configured for model: " + key);
        }
        return chatClient;
    }

    public boolean supports(String key) {
        // ModelSelector 用它过滤掉没有注册 ChatClient 的候选。
        return chatClients.containsKey(key);
    }

    public Set<String> availableModels() {
        // 用于错误提示或管理端展示当前 Spring 容器里可用的模型 key。
        return chatClients.keySet();
    }
}
