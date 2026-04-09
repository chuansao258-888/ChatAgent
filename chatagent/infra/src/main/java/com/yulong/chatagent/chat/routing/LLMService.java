package com.yulong.chatagent.chat.routing;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;

import java.util.List;

public interface LLMService {

    ChatResponse chatWithRouting(Prompt prompt, String systemPrompt, List<ToolCallback> tools);

    BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt, String systemPrompt, List<ToolCallback> tools);

    BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt,
                                                        String systemPrompt,
                                                        List<ToolCallback> tools,
                                                        StreamCallback callback);

    Disposable streamChat(Prompt prompt, boolean deepThinking, StreamCallback callback);
}
