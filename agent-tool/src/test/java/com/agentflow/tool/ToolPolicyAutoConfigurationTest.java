package com.agentflow.tool;

import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ToolPolicyAutoConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolPolicyAutoConfiguration.class));
    private static final String[] RULE={"agentflow.tool-policy.rules[0].tool-name=safe", "agentflow.tool-policy.rules[0].action=ALLOW",
            "agentflow.tool-policy.rules[0].risk=LOW", "agentflow.tool-policy.rules[0].effect=READ_ONLY", "agentflow.tool-policy.rules[0].action-summary=Read public data"};
    @Test void bindsExplicitRulesAndDeniesUnknown() {
        runner.withPropertyValues(RULE).run(context->{
            assertThat(context).hasNotFailed();var policy=context.getBean(ToolExecutionPolicy.class);
            assertThat(policy.decide("safe").action()).isEqualTo(ToolPolicyDecision.Action.ALLOW);
            assertThat(policy.decide("custom").action()).isEqualTo(ToolPolicyDecision.Action.DENY);
        });
    }
    @Test void rejectsUnsafeRulesAndSecretPreviews() {
        for(String property:new String[]{"effect=WRITE","risk=HIGH","visible-arguments[0]=API_KEY"})
            runner.withPropertyValues(RULE).withPropertyValues("agentflow.tool-policy.rules[0]."+property)
                    .run(context->assertThat(context).hasFailed());
    }
    @Test void duplicateNamesFail() {
        runner.withPropertyValues(RULE).withPropertyValues("agentflow.tool-policy.rules[1].tool-name=safe",
                "agentflow.tool-policy.rules[1].action=DENY","agentflow.tool-policy.rules[1].risk=LOW",
                "agentflow.tool-policy.rules[1].effect=READ_ONLY","agentflow.tool-policy.rules[1].action-summary=deny")
                .run(context->assertThat(context).hasFailed());
    }
    @Test void applicationPolicyWins() {
        var policy=ToolExecutionPolicy.denyAll();
        runner.withBean(ToolExecutionPolicy.class,()->policy).run(context->assertThat(context.getBean(ToolExecutionPolicy.class)).isSameAs(policy));
    }
}
