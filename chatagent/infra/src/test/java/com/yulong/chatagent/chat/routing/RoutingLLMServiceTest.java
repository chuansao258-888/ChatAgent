package com.yulong.chatagent.chat.routing;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingLLMServiceTest {

    // 旧同步路由入口 chatWithRouting 已停用。
    // 原测试 syncRoutingShouldNotApplyFirstPacketTimeoutToFullResponse 只覆盖 ChatClient.call() 同步路径，
    // 当前 Agent runtime 主线已经改为 streamDecisionWithRouting / streamChat，因此这里不再保留可执行测试。
}
