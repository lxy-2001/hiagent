package com.agentflow.demo.workbench;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkbenchAssetsTest extends WorkbenchHttpTestSupport {
    @Test void hashedAssetsLoadBeforeLoginAndHaveImmutableCaching() throws Exception {
        var response = get("/assets/workbench-probe-009.js", false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("workbenchFixtureAsset");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("immutable");
    }

    @Test void entryIsRevalidatedAndApiPathsAreNeverSpaFallback() throws Exception {
        var page = get("/index.html", false);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Cache-Control").orElse("")).contains("no-cache");
        assertThat(get("/api/agent/sessions", false).statusCode()).isEqualTo(401);
        var unknown = get("/api/not-a-route", true);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).doesNotContain("<html", "id=\"app\"");
    }
}
