package com.agentflow.web.approval;

import com.agentflow.core.approval.*;
import com.agentflow.core.runtime.*;
import com.agentflow.web.agent.*;
import com.agentflow.web.run.*;
import com.agentflow.web.autoconfigure.AgentWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = ApprovalRunIntegrationTest.App.class)
class ApprovalRunIntegrationTest {
    @Autowired RunPersistence runs;
    @Autowired ApprovalPersistence persistence;
    @Autowired AgentTaskRepository tasks;
    @Autowired ToolInvocationRepository invocations;
    @Autowired AgentSessionRepository sessions;
    @org.junit.jupiter.api.BeforeEach void clean() { invocations.deleteAll(); tasks.deleteAll(); sessions.deleteAll(); }

    @Test void twentyConcurrentApprovalsAreIdempotentAndOnlyOneDispatchIsPossible() throws Exception {
        var request = ApprovalFixtures.request();
        runs.createQueued(new RunPersistence.CreateCommand(ApprovalFixtures.RUN, "s", "owner", "hello", "hello", ApprovalFixtures.NOW));
        runs.markRunning(ApprovalFixtures.RUN, ApprovalFixtures.NOW);
        var service = new ApprovalService(persistence, Clock.fixed(ApprovalFixtures.NOW, ZoneOffset.UTC), System::nanoTime);
        var control = ApprovalFixtures.control();
        var budget = new ToolExecutionControl(control, TimeSource.system(), Duration.ofSeconds(30));
        var events = new CopyOnWriteArrayList<RunEvent.Draft>();
        service.begin(request, control, budget, event -> {
            assertThat(tasks.findById(ApprovalFixtures.RUN).orElseThrow().getStatus()).isEqualTo("WAITING_APPROVAL");
            events.add(event);
        });
        assertThat(runs.getOwned("owner", ApprovalFixtures.RUN).orElseThrow().status()).isEqualTo(RunLifecycleStatus.WAITING_APPROVAL);
        assertThatThrownBy(() -> service.get("other", ApprovalFixtures.RUN, request.approvalId().toString()))
                .isInstanceOf(RunCoordinator.RunNotFoundException.class);
        var pool = Executors.newFixedThreadPool(20);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<ApprovalSnapshot>>();
            for (int i=0;i<20;i++) futures.add(pool.submit(() -> { start.await(); return service.decide("owner",
                    ApprovalFixtures.RUN, request.approvalId().toString(), ApprovalService.Decision.APPROVE); }));
            start.countDown();
            for (var future : futures) assertThat(future.get(5, TimeUnit.SECONDS).status()).isEqualTo(ApprovalStatus.APPROVED);
        } finally { pool.shutdownNow(); }
        assertThat(events).hasSize(2);
        assertThat(service.dispatch(request, budget)).isTrue();
        assertThatThrownBy(() -> service.dispatch(request, budget)).isInstanceOf(ApprovalService.ConflictException.class);
        assertThat(tasks.findById(ApprovalFixtures.RUN).orElseThrow().getStartedAt()).isEqualTo(ApprovalFixtures.NOW);
        assertThat(persistence.getOwned("owner", ApprovalFixtures.RUN, request.approvalId().toString()).dispatchCount()).isEqualTo(1);
        var call = request.preparedCall(); var policy = request.policyDecision();
        var record = new com.agentflow.core.tool.ToolInvocationRecord("006-v1", call.runId(), call.callId(), call.call().name(),
                null, null, call.toolDefinitionVersion(), policy.policyVersion(), call.argumentsDigest(), policy.risk(), policy.effect(),
                policy.action(), call.createdAt(), null, request.approvalId(), ApprovalStatus.PENDING, 0L, 0, null,
                com.agentflow.core.tool.ToolInvocationRecord.Outcome.NOT_DISPATCHED, null);
        runs.complete(new RunResultProjector.FinalProjection(ApprovalFixtures.RUN, RunLifecycleStatus.CANCELLED,
                RunTerminationReason.CANCELLED, TerminationReason.CANCELLED, null, null, ApprovalFixtures.NOW.plusSeconds(1),
                true, false, "CANCELLED", List.of(), List.of(), List.of(record)));
        var finished = persistence.getOwned("owner", ApprovalFixtures.RUN, request.approvalId().toString());
        assertThat(finished.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(finished.dispatchCount()).isZero();
        assertThat(finished.actionSummary()).isEqualTo("Append note");
        assertThat(service.decide("owner", ApprovalFixtures.RUN, request.approvalId().toString(), ApprovalService.Decision.APPROVE).status())
                .isEqualTo(ApprovalStatus.APPROVED);
        service.release(request.approvalId().toString());
    }

    @Test void ordinaryRecordUsesHistoricalFactsAndOwningRun() {
        runs.createQueued(new RunPersistence.CreateCommand(ApprovalFixtures.RUN, "s", "owner", "hello", "hello", ApprovalFixtures.NOW));
        runs.markRunning(ApprovalFixtures.RUN, ApprovalFixtures.NOW);
        var fact = ApprovalPersistenceTest.record(ApprovalFixtures.RUN);
        runs.complete(new RunResultProjector.FinalProjection(ApprovalFixtures.RUN, RunLifecycleStatus.SUCCEEDED,
                RunTerminationReason.COMPLETED, TerminationReason.COMPLETED, "done", null, ApprovalFixtures.NOW.plusSeconds(1),
                false, false, null, List.of(), List.of(), List.of(fact)));
        var row = invocations.findByTaskIdAndCallId(ApprovalFixtures.RUN, "call-1").orElseThrow();
        assertThat(row.getOwnerId()).isEqualTo("owner");
        assertThat(row.getArgumentsDigest()).isEqualTo(fact.argumentsDigest());
        assertThat(row.getCreatedAt()).isEqualTo(fact.createdAt());
        assertThat(row.getDispatchAt()).isEqualTo(fact.dispatchAt());
        assertThat(persistence.listOwned("owner", ApprovalFixtures.RUN)).isEmpty();
    }

    @Test void approvalCreationRollsBackWhenRunCannotWait() {
        runs.createQueued(new RunPersistence.CreateCommand(ApprovalFixtures.RUN, "s", "owner", "hello", "hello", ApprovalFixtures.NOW));
        assertThatThrownBy(() -> persistence.create(ApprovalFixtures.request())).isInstanceOf(IllegalStateException.class);
        assertThat(invocations.count()).isZero();
        assertThat(tasks.findById(ApprovalFixtures.RUN).orElseThrow().getStatus()).isEqualTo("QUEUED");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = AgentWebAutoConfiguration.class)
    @EntityScan(basePackageClasses = {AgentTaskEntity.class, ToolInvocationEntity.class})
    @EnableJpaRepositories(basePackageClasses = {AgentTaskRepository.class, ToolInvocationRepository.class})
    @Import({RunPersistence.class, ApprovalPersistence.class})
    static class App { }
}
