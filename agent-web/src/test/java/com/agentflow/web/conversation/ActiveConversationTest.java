package com.agentflow.web.conversation;

import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.when;

class ActiveConversationTest extends WorkbenchConversationTestSupport {
    @Test void exposesOnlyTheCurrentOwnedActiveTaskInDetailAndList() throws Exception {
        String id = session();
        String task = turn(id, 1, "WAITING_APPROVAL", "approve me");
        mvc.perform(get("/api/agent/sessions/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activeTaskId").value(task));
        mvc.perform(get("/api/agent/sessions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].activeTaskId").value(task));
        jdbc.update("update agent_task set status='CANCELLED' where id=?", task);
        mvc.perform(get("/api/agent/sessions/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activeTaskId").isEmpty());
    }

    @Test void inconsistentMultipleActiveTasksFailInsteadOfPickingOne() throws Exception {
        String id = session();
        turn(id, 1, "QUEUED", "first");
        turn(id, 2, "RUNNING", "second");
        mvc.perform(get("/api/agent/sessions/" + id)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PERSISTENCE_UNAVAILABLE"));
        mvc.perform(get("/api/agent/sessions")).andExpect(status().isServiceUnavailable());
    }

    @Test void recoveryDoesNotPublishUnstableActiveIdsAndOwnerCheckRunsFirst() throws Exception {
        String id = session();
        when(coordinator.isStartupRecoveryBlocked()).thenReturn(true);
        mvc.perform(get("/api/agent/sessions/" + id)).andExpect(status().isServiceUnavailable());
        jdbc.update("update agent_session set user_id='another-owner' where id=?", id);
        mvc.perform(get("/api/agent/sessions/" + id)).andExpect(status().isNotFound());
    }
}
