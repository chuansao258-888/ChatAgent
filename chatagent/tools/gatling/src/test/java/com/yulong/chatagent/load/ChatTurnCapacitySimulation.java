package com.yulong.chatagent.load;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.yulong.chatagent.load.ChatAgentLoadDsl.BASE_URL;
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
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Synthetic application-capacity workload using the capacity-test profile.
 * Formal execution remains gated by {@code run-capacity-matrix.ps1}; PHASE-03
 * establishes only the workload owner and lifecycle accounting contract.
 */
public class ChatTurnCapacitySimulation extends Simulation {

    private static final int AWAIT_SECONDS =
            ChatAgentLoadDsl.intProp("e2eAwaitSeconds", "CHATAGENT_E2E_AWAIT_SECONDS", 30);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("ChatAgent-Gatling/1.0")
            .disableCaching();

    private final ChainBuilder oneTurn = exec(createChatSession("capacity-turn"))
            .exec(openSseForSession())
            .exitHereIfFailed()
            .exec(session -> TurnOutcomeRecorder.submitted(
                    session.set("turnStartNanos", System.nanoTime())))
            .exec(feed(csv("data/chat-prompts.csv").circular()))
            .exec(ChatAgentLoadDsl::prepareChatMessage)
            .exec(http("Create capacity chat message")
                    .post("/api/chat-messages")
                    .header("Authorization", "Bearer #{accessToken}")
                    .body(StringBody("#{messageBody}"))
                    .check(status().is(200))
                    .check(jsonPath("$.code").is("200"))
                    .check(jsonPath("$.data.turnId").isEL("#{turnId}")))
            .doIf(session -> session.isFailed()).then(
                    exec(session -> {
                        TurnOutcomeRecorder.terminalFailed();
                        return session;
                    }),
                    closeSse())
            .exitHereIfFailed()
            .exec(waitOnSseForTurnDone(AWAIT_SECONDS))
            .exec(session -> {
                if (session.isFailed()) {
                    TurnOutcomeRecorder.timedOut();
                    return session;
                }
                long duration = System.nanoTime() - session.getLong("turnStartNanos");
                return TurnOutcomeRecorder.successful(session, duration);
            })
            .exec(closeSse());

    private final ScenarioBuilder workload = scenario("Chat turn capacity")
            .exec(registerUniqueUser())
            .during(Duration.ofSeconds(HOLD_SECONDS)).on(oneTurn);

    {
        TurnOutcomeRecorder.reset();
        setUp(workload.injectClosed(
                        constantConcurrentUsers(CONCURRENT_USERS)
                                .during(Duration.ofSeconds(HOLD_SECONDS))))
                .protocols(httpProtocol);
    }

    @Override
    public void after() {
        TurnOutcomeRecorder.Snapshot snapshot = TurnOutcomeRecorder.snapshot(0L);
        writeOutcome(snapshot);
        if (!snapshot.reconciled() || snapshot.invalidSuccessAfterFailedCheck() != 0L) {
            throw new IllegalStateException("Turn outcomes failed reconciliation.");
        }
    }

    private static void writeOutcome(TurnOutcomeRecorder.Snapshot snapshot) {
        Path path = Path.of("tools", "gatling", "target", "gatling", "turn-outcomes.json");
        String json = """
                {"schemaVersion":1,"submitted":%d,"successful":%d,"terminalFailed":%d,"timedOut":%d,"interrupted":%d,"finalInFlight":%d,"invalidSuccessAfterFailedCheck":%d,"reconciled":%s}
                """.formatted(
                snapshot.submitted(), snapshot.successful(), snapshot.terminalFailed(),
                snapshot.timedOut(), snapshot.interrupted(), snapshot.finalInFlight(),
                snapshot.invalidSuccessAfterFailedCheck(), snapshot.reconciled());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write turn outcome evidence", e);
        }
    }
}
