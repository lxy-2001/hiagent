package com.agentflow.rag.cli;

import com.agentflow.rag.corpus.CorpusManifest;
import com.agentflow.rag.corpus.CorpusImporter;
import com.agentflow.rag.corpus.CorpusSnapshotStore;
import com.agentflow.rag.qdrant.QdrantVectorIndex;
import com.agentflow.llm.AgentFlowProperties;
import com.agentflow.llm.OpenAiEmbeddingClient;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.core.cancel.CancellationSignal;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.Banner;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

/** Explicit offline entry point; no web server or component scan. */
public final class RagImportApplication {
    private static final Set<String> COMMANDS = Set.of("validate", "import", "cleanup-prepared", "cleanup-retired");
    private static final Set<String> INPUT_ERRORS = Set.of("USAGE", "INVALID_PATH", "UNSUPPORTED_SOURCE", "INVALID_UTF8",
            "SECRET_CONTENT", "CORPUS_LIMIT", "EMPTY_CORPUS", "KEYWORD_INDEX_LIMIT", "INDEX_PROFILE_MISMATCH");
    private static final Set<String> STORAGE_ERRORS = Set.of("INDEX_LOCKED", "ARCHIVE_LIMIT", "STALE_PREPARATION",
            "RETIRED_LIMIT", "ATOMIC_ACTIVATION_UNSUPPORTED", "STORAGE_FAILURE");
    public record Report(int schemaVersion, String command, String outcome, String snapshotId, int fileCount,
                         int skippedEmptyCount, int chunkCount, boolean reused, String errorCode,
                         String warningCode, long elapsedMillis) { }
    public record Execution(int exitCode, Report report) { }
    public static Execution execute(String... arguments) {
        long started = System.nanoTime();
        String command = "";
        CorpusManifest manifest = null;
        // Explicit configuration only: no auto-configuration, repositories, server, or network beans.
        try (var context = new SpringApplicationBuilder(CliConfiguration.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).logStartupInfo(false).registerShutdownHook(false).run(arguments)) {
            var environment = context.getEnvironment();
            command = environment.getProperty("agentflow.rag.command", "");
            if (!COMMANDS.contains(command)) { return failure(command, "USAGE", started); }
            var properties = Binder.get(environment).bind("agentflow", AgentFlowProperties.class).orElseGet(AgentFlowProperties::new);
            var profile = new CorpusManifest.IndexProfile(environment.getProperty("agentflow.rag.embedding-space-id", ""),
                    properties.model().getEmbeddingModel(), properties.model().getEmbeddingDimensions(),
                    environment.getProperty("agentflow.rag.chunk-size", Integer.class, 800),
                    environment.getProperty("agentflow.rag.overlap", Integer.class, 100));
            String storeDirectory = environment.getProperty("agentflow.rag.store-directory", "");
            if (storeDirectory.isBlank()) { throw new IllegalArgumentException("INVALID_PATH"); }
            Path storePath = Path.of(storeDirectory).toAbsolutePath().normalize();
            if (command.equals("validate") || command.equals("import")) {
                String sourceDirectory = environment.getProperty("agentflow.rag.source-directory", "");
                if (sourceDirectory.isBlank()) { throw new IllegalArgumentException("INVALID_PATH"); }
                Path source = Path.of(sourceDirectory).toAbsolutePath().normalize();
                if (source.startsWith(storePath) || storePath.startsWith(source)) { throw new IllegalArgumentException("INVALID_PATH"); }
                manifest = CorpusManifest.read(source, profile);
                if (command.equals("validate")) {
                    return report(command, new CorpusImporter.Result("SUCCESS", manifest.snapshotId(), false, null, null), manifest, started);
                }
            }
            var control = new ToolExecutionControl(CancellationSignal.NONE, TimeSource.system(), Duration.ofMinutes(10));
            try (var store = new CorpusSnapshotStore(storePath)) {
                var vectors = new QdrantVectorIndex(URI.create(environment.getProperty("agentflow.rag.qdrant.base-url", "http://localhost:6333")),
                        environment.getProperty("agentflow.rag.qdrant.api-key", ""), store.storeId());
                var importer = new CorpusImporter(store, new OpenAiEmbeddingClient(properties), vectors);
                if (command.equals("import")) { return report(command, importer.importCorpus(manifest, control), manifest, started); }
                if (command.equals("cleanup-prepared")) { importer.cleanupPrepared(control); }
                else { importer.cleanupRetired(control); }
                return report(command, new CorpusImporter.Result("SUCCESS", null, false, null, null), null, started);
            }
        } catch (RuntimeException error) {
            String message = error.getMessage();
            String code = message != null && (INPUT_ERRORS.contains(message) || STORAGE_ERRORS.contains(message)) ? message : "STORAGE_FAILURE";
            return failure(command, code, started);
        }
    }

    private static Execution failure(String command, String code, long started) {
        return report(command, new CorpusImporter.Result("FAILED", null, false, code, null), null, started);
    }

    private static Execution report(String command, CorpusImporter.Result result, CorpusManifest manifest, long started) {
        int exit = "SUCCESS".equals(result.outcome()) ? 0 : result.errorCode() != null && INPUT_ERRORS.contains(result.errorCode()) ? 2 : 3;
        return new Execution(exit, new Report(1, COMMANDS.contains(command) ? command : "", result.outcome(), result.snapshotId(),
                manifest == null ? 0 : manifest.documents().size(), manifest == null ? 0 : manifest.skippedEmptyCount(),
                manifest == null ? 0 : manifest.chunkCount(), result.reused(), result.errorCode(), result.warningCode(),
                Duration.ofNanos(System.nanoTime() - started).toMillis()));
    }

    @Configuration(proxyBeanMethods = false)
    static class CliConfiguration { }
    public static void main(String[] args) {
        var execution = execute(args);
        System.out.println(new tools.jackson.databind.json.JsonMapper().writeValueAsString(execution.report()));
        System.exit(execution.exitCode());
    }
}
