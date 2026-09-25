package com.agentflow.core.model;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class UsageSourceTest {
    private final TokenUsage usage = new TokenUsage(4, 2, 6);
    private final ToolCall call = new ToolCall("call-1", "echo", new ToolArguments(Map.of()));

    @Test void legacyConstructorsKeepValuesAndMarkUnknown() {
        ModelDecision answer = new FinalAnswerDecision("d1", "OK", usage);
        ModelDecision tool = new ToolCallDecision("d2", call, usage);
        assertEquals(UsageSource.UNKNOWN, answer.usageSource());
        assertEquals(UsageSource.UNKNOWN, tool.usageSource());
        assertSame(usage, answer.usage());
        assertSame(usage, tool.usage());
    }

    @Test void explicitSourcesKeepValues() {
        for (UsageSource source : UsageSource.values()) {
            assertEquals(source, new FinalAnswerDecision("d1", "OK", usage, source).usageSource());
            assertEquals(source, new ToolCallDecision("d2", call, usage, source).usageSource());
        }
    }

    @Test void nullSourcesAreRejected() {
        assertThrows(NullPointerException.class, () -> new FinalAnswerDecision("d1", "OK", usage, null));
        assertThrows(NullPointerException.class, () -> new ToolCallDecision("d2", call, usage, null));
    }
}
