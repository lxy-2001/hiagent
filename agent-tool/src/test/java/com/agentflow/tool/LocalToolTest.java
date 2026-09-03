package com.agentflow.tool;

import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolResultStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalToolTest {
    private final ToolContext context = new ToolContext("task", "session", "user");

    @Test
    void uppercaseTextIsDeterministicAndInMemory() {
        var tool = new UppercaseTextTool();

        var result = tool.execute(new ToolArguments(Map.of("text", "Hi Agent")), context);

        assertEquals("uppercase-text", result.toolName());
        assertEquals(ToolResultStatus.SUCCESS, result.status());
        assertEquals("HI AGENT", result.output());
    }

    @Test
    void textStatsReturnsStableBoundedSummary() {
        var tool = new TextStatsTool();

        var result = tool.execute(new ToolArguments(Map.of("text", "hello world\nagent")), context);

        assertEquals("text-stats", result.toolName());
        assertEquals(ToolResultStatus.SUCCESS, result.status());
        assertTrue(result.output().contains("chars=17"));
        assertTrue(result.output().contains("words=3"));
        assertTrue(result.output().contains("lines=2"));
    }
}
