package com.agentflow.demo.delivery;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class DeliveryManifestTest {
    @Test void publicDeliveryEvidenceMatchesItsClosedSchemaAndActualSourcePaths() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("samples/contracts/delivery-manifest.json"))) root = root.getParent();
        assertThat(root).isNotNull();
        var json = new ObjectMapper();
        var manifest = json.readTree(Files.readString(root.resolve("samples/contracts/delivery-manifest.json")));
        try (var input = Files.newInputStream(root.resolve("samples/contracts/delivery.schema.json"))) {
            var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(input);
            assertThat(schema.validate(manifest)).isEmpty();
        }
        for (var evidence : manifest.path("evidence")) {
            assertThat(Files.isRegularFile(root.resolve(evidence.path("codeRef").asString()))).isTrue();
            assertThat(Files.isRegularFile(root.resolve(evidence.path("testRef").asString()))).isTrue();
        }
        for (var check : manifest.path("checks")) {
            if (check.path("status").asString().equals("NOT_RUN")) assertThat(check.path("exitCode").isNull()).isTrue();
        }
        assertThat(manifest.path("externalPublication").asString()).isEqualTo("NOT_PUBLISHED");
    }
}
