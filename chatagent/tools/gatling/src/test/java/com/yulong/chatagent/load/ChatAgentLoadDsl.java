package com.yulong.chatagent.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.status;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;

import java.util.UUID;

final class ChatAgentLoadDsl {

    static final String BASE_URL = prop("baseUrl", "CHATAGENT_BASE_URL", "http://localhost:8080");
    static final String USER_PREFIX = prop("userPrefix", "CHATAGENT_LOAD_USER_PREFIX", "load-user");
    static final String PASSWORD = prop("password", "CHATAGENT_LOAD_PASSWORD", "LoadTest@123456");
    static final int CONCURRENT_USERS = intProp("concurrentUsers", "CHATAGENT_LOAD_CONCURRENT_USERS", 200);
    static final int RAMP_SECONDS = intProp("rampSeconds", "CHATAGENT_LOAD_RAMP_SECONDS", 60);
    static final int HOLD_SECONDS = intProp("holdSeconds", "CHATAGENT_LOAD_HOLD_SECONDS", 300);
    static final int CHAT_PACE_MILLIS = intProp("paceMillis", "CHATAGENT_LOAD_PACE_MILLIS", 400);
    static final int SSE_CONNECTIONS = intProp("sseConnections", "CHATAGENT_SSE_CONNECTIONS", 500);

    private ChatAgentLoadDsl() {
    }

    static ChainBuilder registerUniqueUser() {
        return exec(ChatAgentLoadDsl::seedUserCredentials)
                .exec(http("Auth register")
                        .post("/api/auth/register")
                        .body(StringBody("#{authBody}"))
                        .check(status().is(200))
                        .check(jsonPath("$.code").is("200"))
                        .check(jsonPath("$.data.accessToken").saveAs("accessToken")))
                .exitHereIfFailed();
    }

    static ChainBuilder createChatSession(String titlePrefix) {
        return exec(session -> session.set(
                        "createSessionBody",
                        "{\"title\":\"" + json(titlePrefix + "-" + shortId()) + "\"}"
                ))
                .exec(http("Create chat session")
                        .post("/api/chat-sessions")
                        .header("Authorization", "Bearer #{accessToken}")
                        .body(StringBody("#{createSessionBody}"))
                        .check(status().is(200))
                        .check(jsonPath("$.code").is("200"))
                        .check(jsonPath("$.data").saveAs("sessionId")))
                .exitHereIfFailed();
    }

    static Session prepareChatMessage(Session session) {
        String content = session.contains("content") ? session.getString("content") : "Hello";
        String body = "{"
                + "\"sessionId\":\"" + json(session.getString("sessionId")) + "\","
                + "\"turnId\":\"" + UUID.randomUUID() + "\","
                + "\"role\":\"user\","
                + "\"content\":\"" + json(content) + "\""
                + "}";
        return session.set("messageBody", body);
    }

    static String prop(String key, String envKey, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(envKey);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    static int intProp(String key, String envKey, int defaultValue) {
        String value = prop(key, envKey, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static Session seedUserCredentials(Session session) {
        String username = USER_PREFIX + "-" + shortId();
        String body = "{"
                + "\"username\":\"" + json(username) + "\","
                + "\"password\":\"" + json(PASSWORD) + "\""
                + "}";
        return session
                .set("username", username)
                .set("password", PASSWORD)
                .set("authBody", body);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
