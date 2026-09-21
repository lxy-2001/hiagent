package com.agentflow.core.context;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Defensive, immutable snapshot of bounded candidates selected during Run preparation. */
public record ContextSeed(long historyThroughTurn, List<ConversationTurn> turns,
                          List<ConfirmedMemory> memories, boolean candidateLimited,
                          int sourceDiscardedCount) {
    public ContextSeed {
        if (historyThroughTurn < 0 || sourceDiscardedCount < 0) {
            throw new IllegalArgumentException("context counters must not be negative");
        }
        Objects.requireNonNull(turns, "turns must not be null");
        Objects.requireNonNull(memories, "memories must not be null");
        if (turns.size() > 20 || memories.size() > 2) {
            throw new IllegalArgumentException("context candidate limit exceeded");
        }
        turns = List.copyOf(turns);
        memories = List.copyOf(memories);
        long previous = 0;
        Set<String> runIds = new HashSet<>();
        for (ConversationTurn turn : turns) {
            if (turn.turnSequence() <= previous || turn.turnSequence() > historyThroughTurn
                    || !runIds.add(turn.runId())) {
                throw new IllegalArgumentException("turns must have unique IDs and strictly ascending sequences");
            }
            previous = turn.turnSequence();
        }
        Set<String> memoryKeys = new HashSet<>();
        for (ConfirmedMemory memory : memories) {
            if (!memoryKeys.add(memory.key())) {
                throw new IllegalArgumentException("memory keys must be unique");
            }
        }
    }

    public static ContextSeed empty() {
        return new ContextSeed(0, List.of(), List.of(), false, 0);
    }
}

