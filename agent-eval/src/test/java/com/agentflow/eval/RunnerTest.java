package com.agentflow.eval;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RunnerTest {
    private EvalDataset dataset() throws Exception {
        return EvalDataset.load(getClass().getResourceAsStream("/evaluation/dataset-v1.json").readAllBytes());
    }
    @Test void sequentialFreshScopesCloseEvenAfterScenarioThrows() throws Exception {
        var active = new AtomicInteger(); var closed = new AtomicInteger(); var created = new AtomicInteger();
        var order = new ArrayList<String>();
        java.util.function.Supplier<ScenarioDriver> factory = () -> {
            created.incrementAndGet();
            return new ScenarioDriver() {
                public ObservedCase execute(EvalCase c, EvalVariant variant, int repeat) {
                    assertEquals(1, active.incrementAndGet()); order.add(c.id());
                    throw new IllegalStateException("PRIVATE_SENTINEL_007");
                }
                public void close() { active.decrementAndGet(); closed.incrementAndGet(); }
            };
        };
        var runner = new EvaluationRunner(Map.of(EvalCase.Driver.CORE, factory, EvalCase.Driver.HTTP, factory));
        var result = runner.run(dataset(), EvalVariant.baseline(), 1);
        assertEquals(26, result.size()); assertEquals(26, created.get()); assertEquals(26, closed.get());
        assertEquals("C01", order.get(0)); assertEquals("C26", order.get(25));
        assertTrue(result.stream().allMatch(e -> e.score().status() == CaseReport.Status.ERROR));
        assertFalse(result.toString().contains("PRIVATE_SENTINEL_007"));
    }
    @Test void interruptibleCaseTimeoutClosesBeforeNextCase() throws Exception {
        var closed = new AtomicInteger();
        java.util.function.Supplier<ScenarioDriver> factory = () -> new ScenarioDriver() {
            public ObservedCase execute(EvalCase c, EvalVariant variant, int repeat) throws Exception {
                new CountDownLatch(1).await(); return null;
            }
            public void close() { closed.incrementAndGet(); }
        };
        var runner = new EvaluationRunner(Map.of(EvalCase.Driver.CORE, factory, EvalCase.Driver.HTTP, factory),
                Duration.ofMillis(30), Duration.ofMillis(300), Duration.ofSeconds(10));
        var result = runner.run(dataset(), EvalVariant.baseline(), 1);
        assertEquals(26, closed.get());
        assertTrue(result.stream().allMatch(e -> e.score().status() == CaseReport.Status.ERROR));
    }
    @Test void unresponsiveWorkerStopsSuiteAndMarksRemainingNotRun() throws Exception {
        var release = new CountDownLatch(1); var finished = new CountDownLatch(1); var created = new AtomicInteger();
        java.util.function.Supplier<ScenarioDriver> factory = () -> {
            created.incrementAndGet();
            return new ScenarioDriver() {
                public ObservedCase execute(EvalCase c, EvalVariant variant, int repeat) {
                    while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { } }
                    return null;
                }
                public void close() { finished.countDown(); }
            };
        };
        try {
            var runner = new EvaluationRunner(Map.of(EvalCase.Driver.CORE, factory, EvalCase.Driver.HTTP, factory),
                    Duration.ofMillis(30), Duration.ofMillis(30), Duration.ofSeconds(10));
            var result = runner.run(dataset(), EvalVariant.baseline(), 1);
            assertEquals(1, created.get()); assertEquals(CaseReport.Status.ERROR, result.get(0).score().status());
            assertTrue(result.subList(1, 26).stream().allMatch(e -> e.score().status() == CaseReport.Status.NOT_RUN));
        } finally { release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS)); }
    }
    @Test void rejectsMissingDriverAndOutOfRangeRepeatBeforeExecution() throws Exception {
        var runner = new EvaluationRunner(Map.of()); var data = dataset();
        assertThrows(IllegalArgumentException.class, () -> runner.run(data, EvalVariant.baseline(), 1));
        assertThrows(IllegalArgumentException.class, () -> runner.run(data, EvalVariant.baseline(), 11));
    }
}
