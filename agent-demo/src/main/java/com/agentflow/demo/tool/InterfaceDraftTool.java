package com.agentflow.demo.tool;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolResult;
import com.agentflow.core.tool.ToolSchema;

import java.util.Map;
import java.util.Set;

/** Deterministic, side-effect-free Demo Tool. */
public final class InterfaceDraftTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "interface-draft", "Draft REST API contracts for Java backend requirements.", RiskLevel.LOW,
            new ToolSchema(Map.of("input", ParameterSpec.requiredString(4096)), Set.of("input"), false));

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolArguments arguments, ToolContext context) {
        return ToolResult.success(DEFINITION.name(), """
                推荐接口：

                POST /api/seckill/deduct

                请求体：
                {
                  "activityId": "A1001",
                  "skuId": "SKU1001",
                  "quantity": 1,
                  "requestId": "client-generated-idempotency-key"
                }

                响应状态：
                - SUCCESS：扣减成功
                - ACCEPTED：已进入排队或异步确认
                - SOLD_OUT：库存不足
                - DUPLICATED：重复请求
                - BUSY：限流或系统繁忙
                """);
    }
}
