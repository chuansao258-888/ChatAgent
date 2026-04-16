package com.yulong.chatagent.load;

import static com.yulong.chatagent.load.ChatAgentLoadDsl.BASE_URL;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.CHAT_PACE_MILLIS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.CONCURRENT_USERS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.HOLD_SECONDS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.RAMP_SECONDS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.createChatSession;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.registerUniqueUser;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

public class ChatApiLoadSimulation extends Simulation {

    private static final int CHAT_P95_TARGET_MS =
            ChatAgentLoadDsl.intProp("chatP95TargetMs", "CHATAGENT_CHAT_P95_TARGET_MS", 5000);
    private static final double MAX_FAILED_PERCENT =
            Double.parseDouble(ChatAgentLoadDsl.prop("maxFailedPercent", "CHATAGENT_LOAD_MAX_FAILED_PERCENT", "1.0"));

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("ChatAgent-Gatling/1.0")
            .disableCaching();

    private final FeederBuilder<String> prompts = csv("data/chat-prompts.csv").circular();

    private final ChainBuilder sendMessage = exec(pace(Duration.ofMillis(CHAT_PACE_MILLIS)))
            .feed(prompts)
            .exec(ChatAgentLoadDsl::prepareChatMessage)
            .exec(http("Create chat message")
                    .post("/api/chat-messages")
                    .header("Authorization", "Bearer #{accessToken}")
                    .body(StringBody("#{messageBody}"))
                    .check(status().is(200))
                    .check(jsonPath("$.code").is("200"))
                    .check(jsonPath("$.data.chatMessageId").exists())
                    .check(jsonPath("$.data.turnId").exists()));

    private final ScenarioBuilder scenario = scenario("Chat API load")
            .exec(registerUniqueUser())
            .exec(createChatSession("gatling-chat"))
            .during(Duration.ofSeconds(HOLD_SECONDS)).on(sendMessage);

    {
        setUp(scenario.injectClosed(
                        rampConcurrentUsers(1).to(CONCURRENT_USERS).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(CONCURRENT_USERS).during(Duration.ofSeconds(HOLD_SECONDS))
                ))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(MAX_FAILED_PERCENT),
                        details("Create chat message").responseTime().percentile3().lt(CHAT_P95_TARGET_MS)
                );
    }
}
