package com.agentflow.web.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

/** Local data root; no connection to or migration of an existing remote database. */
@ConfigurationProperties("agentflow.harness.storage")
public class HarnessStorageProperties {
    private Path dataRoot = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")), "HiAgent");
    public Path getDataRoot() { return dataRoot; }
    public void setDataRoot(Path dataRoot) {
        if (dataRoot == null || dataRoot.toString().isBlank()) throw new IllegalArgumentException("dataRoot is required");
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
    }
}
