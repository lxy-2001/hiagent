package com.agentflow.web.run;

import com.agentflow.web.config.RateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RunFilterChainTest {
    @Test void taskPathDependencyFailureUsesTheSharedJsonError() throws Exception {
        StringRedisTemplate redis=mock(StringRedisTemplate.class); when(redis.opsForValue()).thenThrow(new IllegalStateException("redis password=secret"));
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/agent/tasks/task-1");
        MockHttpServletResponse response=new MockHttpServletResponse();
        new RateLimitFilter(redis,new RunApiErrorWriter(new ObjectMapper())).doFilter(request,response,(req,res)->{throw new AssertionError("chain continued");});
        assertThat(response.getStatus()).isEqualTo(503); assertThat(response.getContentAsString()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("secret");
    }
}
