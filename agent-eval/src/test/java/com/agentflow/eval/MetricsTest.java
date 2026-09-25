package com.agentflow.eval;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.UsageSource;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {
    private EvaluationMetrics.Attempt attempt(UsageSource source, int prompt, int completion) {
        return new EvaluationMetrics.Attempt(new TokenUsage(prompt, completion, prompt + completion), source, 0);
    }
    private PriceSnapshot price() {
        return new PriceSnapshot("test-price", "local", "model", "USD", LocalDate.of(2026, 9, 25),
                "synthetic", new BigDecimal("2.5"), new BigDecimal("10"));
    }
    @Test void exceptionsPartialAndMixedSourcesNeverBecomeKnownCost() {
        var failed = new EvaluationMetrics.Attempt(null, UsageSource.UNKNOWN, 3);
        for (var calls : List.of(List.of(failed), List.of(attempt(UsageSource.REPORTED, 2, 1), failed),
                List.of(attempt(UsageSource.REPORTED, 2, 1), attempt(UsageSource.FIXTURE, 2, 1)))) {
            var usage = EvaluationMetrics.usage(calls);
            assertEquals("UNKNOWN", usage.status());
            assertNull(usage.promptTokens());
            assertNull(EvaluationMetrics.cost(usage, "local", "model", price()).amount());
        }
        assertEquals(1, EvaluationMetrics.usage(List.of(failed)).unknownCalls());
    }
    @Test void realZeroIsKnownButFixturesAreNotBillable() {
        var zero = EvaluationMetrics.usage(List.of(attempt(UsageSource.REPORTED, 0, 0)));
        assertEquals(0L, zero.promptTokens());
        assertEquals("0.00000000", EvaluationMetrics.cost(zero, "local", "model", price()).amount());
        var fixture = EvaluationMetrics.usage(List.of(attempt(UsageSource.FIXTURE, 0, 0)));
        assertEquals("UNKNOWN", EvaluationMetrics.cost(fixture, "local", "model", price()).status());
        assertEquals("NOT_APPLICABLE", EvaluationMetrics.usage(List.of()).status());
    }
    @Test void decimalCostRequiresMatchingPriceAndUsesLongTokenSums() {
        var usage = EvaluationMetrics.usage(List.of(attempt(UsageSource.REPORTED, 2000000000, 0),
                attempt(UsageSource.REPORTED, 2000000000, 0)));
        assertEquals(4000000000L, usage.promptTokens());
        assertEquals("10000.00000000", EvaluationMetrics.cost(usage, "local", "model", price()).amount());
        assertNull(EvaluationMetrics.cost(usage, "other", "model", price()).amount());
        assertNull(EvaluationMetrics.cost(usage, "local", "model", null).amount());
    }
    @Test void nearestRankKeepsEmptyUnknownAndSmallSamplesExplicit() {
        assertNull(EvaluationMetrics.distribution(List.of(), "ms", "CORE").p50());
        var d = EvaluationMetrics.distribution(List.of(10.0, 0.0, 30.0, 20.0), "ms", "CORE");
        assertEquals(4, d.n()); assertEquals(10.0, d.p50()); assertEquals(30.0, d.p95());
        assertTrue(d.smallSample());
        assertThrows(IllegalArgumentException.class, () -> EvaluationMetrics.distribution(List.of(Double.NaN), "ms", "CORE"));
    }
    @Test void rejectsImpossibleUsageAndMalformedPrices() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new EvaluationMetrics.Usage("REPORTED", null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new EvaluationMetrics.Usage("UNKNOWN", 0L, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new EvaluationMetrics.Usage("REPORTED", -1L, 0L, 0));
        byte[] bytes = getClass().getResourceAsStream("/evaluation/price-example.json").readAllBytes();
        assertNotNull(PriceSnapshot.load(bytes));
        var json = (tools.jackson.databind.node.ObjectNode) EvalJson.MAPPER.readTree(bytes);
        json.put("date", "2026-02-30");
        assertThrows(IllegalArgumentException.class, () -> PriceSnapshot.load(EvalJson.MAPPER.writeValueAsBytes(json)));
    }
    @Test void timingDeltasRequireSameScopeAndUnits() {
        var a = new EvaluationMetrics.Metric("KNOWN", 2.0, "ms", "CORE");
        assertEquals(1.0, EvaluationMetrics.delta(a, new EvaluationMetrics.Metric("KNOWN", 3.0, "ms", "CORE")));
        assertNull(EvaluationMetrics.delta(a, new EvaluationMetrics.Metric("KNOWN", 3.0, "ms", "WEB_ACTIVE")));
        assertThrows(IllegalArgumentException.class, () -> new EvaluationMetrics.Metric("UNKNOWN", 0.0, "ms", "CORE"));
    }
}
