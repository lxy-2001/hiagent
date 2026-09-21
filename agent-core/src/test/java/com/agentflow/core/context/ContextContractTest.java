package com.agentflow.core.context;

import com.agentflow.core.AgentRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ContextContractTest {
    @Test void rejectsCurrentRunAndMismatchedMemoryType() {
        var seed = new ContextSeed(1, List.of(new ConversationTurn("r", 1, "u", "a")), List.of(), false, 0);
        assertThrows(IllegalArgumentException.class, () -> new AgentRequest("r", "s", "u", "input", seed));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmedMemory(
                "preferred_language", "PROJECT_FACT", "Java", 1, "USER_CONFIRMED"));
    }

    @Test void enforcesTurnTextLimitsWithoutSplittingUnicode() {
        assertThrows(IllegalArgumentException.class, () -> new ConversationTurn("r", 1, "x".repeat(8001), "answer"));
        assertThrows(IllegalArgumentException.class, () -> new ConversationTurn("r", 1, "input", "x".repeat(65537)));
        assertThrows(IllegalArgumentException.class, () -> new ConversationTurn("r", 1, "😀".repeat(4001), "answer"));
        assertEquals(8000, new ConversationTurn("r", 1, "😀".repeat(4000), "answer").userText().length());
    }

    @Test void boundsCountsAndRejectsDuplicatesOrUnorderedHistory() {
        var first = new ConversationTurn("r1", 1, "same", "same");
        var second = new ConversationTurn("r2", 2, "same", "same");
        assertEquals(2, new ContextSeed(2, List.of(first, second), List.of(), false, 0).turns().size());
        assertThrows(IllegalArgumentException.class, () -> new ContextSeed(2, List.of(second, first), List.of(), false, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContextSeed(2,
                List.of(first, new ConversationTurn("r1", 2, "u", "a")), List.of(), false, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContextSeed(21, Collections.nCopies(21, first), List.of(), true, 0));
        var memory = new ConfirmedMemory("project_stack", "PROJECT_FACT", "Java", 1, "USER_CONFIRMED");
        assertThrows(IllegalArgumentException.class, () -> new ContextSeed(0, List.of(), List.of(memory, memory), false, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContextSeed(0, List.of(), Collections.nCopies(3, memory), false, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmedMemory("project_stack", "PROJECT_FACT", "x", 0, "USER_CONFIRMED"));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmedMemory("project_stack", "PROJECT_FACT", "x", 1, "MODEL"));
    }

    @Test void copiesCallerListsAndValidatesApplicationPolicy() {
        var turns = new ArrayList<>(List.of(new ConversationTurn("r", 1, "u", "a")));
        var seed = new ContextSeed(1, turns, List.of(), false, 0);
        turns.clear();
        assertEquals(1, seed.turns().size());
        assertEquals(16384, ContextPolicy.defaults().windowLimit());
        assertThrows(IllegalArgumentException.class, () -> new ContextPolicy("s", "版本", 1));
        assertThrows(IllegalArgumentException.class, () -> new ContextPolicy("s".repeat(2049), "v", 1));
        assertThrows(IllegalArgumentException.class, () -> new ContextPolicy("s", "v", 131073));
    }
    @Test void validatesImmutableBoundedSeedAndLegacyRequest() {
        var turn = new ConversationTurn("r1", 1, "hello", "world");
        var memory = new ConfirmedMemory(ConfirmedMemory.PROJECT_STACK, "PROJECT_FACT", "Java", 1, ConfirmedMemory.USER_CONFIRMED);
        var seed = new ContextSeed(2, List.of(turn), List.of(memory), false, 0);
        assertEquals(1, seed.turns().size());
        assertThrows(UnsupportedOperationException.class, () -> seed.turns().clear());
        assertEquals(ContextSeed.empty(), new AgentRequest("r", "s", "u", "input").contextSeed());
        assertThrows(IllegalArgumentException.class, () -> new ContextSeed(1,
                List.of(new ConversationTurn("r", 2, "u", "a")), List.of(), false, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmedMemory("other", "x", "v", 1, ConfirmedMemory.USER_CONFIRMED));
    }
}
