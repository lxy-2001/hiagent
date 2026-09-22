package com.agentflow.llm;

import com.agentflow.core.model.DeadlineAwareEmbeddingClient;
import com.agentflow.core.model.EmbeddingCallOptions;

import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Map;
import java.net.URI;
import java.time.Duration;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.llm.http.BoundedEmbeddingTransport;
import tools.jackson.databind.json.JsonMapper;

/**
 * Embedding adapter backed by the shared OpenAI-compatible transport.
 */
public final class OpenAiEmbeddingClient implements DeadlineAwareEmbeddingClient {

    private final OpenAiCompatibleModelClient transport;
    private final AgentFlowProperties properties;
    private final BoundedEmbeddingTransport bounded = new BoundedEmbeddingTransport();
    private final JsonMapper json = new JsonMapper();

    public OpenAiEmbeddingClient(OpenAiCompatibleModelClient transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.properties = transport.properties();
    }

    public OpenAiEmbeddingClient(AgentFlowProperties properties) {
        this.transport = null;
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public List<Double> embed(String text, EmbeddingCallOptions options) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(options, "options");
        var model = properties.model();
        String key = present(model.getEmbeddingApiKey()) ? model.getEmbeddingApiKey() : model.getApiKey();
        int dimensions = model.getEmbeddingDimensions();
        if (!present(key) || !present(model.getEmbeddingModel()) || dimensions < 1 || dimensions > 4096) {
            throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR, "Embedding configuration invalid");
        }
        URI uri = endpoint();
        byte[] response = bounded.post(uri, key, json.writeValueAsBytes(Map.of("input", text,
                "model", model.getEmbeddingModel(), "dimensions", dimensions)), options);
        try {
            var data = json.readTree(response).path("data");
            if (!data.isArray() || data.size() != 1) { throw malformed(); }
            var values = data.path(0).path("embedding");
            if (!values.isArray() || values.size() != dimensions) { throw malformed(); }
            var vector = new ArrayList<Double>(dimensions);
            boolean nonzero = false;
            for (var value : values) {
                if (!value.isNumber() || !Double.isFinite(value.asDouble())) { throw malformed(); }
                double number = value.asDouble();
                nonzero |= number != 0;
                vector.add(number);
            }
            if (!nonzero) { throw malformed(); }
            return List.copyOf(vector);
        } catch (RuntimeException invalid) { throw malformed(); }
    }

    @Override
    public List<Double> embed(String text) {
        // Retain the explicit legacy transport constructor used by existing consumers.
        return transport != null ? transport.embed(text)
                : embed(text, new EmbeddingCallOptions(Duration.ofSeconds(5), CancellationSignal.NONE));
    }

    private URI endpoint() {
        var model = properties.model();
        String base = present(model.getEmbeddingBaseUrl()) ? model.getEmbeddingBaseUrl() : model.getBaseUrl();
        if (!present(base)) { base = "openai".equals(model.getProvider()) ? "https://api.openai.com/v1" : "https://api.deepseek.com"; }
        URI uri;
        try { uri = URI.create(base.replaceAll("/+$", "") + "/embeddings"); }
        catch (IllegalArgumentException invalid) { throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR, "Embedding endpoint invalid"); }
        boolean local = uri.getHost() != null && List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))) {
            throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR, "Embedding endpoint invalid");
        }
        return uri;
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static ModelClientException malformed() {
        return new ModelClientException(ModelClientException.MALFORMED_MODEL_RESPONSE, "Embedding response invalid");
    }
}
