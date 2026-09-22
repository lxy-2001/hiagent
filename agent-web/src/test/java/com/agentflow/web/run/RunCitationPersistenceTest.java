package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.RunStatus;
import com.agentflow.core.runtime.TerminationReason;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = RunCreateTransactionTest.TestApplication.class)
class RunCitationPersistenceTest {
    @Autowired RunPersistence persistence;
    @Autowired JdbcTemplate jdbc;

    @Test void policySurvivesInterruptedRunRecoveryAndOldConstructorsDefaultFalse() {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var queued = persistence.createQueued(new RunPersistence.CreateCommand("policy-run", "policy-session", "owner", "q", "q", now, true, true));
        assertThat(queued.requireEvidence()).isTrue();
        persistence.convergeInterrupted("", 100, now.plusSeconds(1));
        var recovered = persistence.getOwned("owner", "policy-run").orElseThrow();
        assertThat(recovered.requireEvidence()).isTrue();
        assertThat(recovered.citations()).isEmpty();
        assertThat(recovered.terminationReason()).isEqualTo(RunTerminationReason.PROCESS_INTERRUPTED);
        assertThat(new RunPersistence.CreateCommand("old", "s", "u", "q", "q", now).requireEvidence()).isFalse();
    }

    @Test void sizePreflightDoesNotFetchEntityOrJsonForOversizedOwnedRow() {
        var tasks = org.mockito.Mockito.mock(com.agentflow.web.agent.AgentTaskRepository.class);
        org.mockito.Mockito.when(tasks.ownedCitationBytes("large", "owner")).thenReturn(65537L);
        var boundary = new RunPersistence(org.mockito.Mockito.mock(com.agentflow.web.agent.AgentSessionRepository.class),
                tasks, org.mockito.Mockito.mock(com.agentflow.web.agent.AgentStepRepository.class),
                org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class));
        assertThatThrownBy(() -> boundary.getOwned("owner", "large")).hasMessage("CITATION_DATA_UNAVAILABLE");
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test void storesAnswerAndEvidenceAndComparesEvidenceOnTerminalRetry() throws Exception {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        persistence.createQueued(new RunPersistence.CreateCommand("citation-run", "citation-session", "owner", "q", "q", now));
        var projector = new RunResultProjector();
        var citation = CitationProjectionTest.citation("original text");
        var result = new AgentResult("citation-run", "answer [S1]", List.of(), RunStatus.SUCCEEDED,
                TerminationReason.COMPLETED, TokenUsage.empty(), "", List.of(citation));
        var projection = projector.project("citation-run", result, now, false, false);
        assertThat(persistence.complete(projection).written()).isTrue();
        var snapshot = persistence.getOwned("owner", "citation-run").orElseThrow();
        assertThat(snapshot.finalAnswer()).isEqualTo("answer [S1]");
        assertThat(new ObjectMapper().valueToTree(snapshot).path("citations").size()).isOne();
        assertThat(persistence.complete(projection).conflict()).isFalse();
        var changed = new AgentResult("citation-run", "answer [S1]", List.of(), RunStatus.SUCCEEDED,
                TerminationReason.COMPLETED, TokenUsage.empty(), "", List.of(CitationProjectionTest.citation("other text")));
        assertThat(persistence.complete(projector.project("citation-run", changed, now, false, false)).conflict()).isTrue();
        assertThat(persistence.getOwned("stranger", "citation-run")).isEmpty();
    }

    @Test void corruptAndOversizedStoredJsonCannotBecomeAnEmptySuccessfulCitationList() {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        persistence.createQueued(new RunPersistence.CreateCommand("corrupt-run", "corrupt-session", "owner", "q", "q", now));
        for (String value : List.of("{broken", "x".repeat(65537))) {
            jdbc.update("update agent_task set citations_json=? where id='corrupt-run'", value);
            assertThatThrownBy(() -> persistence.getOwned("owner", "corrupt-run"))
                    .hasMessage("CITATION_DATA_UNAVAILABLE");
            assertThat(persistence.getOwned("stranger", "corrupt-run")).isEmpty();
        }
    }
}
