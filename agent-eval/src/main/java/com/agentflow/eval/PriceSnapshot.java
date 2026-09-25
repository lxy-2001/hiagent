package com.agentflow.eval;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Explicit local price snapshot; never inferred from a model name or a live price feed. */
public record PriceSnapshot(String id, String provider, String model, String currency, LocalDate date,
                            String source, BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    public static PriceSnapshot load(byte[] bytes) {
        if (bytes == null || bytes.length > 16384) throw new IllegalArgumentException("INVALID_PRICE_SNAPSHOT");
        try {
            var node = EvalJson.MAPPER.readTree(bytes);
            EvalDataset.fields(node, "id", "provider", "model", "currency", "date", "source", "inputPerMillion", "outputPerMillion");
            for (var field : node.properties()) if (!field.getValue().isTextual()) throw new IllegalArgumentException();
            for (String key : java.util.List.of("inputPerMillion", "outputPerMillion")) {
                if (!node.path(key).asText().matches("[0-9]{1,32}(\\.[0-9]{1,8})?")) throw new IllegalArgumentException();
            }
            return new PriceSnapshot(node.path("id").asText(), node.path("provider").asText(), node.path("model").asText(),
                    node.path("currency").asText(), LocalDate.parse(node.path("date").asText()), node.path("source").asText(),
                    new BigDecimal(node.path("inputPerMillion").asText()), new BigDecimal(node.path("outputPerMillion").asText()));
        } catch (RuntimeException e) { throw new IllegalArgumentException("INVALID_PRICE_SNAPSHOT"); }
    }
    public PriceSnapshot {
        for (String value : new String[]{id, provider, model}) {
            if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException("Invalid price identity");
        }
        if (source == null || source.isBlank() || source.length() > 256) throw new IllegalArgumentException("Invalid price source");
        if (currency == null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency");
        Objects.requireNonNull(date); Objects.requireNonNull(inputPerMillion); Objects.requireNonNull(outputPerMillion);
        if (inputPerMillion.signum() < 0 || outputPerMillion.signum() < 0
                || inputPerMillion.scale() > 8 || outputPerMillion.scale() > 8) throw new IllegalArgumentException("Invalid price rate");
    }
}
