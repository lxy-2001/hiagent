package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RunAdmissionGateTest {
    @Test void failedStartupConvergenceClosesAdmissionBeforeAnyCreateWrite() {
        RunPersistence p=mock(RunPersistence.class); when(p.convergeInterrupted(anyString(),eq(100),any())).thenThrow(new IllegalStateException("db"));
        RunCoordinator c=RunStartTest.coordinator((r,s,o)->null,p, Instant.parse("2026-09-15T00:00:00Z"));
        try { c.recoverInterrupted(); assertThatThrownBy(()->c.create("owner","input")).isInstanceOf(RunCoordinator.RunUnavailableException.class); verify(p,never()).createQueued(any()); }
        finally { c.close(); }
    }
}
