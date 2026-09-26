package com.agentflow.autoconfigure;

import com.agentflow.core.AgentRuntime;
import com.agentflow.core.context.*;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.runtime.DefaultAgentRuntime;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Optional Spring assembly; all execution semantics remain in agent-core. */
@AutoConfiguration(afterName = {
        "com.agentflow.llm.AgentLlmAutoConfiguration",
        "com.agentflow.tool.AgentToolAutoConfiguration",
        "com.agentflow.tool.ToolPolicyAutoConfiguration",
        "com.agentflow.web.autoconfigure.AgentWebAutoConfiguration"})
public class AgentRuntimeAutoConfiguration {
    @Bean @ConditionalOnMissingBean(ToolResultNormalizer.class)
    ToolResultNormalizer toolResultNormalizer() { return new DefaultToolResultNormalizer(); }

    @Bean @ConditionalOnMissingBean(ToolExecutor.class)
    ToolExecutor toolExecutor(ToolRegistry registry, ToolResultNormalizer normalizer) {
        return new DefaultToolExecutor(registry, normalizer);
    }

    @Bean @ConditionalOnMissingBean
    ContextPolicy contextPolicy() { return ContextPolicy.defaults(); }

    @Bean @ConditionalOnMissingBean
    ContextTextPolicy contextTextPolicy() { return new ContextTextPolicy(); }

    @Bean @ConditionalOnMissingBean(TokenEstimator.class)
    TokenEstimator tokenEstimator() { return new Utf8TokenEstimator(); }

    @Bean @ConditionalOnMissingBean
    ContextAssembler contextAssembler(ContextPolicy policy, TokenEstimator estimator, ContextTextPolicy textPolicy) {
        return new ContextAssembler(policy, estimator, textPolicy);
    }

    @Bean @ConditionalOnMissingBean
    TimeSource timeSource() { return TimeSource.system(); }

    @Bean @ConditionalOnMissingBean(AgentRuntime.class)
    AgentRuntime agentRuntime(AgentModelClient model, ToolRegistry registry, ToolExecutor executor,
                              ObjectProvider<StepRecorder> recorder, ToolResultNormalizer normalizer,
                              TimeSource clock, ContextAssembler assembler, ToolExecutionPolicy policy) {
        return new DefaultAgentRuntime(model, registry, executor, recorder.getIfAvailable(), normalizer,
                clock, assembler, policy);
    }
}
