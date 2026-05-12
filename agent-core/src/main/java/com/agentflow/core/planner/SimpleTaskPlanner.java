package com.agentflow.core.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SimpleTaskPlanner implements TaskPlanner {

    private static final String INTERFACE_TOOL = "interface-draft";
    private static final String SQL_TOOL = "sql-draft";
    private static final String CODE_TOOL = "code-draft";

    @Override
    public Plan plan(String userInput, Set<String> enabledTools) {
        List<String> toolNames = new ArrayList<>();
        String normalized = userInput == null ? "" : userInput.toLowerCase();
        if (enabledTools.contains(INTERFACE_TOOL)) {
            toolNames.add(INTERFACE_TOOL);
        }
        if ((normalized.contains("sql") || normalized.contains("表") || normalized.contains("mysql")
                || normalized.contains("库存")) && enabledTools.contains(SQL_TOOL)) {
            toolNames.add(SQL_TOOL);
        }
        if ((normalized.contains("代码") || normalized.contains("controller") || normalized.contains("service")
                || normalized.contains("接口")) && enabledTools.contains(CODE_TOOL)) {
            toolNames.add(CODE_TOOL);
        }
        if (toolNames.isEmpty()) {
            toolNames.addAll(enabledTools.stream().limit(2).toList());
        }
        return new Plan("识别为 Java 后端设计任务，先检索知识，再调用安全的设计草图工具。", List.copyOf(toolNames));
    }
}
