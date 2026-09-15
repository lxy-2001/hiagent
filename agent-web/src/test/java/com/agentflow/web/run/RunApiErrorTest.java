package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class RunApiErrorTest {
    @Test void writesFixedNoStoreJsonWithoutExceptionDetails() throws Exception {
        MockHttpServletResponse response=new MockHttpServletResponse(); new RunApiErrorWriter(new ObjectMapper()).write(response,503,"PERSISTENCE_UNAVAILABLE","task-1");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(response.getHeader("Location")).endsWith("task-1");
        assertThat(response.getContentAsString()).contains("PERSISTENCE_UNAVAILABLE").doesNotContain("SQLException");
    }
}
