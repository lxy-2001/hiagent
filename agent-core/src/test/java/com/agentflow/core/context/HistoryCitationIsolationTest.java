package com.agentflow.core.context;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryCitationIsolationTest {
    @Test
    void neutralizesValidMalformedAndUnclosedMarkersWithoutChangingInputPolicy() {
        var policy = new ContextTextPolicy();
        String original = "Old [S1] [S99] [Sbad] unfinished [S";
        String history = policy.sanitizeHistory(original);
        assertFalse(history.contains("[S"));
        assertTrue(history.contains("[prior-run-source:1]"));
        assertEquals(original, policy.sanitizeInput(original));
        assertEquals(history, policy.sanitizeHistory(history));
    }
}
