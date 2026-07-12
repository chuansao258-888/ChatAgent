package com.yulong.chatagent.load;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.yulong.chatagent.load.ChatAgentLoadDsl.BASE_URL;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.closeSse;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.openSseForSession;
import static com.yulong.chatagent.load.ChatAgentLoadDsl.waitOnSseForTurnDone;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/** Independent-session burst that exercises Redis Agent execution permits. */
public class AgentCapacitySimulation extends Simulation {

    private static final String ACCESS_TOKEN = required("accessToken", "CHATAGENT_LOAD_ACCESS_TOKEN");
    private static final String SESSION_FEEDER = required("sessionFeeder", "CHATAGENT_SESSION_FEEDER");
    private static final int TURNS = ChatAgentLoadDsl.intProp(
            "agentTurns", "CHATAGENT_AGENT_TURNS", 8);
    private static final int AWAIT_SECONDS = ChatAgentLoadDsl.intProp(
            "e2eAwaitSeconds", "CHATAGENT_E2E_AWAIT_SECONDS", 60);
    private static final Path RESULT_PATH = Path.of(ChatAgentLoadDsl.prop(
            "scenarioResultPath", "CHATAGENT_SCENARIO_RESULT_PATH",
            "tools/gatling/target/gatling/agent-capacity-result.json"));

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("ChatAgent-Gatling/1.0")
            .disableCaching();

    private final ChainBuilder oneTurn = exec(feed(csv(SESSION_FEEDER).queue()))
            .exec(session -> session.set("accessToken", ACCESS_TOKEN))
            .exec(openSseForSession())
            .exitHereIfFailed()
            .exec(session -> TurnOutcomeRecorder.submitted(
                    ChatAgentLoadDsl.prepareChatMessage(session
                            .set("turnStartNanos", System.nanoTime())
                            .set("content", "agent capacity proof"))))
            .exec(http("Create capacity-limited message")
                    .post("/api/chat-messages")
                    .header("Authorization", "Bearer " + ACCESS_TOKEN)
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
                return TurnOutcomeRecorder.successful(
                        session, System.nanoTime() - session.getLong("turnStartNanos"));
            })
            .exec(closeSse());

    private final ScenarioBuilder workload = scenario("Agent capacity burst").exec(oneTurn);

    {
        TurnOutcomeRecorder.reset();
        setUp(workload.injectOpen(atOnceUsers(TURNS))).protocols(httpProtocol);
    }

    @Override
    public void after() {
        TurnOutcomeRecorder.Snapshot snapshot = TurnOutcomeRecorder.snapshot(0L);
        writeResult(snapshot);
        if (!snapshot.reconciled() || snapshot.successful() != TURNS ||
                snapshot.invalidSuccessAfterFailedCheck() != 0L) {
            throw new IllegalStateException("Agent capacity turn outcomes did not reconcile.");
        }
    }

    private static String required(String property) {
        return required(property, null);
    }

    private static String required(String property, String environmentVariable) {
        String value = System.getProperty(property);
        if ((value == null || value.isBlank()) && environmentVariable != null) {
            value = System.getenv(environmentVariable);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + property);
        }
        return value;
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
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write Agent capacity evidence", e);
        }
    }
}
