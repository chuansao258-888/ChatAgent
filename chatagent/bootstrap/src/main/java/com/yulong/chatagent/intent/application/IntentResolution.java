package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Single source of truth produced by turn preparation and consumed by runtime execution.
 */
public record IntentResolution(
        IntentKind kind,
        List<IntentNodeDTO> path,
        List<String> scopedKbIds,
        ScopePolicy scopePolicy,
        List<String> allowedTools,
        String systemPromptOverride
) {

    public IntentResolution {
        path = path == null ? List.of() : List.copyOf(path);
        scopedKbIds = scopedKbIds == null ? List.of() : List.copyOf(scopedKbIds);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }

    public String pathLabel() {
        return path.stream()
                .map(IntentNodeDTO::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(" > "));
    }
}

