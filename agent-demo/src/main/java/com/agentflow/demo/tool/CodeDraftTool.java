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
public final class CodeDraftTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "code-draft", "Generate safe Controller and Service pseudocode for a Java backend design.", RiskLevel.LOW,
            new ToolSchema(Map.of("input", ParameterSpec.requiredString(4096)), Set.of("input"), false));

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolArguments arguments, ToolContext context) {
        return ToolResult.success(DEFINITION.name(), """
                ```java
                @RestController
                @RequestMapping("/api/seckill")
                class SeckillController {
                    private final SeckillService seckillService;

                    @PostMapping("/deduct")
                    ApiResult<SeckillResult> deduct(@Valid @RequestBody DeductRequest request,
                                                    @AuthenticationPrincipal LoginUser user) {
                        return ApiResult.ok(seckillService.deduct(user.id(), request));
                    }
                }

                @Service
                class SeckillService {
                    @Transactional
                    public SeckillResult deduct(String userId, DeductRequest request) {
                        // 1. 校验活动状态、幂等键、用户资格
                        // 2. Redis Lua 预扣减库存
                        // 3. 写入扣减流水和订单
                        // 4. MySQL 条件更新兜底，失败则补偿 Redis
                        return SeckillResult.accepted();
                    }
                }
                ```
                """);
    }
}
