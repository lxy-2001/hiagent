package com.agentflow.web.autoconfigure;

import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;

class RunAssemblyTest {
    @Test void lifecycleResourcesAreExplicitConditionalBeans() {
        for (String name : java.util.List.of("runLifecycleProperties","runResultProjector","runEventProjector","runEventHub","boundedRunExecutor","runPersistence","runCoordinator")) {
            Method method=java.util.Arrays.stream(AgentWebAutoConfiguration.class.getDeclaredMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();
            assertThat(method.isAnnotationPresent(Bean.class)).isTrue(); assertThat(method.isAnnotationPresent(ConditionalOnMissingBean.class)).isTrue();
        }
    }
}
