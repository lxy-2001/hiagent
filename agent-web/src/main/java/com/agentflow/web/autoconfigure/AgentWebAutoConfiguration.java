package com.agentflow.web.autoconfigure;

import com.agentflow.core.context.ContextSource;
import com.agentflow.web.approval.*;
import com.agentflow.web.memory.ConfirmedMemoryEntity;
import com.agentflow.web.memory.ConfirmedMemoryRepository;
import com.agentflow.web.memory.ConfirmedMemoryService;
import com.agentflow.web.memory.ConfirmedMemoryController;
import com.agentflow.web.conversation.PersistentContextSource;
import com.agentflow.web.conversation.ConversationService;
import com.agentflow.web.conversation.ConversationController;
import com.agentflow.web.conversation.ConversationProperties;

import com.agentflow.core.AgentRuntime;
import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.core.memory.ShortTermMemory;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.llm.AgentFlowProperties;
import com.agentflow.web.agent.AgentController;
import com.agentflow.web.agent.AgentSessionEntity;
import com.agentflow.web.agent.AgentStepRepository;
import com.agentflow.web.agent.AgentTaskService;
import com.agentflow.web.agent.JpaStepRecorder;
import com.agentflow.web.agent.TaskEventPublisher;
import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.agent.AgentTaskRepository;
import com.agentflow.web.run.*;
import com.agentflow.web.support.Ids;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;
import com.agentflow.web.auth.AuthController;
import com.agentflow.web.auth.AuthService;
import com.agentflow.web.auth.JwtService;
import com.agentflow.web.auth.SysUser;
import com.agentflow.web.chat.ChatController;
import com.agentflow.web.chat.ChatService;
import com.agentflow.web.config.SecurityConfig;
import com.agentflow.web.memory.InMemoryShortTermMemory;
import com.agentflow.web.planner.SimpleTaskPlanner;
import com.agentflow.core.planner.TaskPlanner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

@AutoConfiguration(before = {com.agentflow.tool.AgentToolAutoConfiguration.class, com.agentflow.tool.ToolPolicyAutoConfiguration.class})
@AutoConfigurationPackage(basePackageClasses = {AgentSessionEntity.class, SysUser.class, ConfirmedMemoryEntity.class, ToolInvocationEntity.class, com.agentflow.web.context.HistoryRecordEntity.class})
@EnableConfigurationProperties(AgentFlowProperties.class)
@Import({
        HarnessContextConfiguration.class,
        AgentController.class,
        ApprovalController.class,
        ConfirmedMemoryController.class,
        ConversationController.class,
        com.agentflow.web.context.ContextHistoryController.class,
        AgentTaskService.class,
        RunApiExceptionHandler.class,
        AuthController.class,
        AuthService.class,
        JwtService.class,
        ChatController.class,
        ChatService.class,
        SecurityConfig.class
})
public class AgentWebAutoConfiguration {

    /**
     * Kept for the existing chat compatibility path; the Core Runtime does not depend on it.
     */
    @Bean
    @ConditionalOnMissingBean(TaskPlanner.class)
    TaskPlanner taskPlanner() {
        return new SimpleTaskPlanner();
    }

    @Bean
    @ConditionalOnMissingBean(ShortTermMemory.class)
    ShortTermMemory shortTermMemory() {
        return new InMemoryShortTermMemory();
    }

    @Bean
    @ConditionalOnMissingBean(StepRecorder.class)
    StepRecorder stepRecorder(RunPersistence persistence, RunResultProjector projector) {
        return new JpaStepRecorder(persistence, projector);
    }

    @Bean
    @ConditionalOnMissingBean
    RunLifecycleProperties runLifecycleProperties(org.springframework.core.env.Environment environment) {
        return RunLifecycleProperties.defaults().withMaxDuration(org.springframework.boot.convert.DurationStyle.detectAndParse(
                environment.getProperty("agentflow.run.max-duration", "30s")));
    }

    @Bean
    @ConditionalOnMissingBean
    RunResultProjector runResultProjector() { return new RunResultProjector(); }

    @Bean
    @ConditionalOnMissingBean
    RunEventProjector runEventProjector() { return new RunEventProjector(); }

    @Bean
    @ConditionalOnMissingBean
    TaskEventPublisher taskEventPublisher(RunEventHub hub, RunEventProjector projector) {
        return new TaskEventPublisher(hub, projector);
    }

    @Bean
    @ConditionalOnMissingBean
    RunEventHub runEventHub(ObjectMapper mapper, RunLifecycleProperties p) {
        return new InMemoryRunEventHub(mapper, p.eventWindowCount(), p.eventWindowBytes(), p.eventFrameBytes(),
                p.inFlightCapacity(), p.terminalCacheCapacity(), p.terminalCacheTtl().toNanos(),
                System::nanoTime, p.subscriptionsPerRun(), p.globalSubscriptions());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    RunSseService runSseService(RunEventHub hub, RunLifecycleProperties properties) {
        return new RunSseService(hub, properties, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    RunSseMvcConfiguration runSseMvcConfiguration() { return new RunSseMvcConfiguration(); }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    BoundedRunExecutor boundedRunExecutor(RunLifecycleProperties p) {
        AtomicInteger number = new AtomicInteger();
        return new BoundedRunExecutor(p.workerThreads(), p.queueCapacity(), runnable -> {
            Thread thread = new Thread(runnable, "agent-run-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="agentflow.harness.enabled",havingValue="true")
    @ConditionalOnMissingBean
    com.agentflow.web.context.JpaHistoryStore jpaHistoryStore(AgentSessionRepository sessions, EntityManager manager,ObjectMapper mapper) {
        return new com.agentflow.web.context.JpaHistoryStore(sessions,manager,mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    com.agentflow.web.context.ContextHistoryService contextHistoryService(EntityManager em, AgentSessionRepository sessions, ContextTextPolicy policy, RunCoordinator coordinator, org.springframework.core.env.Environment environment, ObjectMapper mapper, org.springframework.beans.factory.ObjectProvider<com.agentflow.web.context.ModelCallLedger> ledger) {
        var service = new com.agentflow.web.context.ContextHistoryService(em, sessions, policy, coordinator, environment, mapper, Clock.systemUTC());
        ledger.ifAvailable(service::configureLedger);
        return service;
    }
    @Bean
    @ConditionalOnMissingBean
    RunPersistence runPersistence(AgentSessionRepository sessions, AgentTaskRepository tasks,
                                  AgentStepRepository steps, EntityManager entityManager) {
        return new RunPersistence(sessions, tasks, steps, entityManager);
    }

    @Bean
    @ConditionalOnMissingBean
    ConversationService conversationService(EntityManager em, AgentSessionRepository sessions, ContextTextPolicy policy, RunCoordinator coordinator, org.springframework.core.env.Environment env) {
        return new ConversationService(em, sessions, policy, coordinator, env.getProperty("agentflow.harness.enabled", Boolean.class, false));
    }

    @Bean
    @ConditionalOnMissingBean
    ConfirmedMemoryService confirmedMemoryService(AgentSessionRepository sessions, ConfirmedMemoryRepository memories, ContextTextPolicy textPolicy, org.springframework.core.env.Environment env) {
        return new ConfirmedMemoryService(sessions, memories, textPolicy, env.getProperty("agentflow.harness.enabled", Boolean.class, false));
    }

    @Bean
    @ConditionalOnMissingBean
    ConversationProperties conversationProperties() {
        return ConversationProperties.defaults();
    }

    @Bean
    @ConditionalOnMissingBean(ContextSource.class)
    PersistentContextSource persistentContextSource(EntityManager entityManager, ContextTextPolicy textPolicy, org.springframework.core.env.Environment environment) {
        return new PersistentContextSource(entityManager, textPolicy, environment.getProperty("agentflow.harness.enabled", Boolean.class, false));
    }

    @Bean
    @ConditionalOnMissingBean
    ApprovalPersistence approvalPersistence(AgentTaskRepository tasks, ToolInvocationRepository invocations, EntityManager em) {
        return new ApprovalPersistence(tasks, invocations, em);
    }

    @Bean
    @ConditionalOnMissingBean
    ApprovalService approvalService(ApprovalPersistence persistence) {
        return new ApprovalService(persistence, Clock.systemUTC(), System::nanoTime);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    RunCoordinator runCoordinator(ContextSource contextSource, AgentRuntime runtime, RunPersistence persistence, RunEventHub hub,
                                  RunEventProjector eventProjector, RunResultProjector resultProjector,
                                  BoundedRunExecutor executor, RunLifecycleProperties properties,
                                  ConversationProperties conversationProperties, ApprovalService approvalService,
                                  org.springframework.core.env.Environment environment,
                                  org.springframework.beans.factory.ObjectProvider<com.agentflow.core.approval.ApprovalGate> applicationGate,
                                  org.springframework.beans.factory.ObjectProvider<com.agentflow.web.context.ContextSessionService> contextSessions) {
        RunCoordinator coordinator = new RunCoordinator(contextSource, runtime, persistence, hub, eventProjector, resultProjector, executor,
                properties, Clock.systemUTC(), System::nanoTime, Ids::newId, conversationProperties);
        coordinator.configureApprovals(approvalService, org.springframework.boot.convert.DurationStyle.detectAndParse(
                environment.getProperty("agentflow.approval.ttl", "30s")));
        applicationGate.ifAvailable(coordinator::configureApprovalGate);
        contextSessions.ifAvailable(sessions -> {
            var duration=org.springframework.boot.convert.DurationStyle.detectAndParse(environment.getProperty("agentflow.harness.run.max-duration", "10m"));
            if(duration.isNegative()||duration.isZero()||duration.compareTo(java.time.Duration.ofHours(1))>0) throw new IllegalArgumentException("Harness max-duration must be >0 and <=1h");
            coordinator.configureContextSessions(sessions,new com.agentflow.core.runtime.ExecutionBudget(
                environment.getProperty("agentflow.harness.run.max-iterations",Integer.class,8),duration,
                environment.getProperty("agentflow.harness.run.max-input-tokens",Integer.class,3000000),
                environment.getProperty("agentflow.harness.run.max-output-tokens",Integer.class,524288)));
        });
        coordinator.recoverInterrupted();
        return coordinator;
    }

}
