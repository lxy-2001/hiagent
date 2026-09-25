package com.agentflow.core.tool;

import com.agentflow.core.approval.ApprovalStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.agentflow.core.tool.ToolPolicyDecision.*;
import static com.agentflow.core.tool.ToolInvocationRecord.Outcome.*;

class ToolInvocationRecordValidationTest {
    private final Instant created = Instant.parse("2026-09-25T00:00:00Z");
    private ToolInvocationRecord record(String id, Action action, RiskLevel risk, Effect effect,
            UUID approval, ApprovalStatus status, int count, Instant dispatch, ToolInvocationRecord.Outcome outcome) {
        return new ToolInvocationRecord("006-v1",id,"call","lookup",null,null,"a".repeat(64),"b".repeat(64),
                "c".repeat(64),risk,effect,action,created,dispatch,approval,status,null,count,null,outcome,null);
    }
    @Test void missingGateAndApprovedWithoutDispatchRemainRepresentable() {
        assertEquals("demo-task",record("demo-task",Action.REQUIRE_APPROVAL,RiskLevel.HIGH,Effect.WRITE,null,null,0,null,NOT_DISPATCHED).runId());
        assertDoesNotThrow(()->record("demo-task",Action.REQUIRE_APPROVAL,RiskLevel.HIGH,Effect.WRITE,UUID.randomUUID(),ApprovalStatus.APPROVED,0,null,NOT_DISPATCHED));
    }
    @Test void rejectsUnauthorizedDispatch() {
        assertThrows(IllegalArgumentException.class,()->record("run",Action.REQUIRE_APPROVAL,RiskLevel.HIGH,Effect.WRITE,null,null,1,created,UNKNOWN));
        assertThrows(IllegalArgumentException.class,()->record("run",Action.REQUIRE_APPROVAL,RiskLevel.HIGH,Effect.WRITE,UUID.randomUUID(),ApprovalStatus.REJECTED,1,created,UNKNOWN));
        assertThrows(IllegalArgumentException.class,()->record("run",Action.DENY,RiskLevel.LOW,Effect.READ_ONLY,null,null,1,created,SUCCEEDED));
        assertThrows(IllegalArgumentException.class,()->record("run",Action.ALLOW,RiskLevel.HIGH,Effect.READ_ONLY,null,null,1,created,SUCCEEDED));
        assertThrows(IllegalArgumentException.class,()->record("run",Action.ALLOW,RiskLevel.LOW,Effect.WRITE,null,null,1,created,SUCCEEDED));
    }
    @Test void rejectsBrokenApprovalAndDispatchBindings() {
        assertThrows(IllegalArgumentException.class,()->record("run",Action.REQUIRE_APPROVAL,RiskLevel.LOW,Effect.READ_ONLY,UUID.randomUUID(),null,0,null,NOT_DISPATCHED));
        assertThrows(IllegalArgumentException.class,()->record("run",Action.ALLOW,RiskLevel.LOW,Effect.READ_ONLY,UUID.randomUUID(),ApprovalStatus.APPROVED,1,created,SUCCEEDED));
        assertThrows(IllegalArgumentException.class,()->record("run",Action.ALLOW,RiskLevel.LOW,Effect.READ_ONLY,null,null,0,created,NOT_DISPATCHED));
        assertThrows(IllegalArgumentException.class,()->record("run",Action.ALLOW,RiskLevel.LOW,Effect.READ_ONLY,null,null,1,null,UNKNOWN));
        assertThrows(IllegalArgumentException.class,()->record("run",Action.ALLOW,RiskLevel.LOW,Effect.READ_ONLY,null,null,0,null,SUCCEEDED));
        assertThrows(IllegalArgumentException.class,()->record(" ",Action.DENY,RiskLevel.LOW,Effect.READ_ONLY,null,null,0,null,NOT_DISPATCHED));
    }
    @Test void publicFieldsRequired() {
        assertThrows(RuntimeException.class,()->new ToolInvocationRecord("006-v1","run","call","lookup",null,null,null,"b".repeat(64),"c".repeat(64),RiskLevel.LOW,Effect.READ_ONLY,Action.ALLOW,created,null,null,null,null,0,null,NOT_DISPATCHED,null));
        assertThrows(RuntimeException.class,()->new ToolInvocationRecord("006-v1","run","call","lookup",null,null,"a".repeat(64),"b".repeat(64),"c".repeat(64),RiskLevel.LOW,Effect.READ_ONLY,Action.ALLOW,null,null,null,null,null,0,null,NOT_DISPATCHED,null));
    }
}
