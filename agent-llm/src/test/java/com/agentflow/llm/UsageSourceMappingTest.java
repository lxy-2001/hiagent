package com.agentflow.llm;

import com.agentflow.core.model.UsageSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class UsageSourceMappingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void absentAndPartialCountersAreUnknownForBothDecisions() {
        for (String usage : new String[]{"", ",\"usage\":null", ",\"usage\":{}",
                ",\"usage\":{\"prompt_tokens\":3}", ",\"usage\":{\"completion_tokens\":2}",
                ",\"usage\":{\"prompt_tokens\":null,\"completion_tokens\":2}"}) {
            for (boolean tool : new boolean[]{false, true}) {
                assertEquals(UsageSource.UNKNOWN, decision(tool, usage).usageSource());
            }
        }
    }

    @Test void completeZeroAndPositiveCountersAreReported() {
        for (boolean tool : new boolean[]{false, true}) {
            var zero = decision(tool, ",\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}");
            assertEquals(UsageSource.REPORTED, zero.usageSource());
            assertEquals(0, zero.usage().totalTokens());
            var positive = decision(tool, ",\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}");
            assertEquals(UsageSource.REPORTED, positive.usageSource());
            assertEquals(5, positive.usage().totalTokens());
        }
    }

    @Test void invalidUsageStillFails() {
        for (String usage : new String[]{"[]", "{\"prompt_tokens\":-1}", "{\"completion_tokens\":1.5}",
                "{\"prompt_tokens\":2147483648}", "{\"prompt_tokens\":2147483647,\"completion_tokens\":1}"}) {
            assertThrows(ModelClientException.class, () -> decision(false, ",\"usage\":" + usage));
        }
    }

    private com.agentflow.core.model.ModelDecision decision(boolean tool, String usage) {
        String message = tool
                ? "{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]}"
                : "{\"content\":\"OK\"}";
        return ProviderDecisionMapper.decision(mapper.readTree("{\"id\":\"d1\",\"choices\":[{\"finish_reason\":\""
                + (tool ? "tool_calls" : "stop") + "\",\"message\":" + message + "}]" + usage + "}"), mapper);
    }
}
