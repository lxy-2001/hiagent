package com.agentflow.llm;

/**
 * Signals a configuration or provider failure at the LLM adapter boundary.
 *
 * The exception intentionally lives in the adapter module so the pure core
 * contracts do not depend on HTTP, JSON, or vendor-specific types.
 */
public class ModelClientException extends IllegalStateException {

    public ModelClientException(String message) {
        super(message);
    }

    public ModelClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
