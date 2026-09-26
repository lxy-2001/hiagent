package com.agentflow.demo.evaluation;

import com.agentflow.eval.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentEvaluationSuiteTest {
    @Test void runSelectedSuiteAndPublishBeforeCheckingGate() throws Exception {
        var options = EvaluationEntry.options(System.getProperties());
        var report = EvaluationEntry.run(options);
        var destination = new ReportWriter().write(Path.of("target/evaluation"), report);
        System.out.println("Evaluation report: " + destination.toAbsolutePath());
        EvaluationEntry.validateReport(report.document());
        assertTrue(report.passed(), () -> "Evaluation gate failed: " + destination.toAbsolutePath() + " " + report.document().path("summary"));
    }
    @Test void rejectUnknownVariantAndInvalidRepeatBeforeExecution() {
        var p = new java.util.Properties(); p.setProperty("agentflow.eval.variant", "invented");
        assertThrows(IllegalArgumentException.class, () -> EvaluationEntry.options(p));
        p.setProperty("agentflow.eval.variant", "baseline"); p.setProperty("agentflow.eval.repeat", "0");
        assertThrows(IllegalArgumentException.class, () -> EvaluationEntry.options(p));
    }
}
