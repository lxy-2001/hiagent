package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RunLifecycleGateTest {
    @Test void startupScansInBatchesAndBecomesReadyOnlyAfterEmptyBatch() {
        RunPersistence p=mock(RunPersistence.class); when(p.convergeInterrupted("",100,Instant.parse("2026-09-15T00:00:00Z"))).thenReturn(List.of());
        RunCoordinator c=RunStartTest.coordinator((r,s,o)->null,p,Instant.parse("2026-09-15T00:00:00Z"));
        try { assertThat(c.recoverInterrupted()).isTrue(); assertThat(c.availability()).isEqualTo(RunCoordinator.Availability.READY); }
        finally { c.close(); }
    }
}
