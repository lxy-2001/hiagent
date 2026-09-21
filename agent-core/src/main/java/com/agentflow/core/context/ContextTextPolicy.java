package com.agentflow.core.context;

import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded, known-pattern redaction at context and persistence boundaries. */
public final class ContextTextPolicy {
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)([\"']?(?:api[-_ ]?key|token|secret|password)[\"']?\\s*[:=]\\s*[\"']?)[^,;&\\s\"'}]+"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern QUERY_SECRET = Pattern.compile("(?i)([?&](?:api[-_ ]?key|token|secret|password)=)[^&\\s]+");

    public String sanitizeInput(String text) {
        Objects.requireNonNull(text, "text must not be null");
        if (text.length() > 65536) {
            throw new IllegalArgumentException("text exceeds redaction input limit");
        }
        return QUERY_SECRET.matcher(BEARER.matcher(KEY_VALUE_SECRET.matcher(text)
                .replaceAll("$1[redacted]")).replaceAll("Bearer [redacted]"))
                .replaceAll("$1[redacted]");
    }

    public String sanitizeHistory(String text) {
        return sanitizeInput(text);
    }

    public boolean isMemoryValueAllowed(String value) {
        return value != null && !value.isBlank() && value.length() <= 512
                && value.equals(sanitizeInput(value));
    }
}
