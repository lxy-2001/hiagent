package com.agentflow.demo.tool;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class InterfaceDraftTool implements AgentTool {

    @Override
    public String name() {
        return "interface-draft";
    }

    @Override
    public String description() {
        return "Draft REST API contracts for Java backend requirements.";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public ToolResult execute(String input, ToolContext context) {
        String output = """
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
                """;
        return new ToolResult(name(), output);
    }
}
