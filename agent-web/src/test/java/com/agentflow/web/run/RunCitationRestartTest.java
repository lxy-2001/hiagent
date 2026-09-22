package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.RunStatus;
import com.agentflow.core.runtime.TerminationReason;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RunCitationRestartTest {
    @Test void newApplicationContextReadsSameCitationsWithoutAnyRetriever() throws Exception {
        var now=Instant.parse("2026-09-22T00:00:00Z");
        var citations=List.of(CitationProjectionTest.citation("persisted evidence"));
        try(var application=start("create")) {
            var persistence=application.getBean(RunPersistence.class);
            persistence.createQueued(new RunPersistence.CreateCommand("restart-run","restart-session","owner","q","q",now,true,true));
            var result=new AgentResult("restart-run","answer [S1]",List.of(),RunStatus.SUCCEEDED,
                    TerminationReason.COMPLETED,TokenUsage.empty(),"",citations);
            persistence.complete(new RunResultProjector().project("restart-run",result,now,false,false));
        }
        try(var application=start("validate")) {
            assertThat(application.getBeansOfType(com.agentflow.core.rag.RagRetriever.class)).isEmpty();
            var result=application.getBean(RunPersistence.class).getOwned("owner","restart-run").orElseThrow();
            assertThat(result.citations()).isEqualTo(citations);
            assertThat(result.finalAnswer()).isEqualTo("answer [S1]");
            assertThat(result.requireEvidence()).isTrue();
        }
    }

    private ConfigurableApplicationContext start(String ddl) {
        return new SpringApplicationBuilder(RunCreateTransactionTest.TestApplication.class)
                .web(WebApplicationType.NONE).registerShutdownHook(false).run(
                        "--spring.datasource.url=jdbc:h2:mem:citation-restart;MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "--spring.datasource.username=sa","--spring.datasource.password=",
                        "--spring.jpa.hibernate.ddl-auto="+ddl);
    }
}
