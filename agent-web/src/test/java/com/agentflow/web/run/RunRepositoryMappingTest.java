package com.agentflow.web.run;

import com.agentflow.web.agent.AgentStepEntity;
import com.agentflow.web.agent.AgentStepRepository;
import com.agentflow.web.agent.AgentTaskEntity;
import com.agentflow.web.agent.AgentTaskRepository;
import com.agentflow.web.autoconfigure.AgentWebAutoConfiguration;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = RunRepositoryMappingTest.TestApplication.class)
class RunRepositoryMappingTest {

    @Autowired AgentTaskRepository tasks;

    @Test
    void roundTripsAllLifecycleFields() throws Exception {
        Instant created = Instant.parse("2026-09-15T10:00:00Z");
        AgentTaskEntity entity = new AgentTaskEntity("run-1", "session-1", "user-1",
                "input", "RUNNING", created);
        set(entity, "startedAt", created.plusSeconds(1));
        set(entity, "finishedAt", created.plusSeconds(2));
        set(entity, "cancelRequested", true);
        set(entity, "terminationReason", "BUDGET_EXCEEDED");
        set(entity, "runtimeReason", "BUDGET_EXCEEDED");
        set(entity, "errorCode", "BUDGET_EXCEEDED");
        set(entity, "recordingComplete", true);
        set(entity, "promptTokens", 7);
        set(entity, "completionTokens", 3);
        set(entity, "totalTokens", 10);

        tasks.saveAndFlush(entity);
        AgentTaskEntity restored = tasks.findById("run-1").orElseThrow();

        assertThat(get(restored, "startedAt")).isEqualTo(created.plusSeconds(1));
        assertThat(get(restored, "finishedAt")).isEqualTo(created.plusSeconds(2));
        assertThat(get(restored, "cancelRequested")).isEqualTo(true);
        assertThat(get(restored, "terminationReason")).isEqualTo("BUDGET_EXCEEDED");
        assertThat(get(restored, "runtimeReason")).isEqualTo("BUDGET_EXCEEDED");
        assertThat(get(restored, "recordingComplete")).isEqualTo(true);
        assertThat(get(restored, "totalTokens")).isEqualTo(10);
    }

    @Test
    void declaresExplicitTaskRowLockAndBoundedStatusScan() throws Exception {
        Method lockMethod = AgentTaskRepository.class.getMethod("findByIdForUpdate", String.class);
        assertThat(lockMethod.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);

        for (int i = 0; i < 105; i++) {
            tasks.save(new AgentTaskEntity("run-%03d".formatted(i), "session", "user",
                    "input", "RUNNING", Instant.EPOCH));
        }
        Method scan = AgentTaskRepository.class.getMethod("findInterruptedAfter",
                Collection.class, String.class, org.springframework.data.domain.Pageable.class);
        @SuppressWarnings("unchecked")
        List<AgentTaskEntity> first = (List<AgentTaskEntity>) scan.invoke(tasks,
                List.of("QUEUED", "RUNNING"), "", PageRequest.of(0, 100));

        assertThat(first).hasSize(100);
        assertThat(first).extracting(AgentTaskEntity::getId).isSorted();
    }

    @Test
    void keepsStepUniquenessAndNewCorrelationColumnsInWebOnly() throws Exception {
        assertThat(AgentStepEntity.class.getDeclaredField("decisionId")).isNotNull();
        assertThat(AgentStepEntity.class.getDeclaredField("callId")).isNotNull();
        assertThat(AgentStepEntity.class.getDeclaredField("errorCode")).isNotNull();
        assertThat(AgentStepEntity.class.getDeclaredField("terminal")).isNotNull();
        assertThat(RunLifecycleStatus.class.getPackageName()).isEqualTo("com.agentflow.web.run");
        assertThat(AgentStepRepository.class.getMethod("findTop256ByTaskIdOrderByStepNoAsc",
                String.class)).isNotNull();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {AgentWebAutoConfiguration.class, com.agentflow.autoconfigure.AgentRuntimeAutoConfiguration.class})
    @EntityScan(basePackageClasses = AgentTaskEntity.class)
    @EnableJpaRepositories(basePackageClasses = AgentTaskRepository.class)
    @Configuration(proxyBeanMethods = false)
    static class TestApplication {
    }
}
