package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;

/**
 * Binds the immutable execution contract to reflective tool invocations.
 *
 * <p>Spring AI tool methods cannot receive {@link AgentRunContext} directly, so
 * this holder mirrors the existing session, turn, and intent holders at that
 * boundary. {@code ChatAgent} binds and clears it around the runtime call so
 * warn/off contracts never reach reflective tools.</p>
 */
public final class CurrentTurnExecutionContractHolder {

    private static final ThreadLocal<TurnExecutionContract> CURRENT = new ThreadLocal<>();

    private CurrentTurnExecutionContractHolder() {
    }

    public static void set(TurnExecutionContract contract) {
        if (contract == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(contract);
        }
    }

    public static TurnExecutionContract get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
