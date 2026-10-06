package com.agentflow.web.context;

import com.agentflow.core.context.TokenEstimator;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Counts opaque provider-state capacity without fetching its private body. */
public final class ContinuationTokenEstimator implements TokenEstimator {
    private final TokenEstimator visible;
    private final JdbcTemplate jdbc;
    private final String provider, model;
    public ContinuationTokenEstimator(TokenEstimator visible, JdbcTemplate jdbc, String provider, String model) {
        this.visible = Objects.requireNonNull(visible); this.jdbc = Objects.requireNonNull(jdbc);
        this.provider = Objects.requireNonNull(provider); this.model = Objects.requireNonNull(model);
    }
    public long estimateInput(List<ModelMessage> messages, List<ToolDefinition> tools) {
        long total = visible.estimateInput(messages, tools);
        var ids = messages.stream().map(ModelMessage::adapterStateRef).filter(Objects::nonNull).distinct().toList();
        for (int from = 0; from < ids.size(); from += 128) {
            var page = ids.subList(from, Math.min(from + 128, ids.size()));
            var found = new HashSet<String>();
            var counts = jdbc.query("select id,provider,model,reasoning_token_hint,payload_bytes from agent_responses_continuation where id in ("
                    + String.join(",", Collections.nCopies(page.size(), "?")) + ")", (rs, row) -> {
                found.add(rs.getString(1));
                if (!provider.equals(rs.getString(2)) || !model.equals(rs.getString(3))) return 0L;
                Number hint = (Number) rs.getObject(4);
                return hint == null ? rs.getLong(5) : hint.longValue();
            }, page.toArray());
            if (!found.containsAll(page)) throw new com.agentflow.core.context.ContextSessionException("CONTINUATION_UNAVAILABLE");
            for (long count : counts) total = Math.addExact(total, Math.max(0, count));
        }
        return total;
    }
    public String version() { return visible.version() + "+opaque-v1"; }
}
