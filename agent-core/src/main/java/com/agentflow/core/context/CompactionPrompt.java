package com.agentflow.core.context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned rules only. JSON parsing is supplied by the application's JSON adapter. */
public final class CompactionPrompt {
    public static final String VERSION = "context-compaction-v1";
    private static final Set<String> FIELDS = Set.of("goal", "constraints", "userCorrections", "decisions",
            "completedFacts", "remainingWork", "blockers", "uncertainActions", "references", "continuationState");
    private static final List<String> ARRAYS = List.of("constraints", "userCorrections", "decisions",
            "completedFacts", "remainingWork", "blockers", "uncertainActions");
    private final String rules;

    public CompactionPrompt(String rules) {
        this.rules = Objects.requireNonNull(rules);
        if (rules.isBlank()) throw new IllegalArgumentException("compaction rules required");
    }

    public static CompactionPrompt load() {
        try (InputStream stream = CompactionPrompt.class.getResourceAsStream("/prompts/context-compaction-v1.txt")) {
            if (stream == null) throw new IllegalStateException("compaction prompt resource missing");
            return new CompactionPrompt(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("compaction prompt could not be loaded", failure);
        }
    }

    public String instruction(int target, boolean repair) {
        if (target < 1 || target > 8192) throw new IllegalArgumentException("invalid summary token target");
        return rules + "\nvisible_token_limit=" + target
                + (repair ? "\n上次摘要未完成、格式不符或超过容量。重新总结相同材料，严格遵守字段和长度限制；只允许这一次修正。" : "");
    }

    public static boolean validFields(Map<String, ?> fields) {
        if (fields == null || !fields.keySet().equals(FIELDS)
                || !(fields.get("goal") instanceof String)
                || !(fields.get("continuationState") instanceof String)) return false;
        for (String field : ARRAYS) {
            if (!(fields.get(field) instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) return false;
        }
        if (!(fields.get("references") instanceof List<?> references)) return false;
        for (Object value : references) {
            if (!(value instanceof Map<?, ?> reference)
                    || !reference.keySet().equals(Set.of("kind", "id", "availability", "note"))
                    || !(reference.get("kind") instanceof String kind)
                    || !Set.of("FILE", "ARTIFACT", "SYMBOL").contains(kind)
                    || !(reference.get("id") instanceof String id) || id.isBlank()
                    || !(reference.get("availability") instanceof String availability)
                    || !Set.of("AVAILABLE", "TRUNCATED", "EVICTED", "MISSING", "UNKNOWN").contains(availability)
                    || !(reference.get("note") instanceof String)) return false;
        }
        return true;
    }
}
