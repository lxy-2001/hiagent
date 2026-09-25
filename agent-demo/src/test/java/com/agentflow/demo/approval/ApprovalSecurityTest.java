package com.agentflow.demo.approval;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ApprovalSecurityTest {
    @Test void sdkLoggingIsOffInBothProductionAndExplicitFixtureProfile() throws Exception {
        var yaml = new org.yaml.snakeyaml.Yaml();
        for (String name : new String[]{"application.yml","application-fixture.yml"}) {
            java.util.Map<?,?> config;
            try (var input=getClass().getResourceAsStream("/"+name)) { config=yaml.load(input); }
            var levels=(java.util.Map<?,?>)((java.util.Map<?,?>)config.get("logging")).get("level");
            assertThat(String.valueOf(levels.get("io.modelcontextprotocol"))).isIn("OFF","false");
        }
        assertThat(Files.readString(Path.of("src/main/resources/static/approval.js")))
                .contains("textContent").doesNotContain("innerHTML", "eval(");
    }
}
