package com.yulong.chatagent.load;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.yulong.chatagent.load.ChatAgentLoadDsl.BASE_URL;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.closeSse;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.openSseForSession;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/** Held fixture campaign for CLOSED/OPEN/HALF_OPEN first-packet routing behavior. */
public class RoutingResilienceSimulation extends Simulation {

    private static final String ACCESS_TOKEN = required("accessToken", "CHATAGENT_LOAD_ACCESS_TOKEN");
    private static final String[] SESSION_IDS = required("sessionIds", "CHATAGENT_SESSION_IDS").split(",");
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_SESSION =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final Path RESULT_PATH = Path.of(required(
            "scenarioResultPath", "CHATAGENT_SCENARIO_RESULT_PATH"));
    private static final int TOTAL_TURNS = 16;

    private final HttpProtocolBuilder protocol = http.baseUrl(BASE_URL)
            .acceptHeader("application/json").contentTypeHeader("application/json")
            .userAgentHeader("ChatAgent-Gatling/1.0").disableCaching();

    {
        TurnOutcomeRecorder.reset();
        setUp(
                phase("late-stale-callback", "EMBER-LATE-01", 1, 0),
                phase("open-threshold", "BIRCH-THRESHOLD", 2, 8),
                phase("open-skip", "BIRCH-SKIP", 4, 10),
                phase("half-open-close", "FIR-CLOSE", 3, 14),
                phase("reopen-threshold", "BIRCH-REOPEN", 4, 26),
                phase("reopen-complete", "BIRCH-REOPEN-COMPLETE", 1, 36),
                phase("half-open-reopen", "BIRCH-HALF-OPEN", 1, 44)
        ).protocols(protocol);
    }

    private PopulationBuilder phase(String name, String code, int users, int delaySeconds) {
        return scenario(name).exec(oneTurn(code)).injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)), atOnceUsers(users));
    }

    private ChainBuilder oneTurn(String code) {
        return exec(session -> {
                    int index = NEXT_SESSION.getAndIncrement();
                    if (index >= SESSION_IDS.length) {
                        return session.markAsFailed();
                    }
                    return session.set("sessionId", SESSION_IDS[index])
                            .set("accessToken", ACCESS_TOKEN)
                            .set("content", "Reply exactly with code " + code);
                })
                .exec(openSseForSession()).exitHereIfFailed()
                .exec(session -> TurnOutcomeRecorder.submitted(ChatAgentLoadDsl.prepareChatMessage(
                        session.set("turnStartNanos", System.nanoTime()))))
                .exec(http("Create routing campaign message")
                        .post("/api/chat-messages")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN)
                        .body(StringBody("#{messageBody}"))
                        .check(status().is(200))
                        .check(jsonPath("$.code").is("200"))
                        .check(jsonPath("$.data.turnId").isEL("#{turnId}")))
                .doIf(session -> session.isFailed()).then(exec(session -> {
                    TurnOutcomeRecorder.terminalFailed();
                    return session;
                }), closeSse())
                .exitHereIfFailed()
                .exec(waitForSessionDone())
                .exec(session -> session.isFailed() ? timedOut(session) :
                        TurnOutcomeRecorder.successful(
                                session, System.nanoTime() - session.getLong("turnStartNanos")))
                .exec(closeSse());
    }

    private static io.gatling.javaapi.core.Session timedOut(io.gatling.javaapi.core.Session session) {
        TurnOutcomeRecorder.timedOut();
        return session;
    }

    private static ChainBuilder waitForSessionDone() {
        // Every routing-campaign session is new and receives exactly one turn, so
        // session-bound AI_DONE is unambiguous even on legacy terminal events that
        // omit payload.turnId. The runner separately reconciles one assistant row
        // per exact session before reporting success.
        return exec(io.gatling.javaapi.http.HttpDsl.sse("Wait for routing AI_DONE")
                .sseName("chat-sse").setCheck().await(Duration.ofSeconds(40))
                .on(io.gatling.javaapi.http.HttpDsl.sse.checkMessage("Routing AI_DONE")
                        .checkIf((String message, io.gatling.javaapi.core.Session session) ->
                                message != null && message.contains("\"type\":\"AI_DONE\""))
                        .then(io.gatling.javaapi.core.CoreDsl.regex("AI_DONE").exists())));
    }

    @Override
    public void after() {
        TurnOutcomeRecorder.Snapshot snapshot = TurnOutcomeRecorder.snapshot(0L);
        writeResult(snapshot);
        if (!snapshot.reconciled() || snapshot.successful() != TOTAL_TURNS ||
                snapshot.invalidSuccessAfterFailedCheck() != 0L) {
            throw new IllegalStateException("Routing resilience outcomes did not reconcile.");
        }
    }

    private static void writeResult(TurnOutcomeRecorder.Snapshot snapshot) {
        String json = """
                {"schemaVersion":1,"submitted":%d,"successful":%d,"terminalFailed":%d,"timedOut":%d,"interrupted":%d,"finalInFlight":%d,"invalidSuccessAfterFailedCheck":%d,"reconciled":%s}
                """.formatted(snapshot.submitted(), snapshot.successful(), snapshot.terminalFailed(),
                snapshot.timedOut(), snapshot.interrupted(), snapshot.finalInFlight(),
                snapshot.invalidSuccessAfterFailedCheck(), snapshot.reconciled());
        try {
            Files.createDirectories(RESULT_PATH.getParent());
            Files.writeString(RESULT_PATH, json);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write routing resilience evidence", exception);
        }
    }

    private static String required(String property, String environmentVariable) {
        String value = System.getProperty(property);
        if ((value == null || value.isBlank()) && environmentVariable != null) {
            value = System.getenv(environmentVariable);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required routing property: " + property);
        }
        return value;
    }
}
