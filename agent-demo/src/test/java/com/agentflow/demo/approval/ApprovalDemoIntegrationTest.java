package com.agentflow.demo.approval;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.Map;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ApprovalDemoIntegrationTest {
    @Test void realHttpApprovalResumesOriginalRuntimeAndExecutesExactlyOnce(org.springframework.boot.test.system.CapturedOutput logs) throws Exception {
        try (var fixture = FixtureDemoLauncher.start(0, 0)) {
            var run = fixture.request("POST", "/api/agent/tasks", Map.of("input", "保存一条Java17演示笔记"));
            String task = run.path("taskId").asString();
            var pending = fixture.awaitApproval(task);
            assertThat(pending.path("status").asString()).isEqualTo("PENDING");
            assertThat(pending.toString()).doesNotContain(OfflineMcpFixture.PRIVATE_BODY);
            assertThat(fixture.server().writes()).isZero();
            String decision = "/api/agent/tasks/"+task+"/approvals/"+pending.path("approvalId").asString()+"/decision";
            var pool=java.util.concurrent.Executors.newFixedThreadPool(20);
            try {
                var start=new java.util.concurrent.CountDownLatch(1);
                var approvals=new java.util.ArrayList<java.util.concurrent.Future<tools.jackson.databind.JsonNode>>();
                for(int i=0;i<20;i++) approvals.add(pool.submit(() -> { start.await(); return fixture.request("POST",decision,Map.of("decision","APPROVE")); }));
                start.countDown();
                for(var approval:approvals) assertThat(approval.get(10,java.util.concurrent.TimeUnit.SECONDS).path("status").asString()).isEqualTo("APPROVED");
            } finally { pool.shutdownNow(); }
            assertThat(fixture.awaitTerminal(task).path("status").asString()).isEqualTo("SUCCEEDED");
            var stored = fixture.context().getBean(com.agentflow.web.approval.ApprovalPersistence.class)
                    .getOwned("fixture-owner",task,pending.path("approvalId").asString());
            assertThat(stored.status()).isEqualTo(com.agentflow.core.approval.ApprovalStatus.APPROVED);
            assertThat(stored.decisionSource()).isEqualTo(com.agentflow.core.approval.ApprovalResolution.DecisionSource.USER);
            fixture.request("POST", decision, Map.of("decision","APPROVE"));
            assertThat(fixture.server().writes()).isEqualTo(1);
            assertThat(fixture.server().lastBody()).isEqualTo(OfflineMcpFixture.PRIVATE_BODY);
            assertThat(fixture.request("GET", "/api/agent/tasks/"+task+"/steps", null).toString()).doesNotContain(OfflineMcpFixture.PRIVATE_BODY);
            assertThat(fixture.events(task)).contains("APPROVAL_REQUESTED", "APPROVAL_RESOLVED")
                    .doesNotContain(OfflineMcpFixture.PRIVATE_BODY, OfflineMcpFixture.API_SECRET);
            assertThat(fixture.request("GET", "/api/agent/tasks/"+task, null).path("citations")).isEmpty();
            assertThat(logs.getAll()).doesNotContain(OfflineMcpFixture.PRIVATE_BODY, OfflineMcpFixture.API_SECRET);
            var sql = fixture.context().getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            var row = sql.queryForMap("select * from agent_tool_invocation where task_id=?",task);
            var fact = new com.agentflow.core.tool.ToolInvocationRecord("006-v1",task,(String)row.get("call_id"),(String)row.get("tool_name"),
                    (String)row.get("server_id"),(String)row.get("remote_tool_name"),(String)row.get("definition_version"),
                    (String)row.get("policy_version"),(String)row.get("arguments_digest"),com.agentflow.core.tool.RiskLevel.valueOf((String)row.get("risk")),
                    com.agentflow.core.tool.ToolPolicyDecision.Effect.valueOf((String)row.get("effect")),
                    com.agentflow.core.tool.ToolPolicyDecision.Action.valueOf((String)row.get("policy_action")),
                    ((java.sql.Timestamp)row.get("created_at")).toInstant(), ((java.sql.Timestamp)row.get("dispatch_at")).toInstant(),
                    java.util.UUID.fromString((String)row.get("id")), com.agentflow.core.approval.ApprovalStatus.valueOf((String)row.get("approval_status")),
                    ((Number)row.get("approval_wait_ms")).longValue(), ((Number)row.get("dispatch_count")).intValue(),
                    ((Number)row.get("execution_ms")).longValue(), com.agentflow.core.tool.ToolInvocationRecord.Outcome.valueOf((String)row.get("outcome")),
                    (String)row.get("error_code"));
            var payload = new tools.jackson.databind.ObjectMapper().writeValueAsString(fact);
            var schema = com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                    .getSchema(getClass().getResourceAsStream("/feature006/tool-invocation.schema.json"));
            assertThat(schema.validate(payload, com.networknt.schema.InputFormat.JSON,
                    context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))).isEmpty();
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/feature006"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/feature006/approved-invocation.json"),payload);
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/feature006/fixture-receipt.json"),
                    new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("caseId","APR-01","taskId",task,
                            "callId",fact.callId(),"fixtureWrites",fixture.server().writes(),"concurrentApprovals",20,"mode","OFFLINE_FIXTURE")));
            var rejectedRun=fixture.request("POST","/api/agent/tasks",Map.of("input","reject offline note"));
            String rejectedId=rejectedRun.path("taskId").asString();
            var rejected=fixture.awaitApproval(rejectedId);
            fixture.request("POST","/api/agent/tasks/"+rejectedId+"/approvals/"+rejected.path("approvalId").asString()+"/decision",Map.of("decision","REJECT"));
            assertThat(fixture.awaitTerminal(rejectedId).path("terminationReason").asString()).isEqualTo("APPROVAL_REJECTED");
            assertThat(fixture.server().writes()).isEqualTo(1);
        }
    }
}
