package com.yulong.chatagent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.contract.QuerySpec;
import com.yulong.chatagent.agent.runtime.contract.RetrievalRoutePlan;
import com.yulong.chatagent.agent.runtime.contract.RetrievalSource;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Plans deterministic contract-authorized retrieval calls for both runtime modes. */
public final class RetrievalToolCallPlanner {

    public static final String TOOL_NAME = "SessionFileSearchTool";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<AssistantMessage.ToolCall> plan(TurnExecutionContract contract,
                                                String fallbackQuery,
                                                int maxToolCalls) {
        List<Request> requests = requests(contract, fallbackQuery);
        if (requests.size() > Math.max(maxToolCalls, 1)) {
            throw new IllegalStateException(
                    "Contract retrieval routes exceed the per-step tool-call budget");
        }
        return requests.stream().map(this::toToolCall).toList();
    }

    private List<Request> requests(TurnExecutionContract contract, String fallbackQuery) {
        if (contract == null || contract.queryPlan() == null) {
            return List.of(new Request(fallbackQuery, null));
        }
        if (contract.retrieval() != null && !contract.retrieval().routes().isEmpty()) {
            List<QuerySpec> specs = contract.queryPlan().queries();
            List<Request> requests = new ArrayList<>();
            for (RetrievalRoutePlan route : contract.retrieval().routes()) {
                if (route.queryIndex() < 0 || route.queryIndex() >= specs.size()) {
                    throw new IllegalStateException("Contract retrieval route references an invalid query");
                }
                QuerySpec spec = specs.get(route.queryIndex());
                if (spec == null || !isRagSource(spec.source()) || spec.source() != route.source()
                        || !StringUtils.hasText(spec.text())) {
                    throw new IllegalStateException("Contract retrieval route does not match its query plan");
                }
                requests.add(new Request(spec.text().trim(), route.key()));
            }
            if (!requests.isEmpty()) {
                return List.copyOf(requests);
            }
        }
        List<Request> planned = contract.queryPlan().queries().stream()
                .filter(Objects::nonNull)
                .filter(query -> isRagSource(query.source()))
                .filter(query -> StringUtils.hasText(query.text()))
                .map(query -> new Request(query.text().trim(), null))
                .toList();
        return planned.isEmpty() ? List.of(new Request(fallbackQuery, null)) : planned;
    }

    private AssistantMessage.ToolCall toToolCall(Request request) {
        try {
            Map<String, String> values = StringUtils.hasText(request.routeKey())
                    ? Map.of("query", request.query(), "routeKey", request.routeKey())
                    : Map.of("query", request.query());
            return new AssistantMessage.ToolCall(
                    "session-file-" + UUID.randomUUID(),
                    "function",
                    TOOL_NAME,
                    OBJECT_MAPPER.writeValueAsString(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize mandatory retrieval query", exception);
        }
    }

    private boolean isRagSource(RetrievalSource source) {
        return source == RetrievalSource.SESSION_FILES
                || source == RetrievalSource.INTENT_KB
                || source == RetrievalSource.AGENT_DEFAULT_KB
                || source == RetrievalSource.MIXED_SESSION_AND_KB;
    }

    private record Request(String query, String routeKey) {
    }
}
