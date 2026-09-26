package com.agentflow.demo.evaluation;

import com.agentflow.eval.ReportComparator;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentEvaluationCompareTest {
    @Test void compareExistingReportsWithoutRunningDrivers() throws Exception {
        String baseline = System.getProperty("agentflow.eval.baseline"), candidate = System.getProperty("agentflow.eval.candidate");
        if (baseline == null && candidate == null) {
            var invalid = EvaluationEntry.JSON.createObjectNode();
            assertEquals("INCOMPARABLE", new ReportComparator().compare(invalid, invalid, "contextWindow").path("status").asString());
            return;
        }
        if (baseline == null || candidate == null) throw new IllegalArgumentException("BOTH_REPORTS_REQUIRED");
        var result = new ReportComparator().compare(EvaluationEntry.readReport(Path.of(baseline)), EvaluationEntry.readReport(Path.of(candidate)),
                System.getProperty("agentflow.eval.dimension", "contextWindow"));
        var output = new com.agentflow.eval.ReportWriter().writeComparison(Path.of("target/evaluation"), result);
        System.out.println("Comparison report: " + output.toAbsolutePath());
        assertEquals("PASS", result.path("status").asString(), "Comparison gate: " + result.path("status").asString());
    }
}
