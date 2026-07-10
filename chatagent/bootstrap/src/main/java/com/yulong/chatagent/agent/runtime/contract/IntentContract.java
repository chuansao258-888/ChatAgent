package com.yulong.chatagent.agent.runtime.contract;

import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;

/**
 * The intent outcome frozen into the execution contract.
 *
 * <p>Carries the resolved {@link IntentResolution} plus the understanding-layer
 * label so downstream consumers do not have to re-read both objects.</p>
 *
 * @param kind        routing-level intent kind (KB/TOOL/SYSTEM/CLARIFY)
 * @param label       understanding-level primary intent label
 * @param resolution  resolved intent (may be {@code null} for passthrough turns)
 * @param pathLabel   human-readable intent path label for logs and debugging
 */
public record IntentContract(
        IntentKind kind,
        IntentLabel label,
        IntentResolution resolution,
        String pathLabel
) {
}
