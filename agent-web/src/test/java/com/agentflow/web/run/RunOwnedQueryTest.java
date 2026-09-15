package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunOwnedQueryTest {
    @Test void unknownAndForeignOwnersShareNotFoundAndStepsRequireOwnerFirst() {
        RunPersistence p=mock(RunPersistence.class); when(p.getOwned(anyString(),anyString())).thenReturn(Optional.empty());
        RunCoordinator c=RunStartTest.coordinator((r,s,o)->null,p,Instant.parse("2026-09-15T00:00:00Z"));
        try { assertThatThrownBy(()->c.getOwned("foreign","task")).isInstanceOf(RunCoordinator.RunNotFoundException.class);
            assertThatThrownBy(()->c.getOwnedSteps("foreign","task")).isInstanceOf(RunCoordinator.RunNotFoundException.class); verify(p,never()).getOwnedSteps(anyString(),anyString());
        } finally { c.close(); }
    }
}
