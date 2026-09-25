package com.agentflow.eval;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.UsageSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Measurement arithmetic independent of the scenario driver and scoring expectations. */
public final class EvaluationMetrics {
    private EvaluationMetrics() { }
    public record Attempt(TokenUsage usage, UsageSource source, double elapsedMillis) {
        public Attempt {
            Objects.requireNonNull(source);
            if (!Double.isFinite(elapsedMillis) || elapsedMillis < 0 || usage == null && source != UsageSource.UNKNOWN) {
                throw new IllegalArgumentException("Invalid model attempt");
            }
        }
    }
    public record Usage(String status, Long promptTokens, Long completionTokens, int unknownCalls) {
        public Usage {
            if (!Set.of("REPORTED", "FIXTURE", "UNKNOWN", "NOT_APPLICABLE").contains(status) || unknownCalls < 0) {
                throw new IllegalArgumentException("Invalid usage status");
            }
            boolean known = status.equals("REPORTED") || status.equals("FIXTURE");
            if (known && (promptTokens == null || completionTokens == null || promptTokens < 0 || completionTokens < 0 || unknownCalls != 0)
                    || !known && (promptTokens != null || completionTokens != null)
                    || status.equals("NOT_APPLICABLE") && unknownCalls != 0) throw new IllegalArgumentException("Inconsistent usage counters");
        }
    }
    public record Cost(String status, String amount, String currency, String priceId, String reason) { }
    public record Metric(String status, Double value, String unit, String scope) {
        public Metric {
            if (!Set.of("KNOWN", "UNKNOWN", "NOT_APPLICABLE").contains(status)
                    || "KNOWN".equals(status) != (value != null)
                    || value != null && (!Double.isFinite(value) || value < 0)
                    || unit == null || scope == null) throw new IllegalArgumentException("Invalid metric");
        }
    }
    public record Distribution(int n, Double p50, Double p95, String unit, String scope, boolean smallSample) { }

    public static Usage usage(List<Attempt> calls) {
        if (calls.isEmpty()) return new Usage("NOT_APPLICABLE", null, null, 0);
        int unknown = 0;
        long prompt = 0, completion = 0;
        UsageSource source = calls.get(0).source();
        boolean mixed = false;
        for (var call : calls) {
            mixed |= source != call.source();
            if (call.usage() == null || call.source() == UsageSource.UNKNOWN) unknown++;
            else {
                prompt = Math.addExact(prompt, call.usage().promptTokens());
                completion = Math.addExact(completion, call.usage().completionTokens());
            }
        }
        if (unknown > 0 || mixed) return new Usage("UNKNOWN", null, null, unknown);
        return new Usage(source.name(), prompt, completion, 0);
    }

    public static Cost cost(Usage usage, String provider, String model, PriceSnapshot price) {
        if (usage.status().equals("NOT_APPLICABLE")) return new Cost("NOT_APPLICABLE", null, null, null, "NO_MODEL_REQUEST");
        if (!usage.status().equals("REPORTED")) return new Cost("UNKNOWN", null, null, null,
                usage.status().equals("FIXTURE") ? "FIXTURE_NOT_BILLABLE" : "USAGE_UNKNOWN");
        if (price == null || !price.provider().equals(provider) || !price.model().equals(model)) {
            return new Cost("UNKNOWN", null, null, null, "PRICE_UNAVAILABLE");
        }
        BigDecimal amount = BigDecimal.valueOf(usage.promptTokens()).multiply(price.inputPerMillion())
                .add(BigDecimal.valueOf(usage.completionTokens()).multiply(price.outputPerMillion()))
                .divide(BigDecimal.valueOf(1000000), 8, RoundingMode.HALF_UP);
        return new Cost("ESTIMATED", amount.toPlainString(), price.currency(), price.id(), "TOKEN_PRICE_ESTIMATE");
    }

    public static Distribution distribution(List<Double> values, String unit, String scope) {
        if (values.stream().anyMatch(v -> v == null || !Double.isFinite(v) || v < 0)) {
            throw new IllegalArgumentException("Invalid measurement");
        }
        var sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return new Distribution(n, n == 0 ? null : sorted.get((int) Math.ceil(n * 0.5) - 1),
                n == 0 ? null : sorted.get((int) Math.ceil(n * 0.95) - 1), unit, scope, n < 20);
    }

    public static Double delta(Metric baseline, Metric candidate) {
        if (!baseline.status().equals("KNOWN") || !candidate.status().equals("KNOWN")
                || !baseline.unit().equals(candidate.unit()) || !baseline.scope().equals(candidate.scope())) return null;
        return candidate.value() - baseline.value();
    }
}
