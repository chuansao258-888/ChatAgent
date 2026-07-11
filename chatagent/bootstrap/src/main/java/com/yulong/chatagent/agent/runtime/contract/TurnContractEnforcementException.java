package com.yulong.chatagent.agent.runtime.contract;

/** Terminal fail-closed violation of the enforced turn-contract boundary. */
public class TurnContractEnforcementException extends IllegalArgumentException {

    public TurnContractEnforcementException(String message) {
        super(message);
    }

    public TurnContractEnforcementException(String message, Throwable cause) {
        super(message, cause);
    }
}
