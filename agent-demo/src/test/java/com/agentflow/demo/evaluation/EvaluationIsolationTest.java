package com.agentflow.demo.evaluation;

import com.agentflow.eval.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationIsolationTest {
    @Test void offlineNetworkGuardRejectsRemoteRedirectTargetsAndUserInfo() {
        for (String value : List.of("https://example.com", "http://127.0.0.1.evil.invalid", "http://user@127.0.0.1", "file:///tmp/x"))
            assertThrows(IllegalArgumentException.class, () -> OfflineConnections.requireLoopback(URI.create(value)));
        assertDoesNotThrow(() -> OfflineConnections.requireLoopback(URI.create("http://127.0.0.1:1234/mcp")));
    }
    @Test void repeatedMcpAndCancellationScopesHaveFreshWritesAndClosePorts() throws Exception {
        var data = EvaluationEntry.dataset();
        for (String id : List.of("C14", "C19", "C20")) for (int repeat = 1; repeat <= 2; repeat++) {
            var c = data.cases().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
            URI endpoint;
            try (var driver = new CoreScenarioDriver()) {
                var observed = driver.execute(c, EvalVariant.baseline(), repeat);
                assertEquals(CaseReport.Status.PASS, new EvaluationScorer().score(c, observed).status());
                assertThrows(IllegalStateException.class, () -> driver.execute(c, EvalVariant.baseline(), 1));
                endpoint = driver.fixtureEndpoint();
            }
            if (endpoint != null) assertThrows(java.io.IOException.class, () -> {
                try (var socket = new java.net.Socket()) { socket.connect(new java.net.InetSocketAddress(endpoint.getHost(), endpoint.getPort()), 500); }
            });
        }
    }
    @Test void malformedAndOversizedReportsAreRejectedWithoutRunningCases(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var path = directory.resolve("report.json"); java.nio.file.Files.writeString(path, "{\"schemaVersion\":1,\"apiKey\":\"sentinel\"}");
        assertThrows(IllegalArgumentException.class, () -> EvaluationEntry.readReport(path));
        java.nio.file.Files.write(path, new byte[10 * 1024 * 1024 + 1]);
        assertThrows(IllegalArgumentException.class, () -> EvaluationEntry.readReport(path));
    }
}
