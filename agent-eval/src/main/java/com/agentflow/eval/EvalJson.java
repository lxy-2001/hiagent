package com.agentflow.eval;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

final class EvalJson {
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private EvalJson() { }
    static String hash(JsonNode node) { return hash(MAPPER.writeValueAsBytes(canonical(node))); }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            node.properties().forEach(e -> sorted.put(e.getKey(), canonical(e.getValue())));
            var result = MAPPER.createObjectNode(); sorted.forEach(result::set); return result;
        }
        if (node.isArray()) {
            var result = MAPPER.createArrayNode(); node.forEach(n -> result.add(canonical(n))); return result;
        }
        return node;
    }
}
