package com.agentflow.demo.tool;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class CodeDraftTool implements AgentTool {

    @Override
    public String name() {
        return "code-draft";
    }

    @Override
    public String description() {
        return "Generate safe Controller and Service pseudocode for a Java backend design.";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public ToolResult execute(String input, ToolContext context) {
        String output = """
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
                """;
        return new ToolResult(name(), output);
    }
}
