package com.agentflow.core.runtime;

public class ModelFailureException extends RuntimeException {
    private final AgentErrorCode errorCode;

    public ModelFailureException(AgentErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ModelFailureException(AgentErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public AgentErrorCode errorCode() {
        return errorCode;
    }
}
