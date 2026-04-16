package com.yulong.chatagent.load;

import static com.yulong.chatagent.load.ChatAgentLoadDsl.BASE_URL;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.HOLD_SECONDS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.RAMP_SECONDS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.SSE_CONNECTIONS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.createChatSession;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.registerUniqueUser;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.sse;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

public class SseCapacitySimulation extends Simulation {

    private static final double MAX_FAILED_PERCENT =
            Double.parseDouble(ChatAgentLoadDsl.prop("maxFailedPercent", "CHATAGENT_LOAD_MAX_FAILED_PERCENT", "1.0"));

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("ChatAgent-Gatling/1.0")
            .disableCaching();

    private final ScenarioBuilder scenario = scenario("SSE capacity")
            .exec(registerUniqueUser())
            .exec(createChatSession("gatling-sse"))
            .exec(sse("Open SSE")
                    .sseName("chat-sse")
                    .get("/api/sse/connect/#{sessionId}?access_token=#{accessToken}"))
            .exec(pause(Duration.ofSeconds(HOLD_SECONDS)))
            .exec(sse("Close SSE").sseName("chat-sse").close());

    {
        setUp(scenario.injectClosed(
                        rampConcurrentUsers(1).to(SSE_CONNECTIONS).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(SSE_CONNECTIONS).during(Duration.ofSeconds(HOLD_SECONDS))
                ))
                .protocols(httpProtocol)
                .assertions(global().failedRequests().percent().lt(MAX_FAILED_PERCENT));
    }
}
