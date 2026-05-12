package com.agentflow.core;

import com.agentflow.core.memory.ShortTermMemory;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.ModelPrompt;
import com.agentflow.core.planner.Plan;
import com.agentflow.core.planner.TaskPlanner;
import com.agentflow.core.rag.RagDocument;
import com.agentflow.core.rag.RagRetriever;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.core.tool.ToolResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class DefaultAgentRuntime implements AgentRuntime {

    private final TaskPlanner taskPlanner;
    private final RagRetriever ragRetriever;
    private final ToolRegistry toolRegistry;
    private final AgentModelClient modelClient;
    private final StepRecorder stepRecorder;
    private final ShortTermMemory shortTermMemory;
    private final int maxSteps;

    public DefaultAgentRuntime(
            TaskPlanner taskPlanner,
            RagRetriever ragRetriever,
            ToolRegistry toolRegistry,
            AgentModelClient modelClient,
            StepRecorder stepRecorder,
            ShortTermMemory shortTermMemory,
            int maxSteps
    ) {
        this.taskPlanner = taskPlanner;
        this.ragRetriever = ragRetriever;
        this.toolRegistry = toolRegistry;
        this.modelClient = modelClient;
        this.stepRecorder = stepRecorder;
        this.shortTermMemory = shortTermMemory;
        this.maxSteps = Math.max(1, maxSteps);
    }

    @Override
    public AgentResult run(AgentRequest request, AgentEventSink eventSink) {
        AgentEventSink sink = eventSink == null ? AgentEventSink.NOOP : eventSink;
        AtomicInteger sequence = new AtomicInteger(1);
        List<AgentStepRecord> steps = new ArrayList<>();
        shortTermMemory.appendUserMessage(request.sessionId(), request.input());

        Plan plan = recordStep(request, sink, steps, sequence, AgentStepType.PLANNER, "planner",
                request.input(), () -> taskPlanner.plan(request.input(), toolRegistry.enabledToolNames()));

        List<RagDocument> documents = recordStep(request, sink, steps, sequence, AgentStepType.RAG, "knowledge-retriever",
                request.input(), () -> ragRetriever.retrieve(request.input(), 5));

        ToolContext toolContext = new ToolContext(request.taskId(), request.sessionId(), request.userId(), documents);
        List<ToolResult> toolResults = new ArrayList<>();
        for (String toolName : plan.toolNames().stream().limit(maxSteps).toList()) {
            Optional<AgentTool> tool = toolRegistry.findEnabled(toolName);
            if (tool.isEmpty()) {
                AgentStepRecord skipped = AgentStepRecord.failed(request.taskId(), sequence.getAndIncrement(),
                        AgentStepType.TOOL, toolName, request.input(), "Tool is not enabled or not found.", 0);
                record(request, sink, steps, skipped);
                continue;
            }
            ToolResult result = recordStep(request, sink, steps, sequence, AgentStepType.TOOL, toolName,
                    request.input(), () -> tool.get().execute(request.input(), toolContext));
            toolResults.add(result);
        }

        String answer = recordStep(request, sink, steps, sequence, AgentStepType.LLM, "final-answer",
                request.input(), () -> modelClient.generate(buildFinalPrompt(request, documents, toolResults)));
        shortTermMemory.appendAssistantMessage(request.sessionId(), answer);

        AgentStepRecord finalStep = AgentStepRecord.success(request.taskId(), sequence.getAndIncrement(),
                AgentStepType.FINAL, null, request.input(), answer, 0);
        record(request, sink, steps, finalStep);
        return new AgentResult(request.taskId(), answer, List.copyOf(steps));
    }

    private ModelPrompt buildFinalPrompt(AgentRequest request, List<RagDocument> documents, List<ToolResult> toolResults) {
        String knowledge = documents.stream()
                .map(doc -> "- " + doc.title() + ": " + doc.content())
                .collect(Collectors.joining("\n"));
        String toolOutput = toolResults.stream()
                .map(result -> "## " + result.toolName() + "\n" + result.output())
                .collect(Collectors.joining("\n\n"));
        String system = """
                你是 AgentFlow-Java 的 AI Java 开发助手。
                你需要基于知识库和工具结果，输出工程化、可落地的后端设计方案。
                回答要覆盖接口设计、数据模型、并发一致性、异常处理和可演进建议。
                """;
        String user = """
                用户任务：
                %s

                检索到的知识：
                %s

                工具执行结果：
                %s
                """.formatted(request.input(), knowledge.isBlank() ? "无" : knowledge, toolOutput.isBlank() ? "无" : toolOutput);
        return new ModelPrompt(system, user);
    }

    private <T> T recordStep(
            AgentRequest request,
            AgentEventSink sink,
            List<AgentStepRecord> steps,
            AtomicInteger sequence,
            AgentStepType type,
            String name,
            String input,
            StepAction<T> action
    ) {
        int stepNo = sequence.getAndIncrement();
        Instant start = Instant.now();
        try {
            T result = action.execute();
            long latency = Duration.between(start, Instant.now()).toMillis();
            AgentStepRecord step = AgentStepRecord.success(request.taskId(), stepNo, type, name, input,
                    String.valueOf(result), latency);
            record(request, sink, steps, step);
            return result;
        } catch (RuntimeException ex) {
            long latency = Duration.between(start, Instant.now()).toMillis();
            AgentStepRecord step = AgentStepRecord.failed(request.taskId(), stepNo, type, name, input,
                    ex.getMessage(), latency);
            record(request, sink, steps, step);
            throw ex;
        }
    }

    private void record(AgentRequest request, AgentEventSink sink, List<AgentStepRecord> steps, AgentStepRecord step) {
        steps.add(step);
        stepRecorder.record(step);
        sink.publish(AgentEvent.now(request.taskId(), step.stepType(),
                step.toolName() == null ? step.stepType().name() : step.toolName(),
                step.status() == AgentStepStatus.SUCCESS ? step.output() : step.errorMessage()));
    }

    @FunctionalInterface
    private interface StepAction<T> {
        T execute();
    }
}
