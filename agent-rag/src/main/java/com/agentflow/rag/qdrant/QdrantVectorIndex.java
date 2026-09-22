package com.agentflow.rag.qdrant;

import com.agentflow.core.runtime.ToolExecutionControl;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.agentflow.rag.corpus.CorpusManifest;

public final class QdrantVectorIndex implements VectorIndex {
    private final URI endpoint;
    private final String apiKey;
    private final String storeId;
    private final BoundedQdrantTransport transport = new BoundedQdrantTransport();
    private final JsonMapper json = new JsonMapper();

    public QdrantVectorIndex(URI endpoint, String apiKey, String storeId) {
        Objects.requireNonNull(endpoint, "endpoint");
        String host = endpoint.getHost();
        boolean local = host != null && List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host);
        if (host == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !("https".equals(endpoint.getScheme()) || local && "http".equals(endpoint.getScheme()))
                || storeId == null || !storeId.matches("[a-f0-9]{32}")) { throw invalidConfiguration(); }
        this.endpoint = URI.create(endpoint.toString().replaceAll("/+$", "") + "/");
        this.apiKey = apiKey;
        this.storeId = storeId;
    }

    @Override public void ensureCollection(String collection, int dimensions, ToolExecutionControl control) {
        if (dimensions < 1 || dimensions > 4096) { throw invalidConfiguration(); }
        var response = request(collection, "", "GET", null, 1_048_576, control);
        if (response.statusCode() == 404) {
            result(request(collection, "", "PUT", Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")), 1_048_576, control));
            return;
        }
        var vectors = result(response).path("config").path("params").path("vectors");
        if (!vectors.path("size").isIntegralNumber() || vectors.path("size").asInt() != dimensions
                || !"Cosine".equals(vectors.path("distance").asString())) { throw invalidConfiguration(); }
    }

    @Override public boolean collectionExists(String collection, ToolExecutionControl control) {
        var response = request(collection, "", "GET", null, 1_048_576, control);
        if (response.statusCode() == 404) { return false; }
        result(response);
        return true;
    }

    @Override public void upsert(String collection, List<VectorPoint> points, ToolExecutionControl control) {
        if (points.isEmpty() || points.size() > 64) { throw invalidConfiguration(); }
        var values = new ArrayList<Map<String, Object>>();
        for (var point : points) {
            validateVector(point.vector());
            validateIdentity(new StoredPoint(point.pointId(), point.snapshotId(), point.chunkId(), point.contentHash()));
            if (!collection.endsWith("_" + point.snapshotId())) { throw invalidSource(); }
            values.add(Map.of("id", point.pointId().toString(), "vector", point.vector(), "payload",
                    Map.of("snapshotId", point.snapshotId(), "chunkId", point.chunkId(), "contentHash", point.contentHash())));
        }
        var response = result(request(collection, "/points?wait=true", "PUT", Map.of("points", values), 1_048_576, control));
        if (!"completed".equals(response.path("status").asString())) { throw invalidSource(); }
    }

    @Override public long countExact(String collection, ToolExecutionControl control) {
        var count = result(request(collection, "/points/count", "POST", Map.of("exact", true), 1_048_576, control)).path("count");
        if (!count.isIntegralNumber() || !count.canConvertToLong() || count.asLong() < 0) { throw invalidSource(); }
        return count.asLong();
    }

    @Override public List<StoredPoint> readPoints(String collection, List<UUID> ids, ToolExecutionControl control) {
        if (ids.isEmpty() || ids.size() > 64 || new HashSet<>(ids).size() != ids.size()) { throw invalidConfiguration(); }
        var result = result(request(collection, "/points", "POST", Map.of("ids", ids, "with_payload", true,
                "with_vector", false), 1_048_576, control));
        if (!result.isArray() || result.size() != ids.size()) { throw invalidSource(); }
        var remaining = new HashSet<>(ids);
        var points = new ArrayList<StoredPoint>();
        for (var value : result) {
            var point = point(value);
            if (!remaining.remove(point.pointId()) || !collection.endsWith("_" + point.snapshotId())) { throw invalidSource(); }
            points.add(point);
        }
        return List.copyOf(points);
    }
    @Override public List<VectorHit> search(String collection, String snapshotId, List<Double> vector, int limit,
                                           double threshold, ToolExecutionControl control) {
        validateVector(vector);
        if (limit < 1 || limit > 40 || !Double.isFinite(threshold) || threshold < -1 || threshold > 1
                || !collection.endsWith("_" + snapshotId)) { throw invalidConfiguration(); }
        var filter = Map.of("must", List.of(Map.of("key", "snapshotId", "match", Map.of("value", snapshotId))));
        var response = result(request(collection, "/points/search", "POST", Map.of("vector", vector, "limit", limit,
                "score_threshold", threshold, "with_payload", true, "with_vector", false, "filter", filter), 131_072, control));
        if (!response.isArray() || response.size() > limit) { throw invalidSource(); }
        var hits = new ArrayList<VectorHit>();
        for (var value : response) {
            var point = point(value);
            var score = value.path("score");
            if (!snapshotId.equals(point.snapshotId()) || !score.isNumber() || !Double.isFinite(score.asDouble())) { throw invalidSource(); }
            if (score.asDouble() >= threshold) {
                hits.add(new VectorHit(point.pointId(), point.snapshotId(), point.chunkId(), point.contentHash(), score.asDouble()));
            }
        }
        return List.copyOf(hits);
    }

    @Override public void deleteOwnedCollection(String collection, ToolExecutionControl control) {
        var response = request(collection, "", "DELETE", null, 1_048_576, control);
        if (response.statusCode() != 404) {
            if (!result(response).isBoolean() || !result(response).asBoolean()) { throw invalidSource(); }
        }
    }

    private java.net.http.HttpResponse<byte[]> request(String collection, String suffix, String method, Object body,
                                                      int limit, ToolExecutionControl control) {
        if (collection == null || !collection.matches("hiagent_" + storeId + "_[a-f0-9]{64}")) { throw invalidConfiguration(); }
        byte[] bytes = body == null ? null : json.writeValueAsBytes(body);
        if (bytes != null && bytes.length > 1_048_576) { throw invalidConfiguration(); }
        return transport.request(endpoint.resolve("collections/" + collection + suffix), apiKey, method, bytes, limit, control);
    }

    private JsonNode result(java.net.http.HttpResponse<byte[]> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) { throw new IllegalStateException("RAG_VECTOR_UNAVAILABLE"); }
        try {
            var root = json.readTree(response.body());
            if (!"ok".equals(root.path("status").asString()) || root.path("result").isMissingNode()) { throw invalidSource(); }
            return root.path("result");
        } catch (RuntimeException invalid) { throw invalidSource(); }
    }

    private StoredPoint point(JsonNode node) {
        try {
            var payload = node.path("payload");
            var point = new StoredPoint(UUID.fromString(node.path("id").asString()), payload.path("snapshotId").asString(),
                    payload.path("chunkId").asString(), payload.path("contentHash").asString());
            validateIdentity(point);
            return point;
        } catch (RuntimeException invalid) { throw invalidSource(); }
    }

    private static void validateIdentity(StoredPoint point) {
        for (String hash : List.of(point.snapshotId(), point.chunkId(), point.contentHash())) {
            if (!hash.matches("[a-f0-9]{64}")) { throw invalidSource(); }
        }
        if (!CorpusManifest.pointId(point.chunkId()).equals(point.pointId())) { throw invalidSource(); }
    }
    private static void validateVector(List<Double> vector) {
        if (vector.isEmpty() || vector.size() > 4096 || vector.stream().anyMatch(v -> v == null || !Double.isFinite(v))
                || vector.stream().allMatch(v -> v == 0)) { throw invalidConfiguration(); }
    }
    private static IllegalStateException invalidConfiguration() { return new IllegalStateException("RAG_CONFIGURATION_INVALID"); }
    private static IllegalStateException invalidSource() { return new IllegalStateException("RAG_SOURCE_INVALID"); }
}
