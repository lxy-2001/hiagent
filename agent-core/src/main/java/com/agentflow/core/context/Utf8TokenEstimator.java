package com.agentflow.core.context;

import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.ToolDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Versioned UTF-8 heuristic, not a provider tokenizer or a billing measurement. */
public final class Utf8TokenEstimator implements TokenEstimator {
    private static final int MESSAGE_LIMIT = 64;
    private static final int TOOL_LIMIT = 32;
    private static final long TEXT_LIMIT = 262144;
    private static final long TOOL_TEXT_LIMIT = 65536;

    @Override
    public long estimateInput(List<ModelMessage> messages, List<ToolDefinition> tools) {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        if (messages.size() > MESSAGE_LIMIT || tools.size() > TOOL_LIMIT) {
            throw new IllegalArgumentException("context item count exceeds limit");
        }
        validateTextLimits(messages, tools);
        Counter estimate = new Counter(true, Long.MAX_VALUE);
        for (ModelMessage message : messages) {
            countMessage(estimate, message, true);
        }
        for (ToolDefinition tool : tools) {
            countTool(estimate, tool, true);
        }
        return estimate.total;
    }

    static void validateTextLimits(List<ModelMessage> messages, List<ToolDefinition> tools) {
        Counter text = new Counter(false, TEXT_LIMIT);
        for (ModelMessage message : messages) {
            countMessage(text, message, false);
        }
        Counter metadata = new Counter(false, TOOL_TEXT_LIMIT);
        for (ToolDefinition tool : tools) {
            countTool(metadata, tool, false);
        }
    }

    @Override
    public String version() {
        return "utf8-v1";
    }

    private static void countMessage(Counter counter, ModelMessage message, boolean overhead) {
        Objects.requireNonNull(message, "message must not be null");
        if (overhead) {
            counter.add(32);
        }
        counter.text(message.role(), false);
        counter.text(message.content(), false);
        counter.text(message.name(), false);
        counter.text(message.toolCallId(), false);
        if (message.toolCall() != null) {
            counter.text(message.toolCall().name(), false);
            counter.text(message.toolCall().callId(), false);
            counter.json(message.toolCall().arguments().values());
        }
    }

    private static void countTool(Counter counter, ToolDefinition tool, boolean overhead) {
        Objects.requireNonNull(tool, "tool must not be null");
        if (overhead) {
            counter.add(128);
        }
        counter.field("name", tool.name());
        counter.field("description", tool.description());
        counter.field("type", "object");
        counter.text("properties", true);
        for (Map.Entry<String, ParameterSpec> entry : tool.schema().properties().entrySet()) {
            if (overhead) {
                counter.add(32);
            }
            counter.text(entry.getKey(), true);
            ParameterSpec spec = entry.getValue();
            counter.field("type", spec.type().name().toLowerCase(Locale.ROOT));
            counter.field("description", spec.description());
            counter.field("nullable", spec.nullable());
            counter.field("minLength", spec.minLength());
            counter.field("maxLength", spec.maxLength());
            counter.field("minimum", spec.minInteger());
            counter.field("maximum", spec.maxInteger());
            counter.field("minimum", spec.minNumber());
            counter.field("maximum", spec.maxNumber());
            counter.field("enum", spec.enumValues());
            counter.field("pattern", spec.pattern());
        }
        counter.field("required", tool.schema().required().stream().sorted().toList());
        counter.field("additionalProperties", tool.schema().allowAdditionalProperties());
    }

    static long checkedAdd(long left, long right) {
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException("estimated quantities must not be negative");
        }
        return Math.addExact(left, right);
    }

    private static final class Counter {
        private final boolean bytes;
        private final long limit;
        private long total;

        private Counter(boolean bytes, long limit) {
            this.bytes = bytes;
            this.limit = limit;
        }

        private void add(long amount) {
            total = checkedAdd(total, amount);
            if (total > limit) {
                throw new IllegalArgumentException("context text exceeds limit");
            }
        }

        private void field(String name, Object value) {
            if (value != null) {
                text(name, true);
                add(2);
                json(value);
            }
        }

        private void json(Object value) {
            if (value instanceof Map<?, ?> map) {
                add(2);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    text((String) entry.getKey(), true);
                    add(2);
                    json(entry.getValue());
                }
            } else if (value instanceof Collection<?> collection) {
                add(2);
                boolean first = true;
                for (Object item : collection) {
                    if (!first) {
                        add(1);
                    }
                    first = false;
                    json(item);
                }
            } else if (value instanceof String string) {
                text(string, true);
            } else {
                text(String.valueOf(value), false);
            }
        }

        private void text(String value, boolean quoted) {
            if (value == null) {
                return;
            }
            if (!bytes) {
                add(value.length());
                return;
            }
            if (quoted) {
                add(2);
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (quoted && (character == '"' || character == '\\'
                        || character == '\b' || character == '\f' || character == '\n'
                        || character == '\r' || character == '\t')) {
                    add(2);
                } else if (quoted && character < 32) {
                    add(6);
                } else if (character < 128) {
                    add(1);
                } else if (character < 2048) {
                    add(2);
                } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    add(4);
                    index++;
                } else if (Character.isSurrogate(character)) {
                    add(quoted ? 6 : 1);
                } else {
                    add(3);
                }
            }
        }
    }
}
