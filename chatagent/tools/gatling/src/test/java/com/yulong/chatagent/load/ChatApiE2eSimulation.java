package com.yulong.chatagent.load;

import static com.yulong.chatagent.load.ChatAgentLoadDsl.BASE_URL;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.CHAT_PACE_MILLIS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.CONCURRENT_USERS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.HOLD_SECONDS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.RAMP_SECONDS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.closeSse;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.createChatSession;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.openSseForSession;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.prepareChatMessage;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.registerUniqueUser;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.waitOnSseForTurnDone;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static io.gatling.javaapi.http.HttpDsl.sse;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

/**
 * End-to-end chat-turn load simulation.
 *
 * <p>Each virtual user registers, creates a session, opens one persistent SSE
 * connection, then loops: record turn start time → POST a chat message (saving
 * the returned turnId) → wait on SSE for the matching {@code AI_DONE} event →
 * record the e2e duration into {@link E2ESamples}. The e2e P50/P95/P99 are
 * computed post-run from the collector because Gatling assertions cannot cover
 * arbitrary session-derived percentiles.</p>
 *
 * <p>The built-in Gatling assertion stays on the POST enqueue P95 as a
 * secondary "enqueue health" gate.</p>
 */
public class ChatApiE2eSimulation extends Simulation {

    private static final int E2E_AWAIT_TIMEOUT_SECONDS =
            ChatAgentLoadDsl.intProp("e2eAwaitSeconds", "CHATAGENT_E2E_AWAIT_SECONDS", 30);
    private static final int E2E_P95_TARGET_MS =
            ChatAgentLoadDsl.intProp("e2eP95TargetMs", "CHATAGENT_E2E_P95_TARGET_MS", 3000);
    private static final double MAX_FAILED_PERCENT =
            Double.parseDouble(ChatAgentLoadDsl.prop("maxFailedPercent", "CHATAGENT_LOAD_MAX_FAILED_PERCENT", "1.0"));

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("ChatAgent-Gatling/1.0")
            .disableCaching();

    /**
     * Sends one chat message, captures the turnId, waits for AI_DONE, records e2e.
     */
    private final ChainBuilder sendOneTurnE2E =
            exec(session -> session.set("turnStartNanos", System.nanoTime()))
                    .exec(feed(csv("data/chat-prompts.csv").circular()))
                    .exec(session -> prepareChatMessage(session))
                    .exec(http("Create chat message")
                            .post("/api/chat-messages")
                            .header("Authorization", "Bearer #{accessToken}")
                            .body(StringBody("#{messageBody}"))
                            .check(status().is(200))
                            .check(jsonPath("$.code").is("200"))
                            .check(jsonPath("$.data.turnId").saveAs("turnId")))
                    .exec(waitOnSseForTurnDone(E2E_AWAIT_TIMEOUT_SECONDS))
                    .exec(session -> {
                        long e2eNanos = System.nanoTime() - session.getLong("turnStartNanos");
                        E2ESamples.record(e2eNanos);
                        return session;
                    });

    private final ChainBuilder sendMessageLoop =
            exec(pace(Duration.ofMillis(CHAT_PACE_MILLIS)))
                    .exec(sendOneTurnE2E);

    private final ScenarioBuilder scenario = scenario("Chat API e2e load")
            .exec(registerUniqueUser())
            .exec(createChatSession("gatling-e2e"))
            .exec(openSseForSession())
            .during(Duration.ofSeconds(HOLD_SECONDS)).on(sendMessageLoop)
            .exec(closeSse());

    {
        E2ESamples.reset();
        setUp(scenario.injectClosed(
                        rampConcurrentUsers(1).to(CONCURRENT_USERS).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(CONCURRENT_USERS).during(Duration.ofSeconds(HOLD_SECONDS))
                ))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(MAX_FAILED_PERCENT),
                        // Secondary enqueue-health gate; the headline e2e P95 is
                        // checked post-run from E2ESamples (see after()).
                        details("Create chat message").responseTime().percentile3().lt(E2E_P95_TARGET_MS)
                );
    }

    /**
     * Post-run: compute e2e percentiles, write CSV, and print a gate verdict.
     * Gatling cannot assert session-derived percentiles, so this prints the
     * result and writes a gate file. It does NOT call System.exit (which causes
     * a Gatling ForkException); the verdict is for the operator to read.
     */
    @Override
    public void after() {
        String reportDir = "tools/gatling/target/gatling/e2e-report";
        E2ESamples.writeCsv(reportDir);
        double p50 = E2ESamples.percentileMs(50);
        double p95 = E2ESamples.percentileMs(95);
        double p99 = E2ESamples.percentileMs(99);
        int count = E2ESamples.count();
        String verdict;
        if (p95 < 0) {
            verdict = "FAIL: no e2e samples collected — SSE AI_DONE events never arrived.";
        } else if (p95 > E2E_P95_TARGET_MS) {
            verdict = "FAIL: e2e P95 " + Math.round(p95) + "ms exceeds target " + E2E_P95_TARGET_MS + "ms";
        } else {
            verdict = "PASS: e2e P95 " + Math.round(p95) + "ms <= target " + E2E_P95_TARGET_MS + "ms";
        }
        System.out.println("[E2E] samples=" + count
                + " P50=" + Math.round(p50) + "ms"
                + " P95=" + Math.round(p95) + "ms"
                + " P99=" + Math.round(p99) + "ms"
                + " -> " + verdict);
    }
}
