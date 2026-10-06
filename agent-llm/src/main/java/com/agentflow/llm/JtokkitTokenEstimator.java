package com.agentflow.llm;

import com.agentflow.core.context.TokenEstimator;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolDefinition;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Counts the rendered Responses input; the cache retains only small serialized items. */
public final class JtokkitTokenEstimator implements TokenEstimator {
    private final Encoding encoding;
    private final String encodingName;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Integer> cache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> entry) {
            return size() > 128;
        }
    };

    public JtokkitTokenEstimator(String name) {
        encodingName = Objects.requireNonNull(name);
        encoding = Encodings.newDefaultEncodingRegistry().getEncoding(name)
                .orElseThrow(() -> new IllegalArgumentException("unknown token encoding: " + name));
    }

    private long count(String text) {
        if (text.length() > 8192) return encoding.countTokens(text);
        synchronized (cache) {
            return cache.computeIfAbsent(text, encoding::countTokens);
        }
    }

    @Override
    public long estimateInput(List<ModelMessage> messages, List<ToolDefinition> tools) {
        long total = 8;
        for (var message : messages) {
            total = Math.addExact(total, 8 + count(mapper.writeValueAsString(
                    OpenAiResponsesAgentModelClient.message(message, mapper))));
        }
        for (var tool : tools) {
            total = Math.addExact(total, 24 + count(mapper.writeValueAsString(
                    OpenAiResponsesAgentModelClient.tool(tool))));
        }
        return total;
    }

    @Override public String version() {
        return "jtokkit-1.1.0:" + encodingName + ":responses-json-v1";
    }
}
