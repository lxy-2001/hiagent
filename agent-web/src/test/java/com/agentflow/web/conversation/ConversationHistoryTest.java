package com.agentflow.web.conversation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.when;

class ConversationHistoryTest extends WorkbenchConversationTestSupport {
    @Test void latestPageUsesStableWatermarkAndLoadsOlderRowsInReadingOrder() throws Exception {
        String id = session();
        for (int n : new int[]{1, 3, 5, 7}) turn(id, n, "FAILED", "question " + n);
        mvc.perform(get(url(id)).param("limit", "2"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].turnSequence").value("5"))
                .andExpect(jsonPath("$.items[1].turnSequence").value("7"))
                .andExpect(jsonPath("$.nextBeforeSequence").value("5"))
                .andExpect(jsonPath("$.untilSequence").value("7"));
        turn(id, 9, "FAILED", "new question");
        mvc.perform(get(url(id)).param("limit", "2").param("beforeSequence", "5").param("untilSequence", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].turnSequence").value("1"))
                .andExpect(jsonPath("$.items[1].turnSequence").value("3"))
                .andExpect(jsonPath("$.hasMore").value(false)).andExpect(jsonPath("$.nextBeforeSequence").isEmpty());
    }

    @Test void preservesLongSequencesAndContentUnavailablePositions() throws Exception {
        String id = session();
        turn(id, 9007199254740992L, "SUCCEEDED", "x".repeat(8001));
        turn(id, 9007199254740993L, "FAILED", "token=hidden");
        turn(id, 9007199254740994L, "RUNNING", "not terminal");
        mvc.perform(get(url(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.untilSequence").value("9007199254740994"))
                .andExpect(jsonPath("$.items[0].contentUnavailable").value(true))
                .andExpect(jsonPath("$.items[0].input").isEmpty())
                .andExpect(jsonPath("$.items[1].turnSequence").value("9007199254740993"))
                .andExpect(jsonPath("$.items[1].input").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hidden"))));
    }

    @Test void handlesEmptySessionsAndBoundsWithoutGuessingNumericCursors() throws Exception {
        String id = session();
        mvc.perform(get(url(id))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.untilSequence").value("0")).andExpect(jsonPath("$.hasMore").value(false));
        for (String invalid : new String[]{"-1", "01", "9223372036854775808"}) {
            mvc.perform(get(url(id)).param("untilSequence", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(url(id)).param("limit", "51")).andExpect(status().isBadRequest());
        mvc.perform(get(url(id)).param("beforeSequence", "1")).andExpect(status().isBadRequest());
        mvc.perform(get(url(id)).param("beforeSequence", "2").param("untilSequence", "1")).andExpect(status().isBadRequest());
        mvc.perform(get(url(id)).param("untilSequence", "99")).andExpect(status().isOk()).andExpect(jsonPath("$.untilSequence").value("0"));
    }

    @Test void keepsOwnershipAndRecoveryErrorsExplicit() throws Exception {
        String id = session();
        jdbc.update("update agent_session set user_id='another-owner' where id=?", id);
        mvc.perform(get(url(id))).andExpect(status().isNotFound());
        id = session();
        when(coordinator.isStartupRecoveryBlocked()).thenReturn(true);
        mvc.perform(get(url(id))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_RECOVERING"));
    }

    private String url(String id) { return "/api/agent/sessions/" + id + "/history"; }
}
