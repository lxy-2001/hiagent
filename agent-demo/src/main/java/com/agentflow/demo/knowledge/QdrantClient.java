package com.agentflow.demo.knowledge;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class QdrantClient {

    private static final Logger log = LoggerFactory.getLogger(QdrantClient.class);

    private final RestClient restClient;
    private final String collection;

    public QdrantClient(RestClient.Builder builder,
                        @Value("${app.qdrant.base-url}") String baseUrl,
                        @Value("${app.qdrant.collection}") String collection) {
        this.restClient = builder.baseUrl(trimTrailingSlash(baseUrl)).build();
        this.collection = collection;
    }

    public void ensureCollection(int dimensions) {
        try {
            restClient.get()
                    .uri("/collections/{collection}", collection)
                    .retrieve()
                    .toBodilessEntity();
            return;
        } catch (HttpClientErrorException.NotFound ignored) {
            // Create it below.
        }
        Map<String, Object> body = Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine"));
        try {
            restClient.put()
                    .uri("/collections/{collection}", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict ignored) {
            // Another startup path may have created it after the existence check.
        }
    }

    public void upsert(String vectorId, List<Double> vector, Map<String, Object> payload) {
        Map<String, Object> point = Map.of("id", vectorId, "vector", vector, "payload", payload);
        Map<String, Object> body = Map.of("points", List.of(point));
        restClient.put()
                .uri("/collections/{collection}/points?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    public List<SearchHit> search(List<Double> vector, int limit) {
        Map<String, Object> body = Map.of("vector", vector, "limit", limit, "with_payload", true);
        JsonNode response = restClient.post()
                .uri("/collections/{collection}/points/search", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        List<SearchHit> hits = new ArrayList<>();
        if (response == null || !response.path("result").isArray()) {
            return hits;
        }
        response.path("result").forEach(node -> hits.add(new SearchHit(node.path("id").asText(), node.path("score").asDouble())));
        return hits;
    }

    public boolean isAvailable() {
        try {
            restClient.get().uri("/readyz").retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            log.debug("Qdrant is not available: {}", ex.getMessage());
            return false;
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record SearchHit(String vectorId, double score) {
    }
}
