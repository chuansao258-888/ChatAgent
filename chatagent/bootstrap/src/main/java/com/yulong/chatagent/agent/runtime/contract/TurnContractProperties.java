package com.yulong.chatagent.agent.runtime.contract;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rollout configuration for the Agent Turn Execution Contract.
 *
 * <p>Bound from {@code chatagent.agent.turn-contract.*}. Defaults match the
 * plan's release/rollback narrative: the contract is enabled and runs in warn
 * mode, so it is observed but does not change retrieval/tool behavior. Phase 3
 * switches {@code retrieval-enforcement} to {@code enforce}.</p>
 */
@Component
@ConfigurationProperties(prefix = "chatagent.agent.turn-contract")
@Data
public class TurnContractProperties {

    /**
     * Master switch. When {@code false}, the preparation layer does not build a
     * contract at all (legacy behavior for emergency rollback).
     */
    private boolean enabled = true;

    /**
     * How the contract affects retrieval. {@code off} = legacy behavior,
     * {@code warn} = derive contract and log mismatches, {@code enforce} =
     * contract controls retrieval/tool decisions.
     */
    private String retrievalEnforcement = "warn";

    /**
     * Whether to emit contract debug metadata (enum values and IDs only, never
     * raw content) to logs/test hooks.
     */
    private boolean emitDebugMetadata = false;
}
