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
public final class SqlDraftTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "sql-draft", "Generate MySQL table sketches for backend design tasks.", RiskLevel.LOW,
            new ToolSchema(Map.of("input", ParameterSpec.requiredString(4096)), Set.of("input"), false));

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolArguments arguments, ToolContext context) {
        return ToolResult.success(DEFINITION.name(), """
                ```sql
                create table seckill_inventory (
                    sku_id varchar(64) primary key,
                    available_stock int not null,
                    version bigint not null,
                    updated_at datetime(6) not null
                );

                create table seckill_deduct_record (
                    id varchar(36) primary key,
                    request_id varchar(100) not null unique,
                    user_id varchar(36) not null,
                    sku_id varchar(64) not null,
                    quantity int not null,
                    status varchar(32) not null,
                    created_at datetime(6) not null,
                    updated_at datetime(6) not null
                );

                update seckill_inventory
                   set available_stock = available_stock - ?,
                       version = version + 1,
                       updated_at = now(6)
                 where sku_id = ?
                   and available_stock >= ?;
                ```
                """);
    }
}
