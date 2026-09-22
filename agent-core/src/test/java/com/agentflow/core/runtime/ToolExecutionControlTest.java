package com.agentflow.core.runtime;

import com.agentflow.core.cancel.CancellationSignal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionControlTest {
    @Test
    void childCannotExtendParentDeadlineAndCancellationIsShared() {
        var now = new AtomicLong();
        var cancelled = new AtomicBoolean();
        var parent = new ToolExecutionControl(cancelled::get, now::get, Duration.ofSeconds(5));
        now.set(Duration.ofSeconds(4).toNanos());
        var child = parent.child(Duration.ofSeconds(10));
        assertEquals(Duration.ofSeconds(1), child.remainingTime());
        cancelled.set(true);
        assertTrue(child.isCancelled());
        assertThrows(CancellationException.class, child::checkActive);
    }

    @Test
    void elapsedArithmeticSurvivesNanoTimeWrapAndNeverIncreases() {
        var now = new AtomicLong(Long.MAX_VALUE - 5);
        var control = new ToolExecutionControl(CancellationSignal.NONE, now::get, Duration.ofNanos(20));
        now.addAndGet(10);
        assertEquals(Duration.ofNanos(10), control.remainingTime());
        now.addAndGet(-1);
        assertEquals(Duration.ofNanos(10), control.remainingTime());
        now.addAndGet(11);
        assertEquals(Duration.ZERO, control.remainingTime());
        assertThrows(ToolExecutionControl.ExecutionTimedOutException.class, control::checkActive);
    }

    @Test
    void legacyContextGetsIndependentBoundedControlAndCancellationWinsOverTimeout() {
        var a = new com.agentflow.core.tool.ToolContext("t", "s", "u");
        var b = new com.agentflow.core.tool.ToolContext("t", "s", "u");
        assertNotSame(a.control(), b.control());
        assertFalse(a.control().isCancelled());
        assertTrue(a.control().remainingTime().compareTo(Duration.ofSeconds(30)) <= 0);
        assertTrue(a.control().remainingTime().compareTo(Duration.ZERO) > 0);
        var expired = new ToolExecutionControl(() -> true, () -> 0, Duration.ZERO);
        assertThrows(CancellationException.class, expired::checkActive);
        assertThrows(IllegalArgumentException.class, () -> expired.child(Duration.ofNanos(-1)));
    }
}
