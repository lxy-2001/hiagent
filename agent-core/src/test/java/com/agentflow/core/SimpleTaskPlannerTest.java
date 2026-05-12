package com.agentflow.core;

import com.agentflow.core.planner.SimpleTaskPlanner;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleTaskPlannerTest {

    @Test
    void plansBackendDesignTools() {
        var planner = new SimpleTaskPlanner();

        var plan = planner.plan("帮我设计一个秒杀库存扣减接口，生成 SQL 和 Service 伪代码",
                Set.of("interface-draft", "sql-draft", "code-draft"));

        assertEquals(java.util.List.of("interface-draft", "sql-draft", "code-draft"), plan.toolNames());
    }
}
