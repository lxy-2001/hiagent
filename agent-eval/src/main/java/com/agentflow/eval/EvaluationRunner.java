package com.agentflow.eval;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Sequential bounded runner: a leaked worker stops the suite instead of contaminating another case. */
public final class EvaluationRunner {
    private final Map<EvalCase.Driver, Supplier<ScenarioDriver>> drivers;
    private final Duration executeLimit;
    private final Duration cleanupLimit;
    private final Duration suiteLimit;

    public EvaluationRunner(Map<EvalCase.Driver, Supplier<ScenarioDriver>> drivers) {
        this(drivers, Duration.ofSeconds(18), Duration.ofSeconds(2), Duration.ofMinutes(10));
    }
    public EvaluationRunner(Map<EvalCase.Driver, Supplier<ScenarioDriver>> drivers, Duration suiteLimit) {
        this(drivers, Duration.ofSeconds(18), Duration.ofSeconds(2), suiteLimit);
    }
    EvaluationRunner(Map<EvalCase.Driver, Supplier<ScenarioDriver>> drivers, Duration executeLimit,
                     Duration cleanupLimit, Duration suiteLimit) {
        this.drivers = Map.copyOf(drivers);
        for (Duration d : List.of(executeLimit, cleanupLimit, suiteLimit)) {
            if (d.isNegative() || d.isZero()) throw new IllegalArgumentException("Invalid evaluation deadline");
        }
        this.executeLimit = executeLimit; this.cleanupLimit = cleanupLimit; this.suiteLimit = suiteLimit;
    }

    public List<RunReport.Execution> run(EvalDataset dataset, EvalVariant variant, int repeat) {
        if (repeat < 1 || repeat > 10 || !drivers.keySet().containsAll(dataset.cases().stream().map(EvalCase::driver).toList())) {
            throw new IllegalArgumentException("INVALID_EVALUATION_CONFIGURATION");
        }
        long started = System.nanoTime();
        boolean stopped = false;
        var results = new ArrayList<RunReport.Execution>();
        var scorer = new EvaluationScorer();
        for (int iteration = 1; iteration <= repeat; iteration++) for (var c : dataset.cases()) {
            if (stopped || System.nanoTime() - started >= suiteLimit.toNanos()) {
                stopped = true; results.add(missing(c, iteration, CaseReport.Status.NOT_RUN)); continue;
            }
            final int attempt = iteration;
            ExecutorService executor = Executors.newSingleThreadExecutor(work -> {
                Thread thread = new Thread(work, "agent-eval-" + c.id() + "-" + attempt);
                thread.setDaemon(true); return thread;
            });
            Future<ObservedCase> future = executor.submit(() -> {
                try (var driver = Objects.requireNonNull(drivers.get(c.driver()).get())) {
                    return Objects.requireNonNull(driver.execute(c, variant, attempt));
                }
            });
            RunReport.Execution execution;
            try {
                long remaining = suiteLimit.toNanos() - (System.nanoTime() - started);
                var observation = future.get(Math.max(1, Math.min(remaining, executeLimit.toNanos())), TimeUnit.NANOSECONDS);
                execution = new RunReport.Execution(c.id(), iteration, observation, scorer.score(c, observation), observation.metrics(), observation.supportingRunIds());
            } catch (InterruptedException e) {
                future.cancel(true); Thread.currentThread().interrupt(); stopped = true;
                execution = missing(c, iteration, CaseReport.Status.ERROR);
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                // Exceptions can contain fixture prompts or credentials; never copy their text into a report.
                future.cancel(true); execution = missing(c, iteration, CaseReport.Status.ERROR);
            } finally {
                executor.shutdownNow();
                try {
                    if (!executor.awaitTermination(cleanupLimit.toNanos(), TimeUnit.NANOSECONDS)) stopped = true;
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); stopped = true; }
            }
            results.add(execution);
        }
        return List.copyOf(results);
    }
    private static RunReport.Execution missing(EvalCase c, int repeat, CaseReport.Status status) {
        return new RunReport.Execution(c.id(), repeat, null, new CaseReport(c.id(), status, List.of()), Map.of(), List.of());
    }
}
