package com.agentflow.llm;

/**
 * Stable, sanitized failure at the LLM adapter boundary.
 *
 * The code is intentionally a small adapter-level vocabulary; the pure Core
 * module maps any adapter failure to its own MODEL_ERROR classification.
 */
public class ModelClientException extends IllegalStateException {

    public static final String CONFIGURATION_ERROR = "CONFIGURATION_ERROR";
    public static final String PROVIDER_ERROR = "PROVIDER_ERROR";
    public static final String INVALID_MODEL_DECISION = "INVALID_MODEL_DECISION";
    public static final String MALFORMED_MODEL_RESPONSE = "MALFORMED_MODEL_RESPONSE";

    private final String code;

    public ModelClientException(String message) {
        this(PROVIDER_ERROR, message, null);
    }

    public ModelClientException(String message, Throwable cause) {
        this(PROVIDER_ERROR, message, cause);
    }

    public ModelClientException(String code, String message) {
        this(code, message, null);
    }

    public ModelClientException(String code, String message, Throwable cause) {
        super(Sanitizer.message(message), Sanitizer.cause(cause));
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must be non-blank");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Shared redaction for messages that may contain provider response details. */
    static String sanitize(String message) {
        return Sanitizer.message(message);
    }

    private static final class Sanitizer {
        private Sanitizer() {
        }

        static String message(String value) {
            if (value == null || value.isBlank()) {
                return "model provider request failed";
            }
            String sanitized = value
                    .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [redacted]")
                    .replaceAll("(?i)([\"']?(?:api[-_ ]?key|token|secret|password)[\"']?\\s*[:=]\\s*[\"']?)[^,;\\s\"'}]+", "$1[redacted]")
                    .replaceAll("(?i)([?&](?:api[-_ ]?key|token|secret|password)=)[^&\\s]+", "$1[redacted]");
            return sanitized.length() <= 1024 ? sanitized : sanitized.substring(0, 1024);
        }

        static Throwable cause(Throwable value) {
            if (value == null) {
                return null;
            }
            // A provider/JSON exception may carry an entire response body in its message.
            return new IllegalStateException(value.getClass().getSimpleName());
        }
    }
}
