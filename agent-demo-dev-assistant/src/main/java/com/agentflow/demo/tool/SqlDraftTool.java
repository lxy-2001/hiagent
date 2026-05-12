package com.agentflow.demo.tool;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class SqlDraftTool implements AgentTool {

    @Override
    public String name() {
        return "sql-draft";
    }

    @Override
    public String description() {
        return "Generate MySQL table sketches for backend design tasks.";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public ToolResult execute(String input, ToolContext context) {
        String output = """
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
                """;
        return new ToolResult(name(), output);
    }
}
