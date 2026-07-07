package com.yulong.chatagent.load;

import static com.yulong.chatagent.load.ChatAgentLoadDsl.BASE_URL;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.CHAT_PACE_MILLIS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.CONCURRENT_USERS;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.HOLD_SECONDS;
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
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * End-to-end chat-turn load simulation.
 *
 * <p>Each virtual user registers, then loops: create a fresh session for the
 * measured turn → open SSE for that session → record turn start time → POST a
 * chat message with a client-generated turnId → wait on SSE for the matching
 * {@code AI_DONE} event → record the e2e duration into {@link E2ESamples} →
 * close SSE. Using one session per measured turn keeps the SSE stream key
 * unique, avoiding stale buffered terminal events while preserving the
 * POST-send → AI_DONE headline metric.</p>
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
     * Sends one chat message, waits for the matching AI_DONE, records e2e.
     */
    private final ChainBuilder sendOneTurnE2E =
            exec(createChatSession("gatling-e2e"))
                    .exec(openSseForSession())
                    .exitHereIfFailed()
                    .exec(session -> session.set("turnStartNanos", System.nanoTime()))
                    .exec(feed(csv("data/chat-prompts.csv").circular()))
                    .exec(session -> prepareChatMessage(session))
                    .exec(http("Create chat message")
                            .post("/api/chat-messages")
                            .header("Authorization", "Bearer #{accessToken}")
                            .body(StringBody("#{messageBody}"))
                            .check(status().is(200))
                            .check(jsonPath("$.code").is("200"))
                            .check(jsonPath("$.data.turnId").isEL("#{turnId}")))
                    .exec(waitOnSseForTurnDone(E2E_AWAIT_TIMEOUT_SECONDS))
                    .exec(session -> {
                        long e2eNanos = System.nanoTime() - session.getLong("turnStartNanos");
                        E2ESamples.record(e2eNanos);
                        return session;
                    })
                    .exec(closeSse());

    private final ChainBuilder sendMessageLoop =
            exec(pace(Duration.ofMillis(CHAT_PACE_MILLIS)))
                    .exec(sendOneTurnE2E);

    private final ScenarioBuilder scenario = scenario("Chat API e2e load")
            .exec(registerUniqueUser())
            .during(Duration.ofSeconds(HOLD_SECONDS)).on(sendMessageLoop);

    {
        E2ESamples.reset();
        setUp(scenario.injectClosed(
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
     * Post-run: compute e2e percentiles, write CSV, write a gate verdict, and
     * fail the simulation if the e2e gate is not met. Gatling cannot assert
     * session-derived percentiles directly, so this uses a normal exception
     * rather than System.exit (which kills the forked JVM and appears as a
     * Gatling ForkException).
     */
    @Override
    public void after() {
        Path reportDir = Path.of("tools", "gatling", "target", "gatling", "e2e-report");
        E2ESamples.writeCsv(reportDir.toString());
        double p50 = E2ESamples.percentileMs(50);
        double p95 = E2ESamples.percentileMs(95);
        double p99 = E2ESamples.percentileMs(99);
        int count = E2ESamples.count();
        String verdict;
        boolean passed;
        if (p95 < 0) {
            verdict = "FAIL: no e2e samples collected — SSE AI_DONE events never arrived.";
            passed = false;
        } else if (p95 > E2E_P95_TARGET_MS) {
            verdict = "FAIL: e2e P95 " + Math.round(p95) + "ms exceeds target " + E2E_P95_TARGET_MS + "ms";
            passed = false;
        } else {
            verdict = "PASS: e2e P95 " + Math.round(p95) + "ms <= target " + E2E_P95_TARGET_MS + "ms";
            passed = true;
        }
        writeGateFile(reportDir, count, p50, p95, p99, verdict);
        System.out.println("[E2E] samples=" + count
                + " P50=" + Math.round(p50) + "ms"
                + " P95=" + Math.round(p95) + "ms"
                + " P99=" + Math.round(p99) + "ms"
                + " -> " + verdict);
        if (!passed) {
            throw new IllegalStateException("[E2E] " + verdict);
        }
    }

    private static void writeGateFile(Path reportDir, int count, double p50, double p95, double p99, String verdict) {
        try {
            Files.createDirectories(reportDir);
            String content = "samples=" + count + System.lineSeparator()
                    + "p50_ms=" + Math.round(p50) + System.lineSeparator()
                    + "p95_ms=" + Math.round(p95) + System.lineSeparator()
                    + "p99_ms=" + Math.round(p99) + System.lineSeparator()
                    + "verdict=" + verdict + System.lineSeparator();
            Files.writeString(reportDir.resolve("e2e-gate.txt"), content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write e2e gate file", e);
        }
    }
}
