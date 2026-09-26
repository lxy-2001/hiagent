package example.hiagent.plain;

import com.agentflow.core.*;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.runtime.DefaultAgentRuntime;
import com.agentflow.core.tool.*;
import java.util.*;

/** Minimal public-core consumer. The caller owns the model and its credentials. */
public final class PlainConsumer {
    public AgentResult run(AgentModelClient model, String input) {
        ToolRegistry empty = new ToolRegistry() {
            public void register(ToolRegistration tool) { throw new UnsupportedOperationException("read-only registry"); }
            public ToolLookup lookup(String name) { return ToolLookup.unknown(); }
            public List<ToolDefinition> enabledDefinitions() { return List.of(); }
        };
        return new DefaultAgentRuntime(model, empty, null)
                .run(new AgentRequest(UUID.randomUUID().toString(), "plain-session", "caller", input), event -> {});
    }
}
