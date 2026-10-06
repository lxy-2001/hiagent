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
        Path resolved = dataRoot.toAbsolutePath().normalize();
        try {
            if (java.nio.file.Files.exists(resolved)) resolved = resolved.toRealPath();
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("dataRoot cannot be resolved", failure);
        }
        Path workspace = Path.of("").toAbsolutePath().normalize();
        if (resolved.getParent() == null || resolved.startsWith(workspace)
                || java.nio.file.Files.exists(resolved.resolve(".git"))) {
            throw new IllegalArgumentException("dataRoot must be a dedicated directory outside the checkout");
        }
        this.dataRoot = resolved;
    }
}
