package com.agentflow.core.approval;

import com.agentflow.core.runtime.ToolExecutionControl;

/** Waits for a durable decision and claims dispatch; never executes a tool. */
public interface ApprovalGate {
    ApprovalResolution await(ApprovalRequest request, ToolExecutionControl control);
    boolean claimDispatch(ApprovalRequest request, ToolExecutionControl control);
}
