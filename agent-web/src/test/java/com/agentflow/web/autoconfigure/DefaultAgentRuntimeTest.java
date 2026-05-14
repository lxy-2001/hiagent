package com.agentflow.web.autoconfigure;

import com.agentflow.core.AgentEventSink;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.model.ModelPrompt;
import com.agentflow.core.rag.RagDocument;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolResult;
import com.agentflow.web.DefaultAgentRuntime;
import com.agentflow.web.memory.InMemoryShortTermMemory;
import com.agentflow.web.planner.SimpleTaskPlanner;
import com.agentflow.tool.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentRuntimeTest {

    @Test
    void recordsRagToolLlmAndFinalSteps() {
        List<AgentStepRecord> recorded = new ArrayList<>();
        StepRecorder recorder = recorded::add;
        var runtime = new DefaultAgentRuntime(
                new SimpleTaskPlanner(),
                (query, limit) -> List.of(new RagDocument("doc-1", "并发设计", "Redis Lua + MySQL 条件更新", 0.9)),
                new InMemoryToolRegistry(List.of(new FakeTool("interface-draft"), new FakeTool("sql-draft"), new FakeTool("code-draft"))),
                this::fakeAnswer,
                recorder,
                new InMemoryShortTermMemory(),
                6
        );

        AgentResult result = runtime.run(new AgentRequest("task-1", "session-1", "user-1",
                "帮我设计一个秒杀库存扣减接口，生成 SQL 和 Service 伪代码"), AgentEventSink.NOOP);

        assertTrue(result.finalAnswer().contains("最终方案"));
        assertEquals(List.of(AgentStepType.PLANNER, AgentStepType.RAG, AgentStepType.TOOL,
                        AgentStepType.TOOL, AgentStepType.TOOL, AgentStepType.LLM, AgentStepType.FINAL),
                recorded.stream().map(AgentStepRecord::stepType).toList());
    }

    private String fakeAnswer(ModelPrompt prompt) {
        return "最终方案：" + prompt.user().substring(0, Math.min(20, prompt.user().length()));
    }

    private record FakeTool(String name) implements AgentTool {

        @Override
        public String description() {
            return name + " tool";
        }

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.LOW;
        }

        @Override
        public ToolResult execute(String input, ToolContext context) {
            return new ToolResult(name, "output from " + name);
        }
    }
}
