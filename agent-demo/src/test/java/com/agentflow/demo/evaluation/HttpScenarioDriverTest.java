package com.agentflow.demo.evaluation;

import org.junit.jupiter.api.Test;
import com.agentflow.eval.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpScenarioDriverTest {
    @Test void approvalCasesUseAuthenticatedHttpAndActualSideEffects() throws Exception {
        var data = EvalDataset.load(getClass().getResourceAsStream("/evaluation/dataset-v1.json").readAllBytes());
        for (String id : java.util.List.of("C15", "C16", "C18", "C23", "C22", "C08")) {
            var c = data.cases().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
            try (var driver = new HttpScenarioDriver()) {
                var observed = driver.execute(c, EvalVariant.baseline(), 1);
                var score = new EvaluationScorer().score(c, observed);
                assertEquals(CaseReport.Status.PASS, score.status(), id + " " + score);
            }
        }
    }
}
