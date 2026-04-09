package com.yulong.chatagent.chat.routing;

import org.springframework.ai.chat.client.ChatClient;

public record ModelTarget(
        String id,
        ChatRoutingProperties.CandidateConfig candidate,
        ChatClient chatClient
) {}