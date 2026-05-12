package com.agentflow.core.planner;

import java.util.Set;

public interface TaskPlanner {

    Plan plan(String userInput, Set<String> enabledTools);
}
