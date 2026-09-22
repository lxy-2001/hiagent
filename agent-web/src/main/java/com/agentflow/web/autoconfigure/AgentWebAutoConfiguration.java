package com.agentflow.web.autoconfigure;

import com.agentflow.core.context.ContextSource;
import com.agentflow.web.memory.ConfirmedMemoryEntity;
import com.agentflow.web.memory.ConfirmedMemoryRepository;
import com.agentflow.web.memory.ConfirmedMemoryService;
import com.agentflow.web.memory.ConfirmedMemoryController;
import com.agentflow.web.conversation.PersistentContextSource;
import com.agentflow.web.conversation.ConversationProperties;

import com.agentflow.core.AgentRuntime;
import com.agentflow.core.context.ContextPolicy;
import com.agentflow.core.context.ContextAssembler;
import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.core.context.TokenEstimator;
import com.agentflow.core.context.Utf8TokenEstimator;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.core.memory.ShortTermMemory;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.runtime.DefaultAgentRuntime;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.DefaultToolExecutor;
import com.agentflow.core.tool.DefaultToolResultNormalizer;
import com.agentflow.core.tool.ToolExecutor;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.core.tool.ToolResultNormalizer;
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

@AutoConfiguration
@AutoConfigurationPackage(basePackageClasses = {AgentSessionEntity.class, SysUser.class, ConfirmedMemoryEntity.class})
@EnableConfigurationProperties(AgentFlowProperties.class)
@Import({
        AgentController.class,
        ConfirmedMemoryController.class,
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
    RunLifecycleProperties runLifecycleProperties() {
        return RunLifecycleProperties.defaults();
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
    @ConditionalOnMissingBean
    RunPersistence runPersistence(AgentSessionRepository sessions, AgentTaskRepository tasks,
                                  AgentStepRepository steps, EntityManager entityManager) {
        return new RunPersistence(sessions, tasks, steps, entityManager);
    }

    @Bean
    @ConditionalOnMissingBean
    ConfirmedMemoryService confirmedMemoryService(AgentSessionRepository sessions, ConfirmedMemoryRepository memories, ContextTextPolicy textPolicy) {
        return new ConfirmedMemoryService(sessions, memories, textPolicy);
    }

    @Bean
    @ConditionalOnMissingBean
    ConversationProperties conversationProperties() {
        return ConversationProperties.defaults();
    }

    @Bean
    @ConditionalOnMissingBean(ContextSource.class)
    PersistentContextSource persistentContextSource(EntityManager entityManager, ContextTextPolicy textPolicy) {
        return new PersistentContextSource(entityManager, textPolicy);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    RunCoordinator runCoordinator(ContextSource contextSource, AgentRuntime runtime, RunPersistence persistence, RunEventHub hub,
                                  RunEventProjector eventProjector, RunResultProjector resultProjector,
                                  BoundedRunExecutor executor, RunLifecycleProperties properties,
                                  ConversationProperties conversationProperties) {
        RunCoordinator coordinator = new RunCoordinator(contextSource, runtime, persistence, hub, eventProjector, resultProjector, executor,
                properties, Clock.systemUTC(), System::nanoTime, Ids::newId, conversationProperties);
        coordinator.recoverInterrupted();
        return coordinator;
    }

    @Bean
    @ConditionalOnMissingBean(ToolResultNormalizer.class)
    ToolResultNormalizer toolResultNormalizer() {
        return new DefaultToolResultNormalizer();
    }

    @Bean
    @ConditionalOnMissingBean(ToolExecutor.class)
    ToolExecutor toolExecutor(ToolRegistry toolRegistry, ToolResultNormalizer resultNormalizer) {
        return new DefaultToolExecutor(toolRegistry, resultNormalizer);
    }

    @Bean
    @ConditionalOnMissingBean
    ContextPolicy contextPolicy() { return ContextPolicy.defaults(); }

    @Bean
    @ConditionalOnMissingBean
    ContextTextPolicy contextTextPolicy() { return new ContextTextPolicy(); }

    @Bean
    @ConditionalOnMissingBean(TokenEstimator.class)
    TokenEstimator tokenEstimator() { return new Utf8TokenEstimator(); }

    @Bean
    @ConditionalOnMissingBean
    ContextAssembler contextAssembler(ContextPolicy policy, TokenEstimator estimator, ContextTextPolicy textPolicy) {
        return new ContextAssembler(policy, estimator, textPolicy);
    }

    @Bean
    @ConditionalOnMissingBean(AgentRuntime.class)
    AgentRuntime agentRuntime(
            AgentModelClient modelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            StepRecorder stepRecorder,
            ToolResultNormalizer resultNormalizer,
            ContextAssembler contextAssembler) {
        return new DefaultAgentRuntime(modelClient, toolRegistry, toolExecutor, stepRecorder, resultNormalizer,
                TimeSource.system(), contextAssembler);
    }
}
