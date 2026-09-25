package com.agentflow.web.approval;

import com.agentflow.core.runtime.*;
import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ApprovalEventsTest {
    @Test void failedCreateNeverPublishesRequested() {
        var persistence = mock(ApprovalPersistence.class);
        var service = new ApprovalService(persistence, Clock.systemUTC(), System::nanoTime);
        var request = ApprovalFixtures.request();
        var run = ApprovalFixtures.control();
        when(persistence.create(request)).thenThrow(new IllegalStateException("storage unavailable"));
        var events = new java.util.ArrayList<RunEvent.Draft>();
        assertThatThrownBy(() -> service.begin(request, run,
                new ToolExecutionControl(run, TimeSource.system(), Duration.ofSeconds(30)), events::add)).isInstanceOf(RuntimeException.class);
        assertThat(events).isEmpty();
        assertThat(run.claimApprovalIo()).isEmpty();
        assertThat(run.isApprovalUncertain()).isTrue();
    }
}
