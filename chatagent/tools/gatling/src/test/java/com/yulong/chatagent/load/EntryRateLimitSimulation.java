package com.yulong.chatagent.load;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static com.yulong.chatagent.load.ChatAgentLoadDsl.BASE_URL;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/** One-identity burst that proves the HTTP entry token-bucket envelope. */
public class EntryRateLimitSimulation extends Simulation {

    private static final String ACCESS_TOKEN = required("accessToken", "CHATAGENT_LOAD_ACCESS_TOKEN");
    private static final String SESSION_FEEDER = required("sessionFeeder", "CHATAGENT_SESSION_FEEDER");
    private static final int REQUESTS = ChatAgentLoadDsl.intProp(
            "entryRequests", "CHATAGENT_ENTRY_REQUESTS", 20);
    private static final Path RESULT_PATH = Path.of(ChatAgentLoadDsl.prop(
            "scenarioResultPath", "CHATAGENT_SCENARIO_RESULT_PATH",
            "tools/gatling/target/gatling/entry-rate-limit-result.json"));
    private static final AtomicInteger ALLOWED = new AtomicInteger();
    private static final AtomicInteger REJECTED = new AtomicInteger();
    private static final AtomicInteger ADMITTED_DOWNSTREAM_FAILED = new AtomicInteger();
    private static final AtomicInteger UNEXPECTED = new AtomicInteger();
    private static final boolean EXPECT_DOWNSTREAM_FAILURE = Boolean.parseBoolean(
            ChatAgentLoadDsl.prop("expectDownstreamFailure", "CHATAGENT_ENTRY_EXPECT_DOWNSTREAM_FAILURE", "false"));

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("ChatAgent-Gatling/1.0")
            .disableCaching();

    private final ScenarioBuilder workload = scenario("Entry rate-limit burst")
            .exec(feed(csv(SESSION_FEEDER).queue()))
            .exec(session -> ChatAgentLoadDsl.prepareChatMessage(
                    session.set("content", "entry limiter proof")))
            .exec(http("Entry limited chat message")
                    .post("/api/chat-messages")
                    .header("Authorization", "Bearer " + ACCESS_TOKEN)
                    .body(StringBody("#{messageBody}"))
                    .check(status().saveAs("entryStatus")))
            .exec(session -> {
                int responseStatus = session.getInt("entryStatus");
                if (responseStatus == 200) {
                    ALLOWED.incrementAndGet();
                } else if (responseStatus == 429) {
                    REJECTED.incrementAndGet();
                } else if (EXPECT_DOWNSTREAM_FAILURE && responseStatus >= 500) {
                    ADMITTED_DOWNSTREAM_FAILED.incrementAndGet();
                } else {
                    UNEXPECTED.incrementAndGet();
                    return session.markAsFailed();
                }
                return session;
            });

    {
        ALLOWED.set(0);
        REJECTED.set(0);
        ADMITTED_DOWNSTREAM_FAILED.set(0);
        UNEXPECTED.set(0);
        setUp(workload.injectOpen(atOnceUsers(REQUESTS))).protocols(httpProtocol);
    }

    @Override
    public void after() {
        writeResult();
        if (ALLOWED.get() + ADMITTED_DOWNSTREAM_FAILED.get() == 0 ||
                REJECTED.get() == 0 || UNEXPECTED.get() != 0) {
            throw new IllegalStateException("Entry limiter did not produce the expected 200/429 envelope.");
        }
    }

    private static String required(String property, String environmentVariable) {
        String value = System.getProperty(property);
        if ((value == null || value.isBlank()) && environmentVariable != null) {
            value = System.getenv(environmentVariable);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required system property or environment variable: " + property);
        }
        return value;
    }

    private static String required(String property) {
        return required(property, null);
    }

    private static void writeResult() {
        String json = """
                {"schemaVersion":1,"submitted":%d,"allowed":%d,"rejected":%d,"admittedDownstreamFailed":%d,"unexpected":%d}
                """.formatted(REQUESTS, ALLOWED.get(), REJECTED.get(),
                ADMITTED_DOWNSTREAM_FAILED.get(), UNEXPECTED.get());
        try {
            Files.createDirectories(RESULT_PATH.getParent());
            Files.writeString(RESULT_PATH, json);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write entry limiter evidence", e);
        }
    }
}
