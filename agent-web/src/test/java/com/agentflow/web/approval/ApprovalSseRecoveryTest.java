package com.agentflow.web.approval;

import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class ApprovalSseRecoveryTest {
    @Test void approvalEventsUseOneWindowAndReconnectCursorWithoutEndingRun() throws Exception {
        var hub = new InMemoryRunEventHub(new ObjectMapper(), 4, 16384, 4096);
        hub.create("run");
        for (var type : java.util.List.of(RunEvent.Type.RUN_CREATED, RunEvent.Type.RUN_STARTED,
                RunEvent.Type.APPROVAL_REQUESTED, RunEvent.Type.APPROVAL_RESOLVED))
            hub.publish("run", new RunEvent.Draft("run", "run", type, Instant.now(), java.util.Map.of("approvalId", "id")));
        var first = hub.open("run", 2); var duplicate = hub.open("run", 2);
        assertThat(first.status()).isEqualTo(RunEventHub.OpenStatus.OPEN);
        try (var a = first.subscription(); var b = duplicate.subscription()) {
            assertThat(a.read(2, Duration.ZERO).frame().event().type()).isEqualTo(RunEvent.Type.APPROVAL_REQUESTED);
            assertThat(b.read(3, Duration.ZERO).frame().event().type()).isEqualTo(RunEvent.Type.APPROVAL_RESOLVED);
            assertThat(a.read(4, Duration.ZERO).status()).isEqualTo(RunEventHub.ReadStatus.IDLE);
        }
        hub.publish("run", new RunEvent.Draft("run", "run", RunEvent.Type.RUN_TERMINATED, Instant.now(), java.util.Map.of("status", "FAILED")));
        hub.markTerminal("run");
        assertThat(hub.open("run", 5).status()).isEqualTo(RunEventHub.OpenStatus.DONE);
        assertThat(hub.open("run", 0).status()).isEqualTo(RunEventHub.OpenStatus.TOO_OLD);
    }
}
