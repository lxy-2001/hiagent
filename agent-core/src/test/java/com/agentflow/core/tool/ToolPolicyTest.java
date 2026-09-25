package com.agentflow.core.tool;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.agentflow.core.tool.ToolPolicyDecision.*;
import static org.junit.jupiter.api.Assertions.*;
class ToolPolicyTest {
 @Test void riskyOrWriteCannotAllow() {
  assertThrows(IllegalArgumentException.class,()->new ToolPolicyDecision(Action.ALLOW,RiskLevel.HIGH,Effect.READ_ONLY,"read",Set.of()));
  assertThrows(IllegalArgumentException.class,()->new ToolPolicyDecision(Action.ALLOW,RiskLevel.LOW,Effect.WRITE,"write",Set.of()));
 }
 @Test void versionBindsLocalFields() {
  var a=new ToolPolicyDecision(Action.REQUIRE_APPROVAL,RiskLevel.HIGH,Effect.WRITE,"append",Set.of("title"));
  assertTrue(a.policyVersion().matches("[a-f0-9]{64}"));
  assertNotEquals(a.policyVersion(),new ToolPolicyDecision(Action.DENY,a.risk(),a.effect(),a.actionSummary(),a.visibleArgumentNames()).policyVersion());
  assertNotEquals(a.policyVersion(),new ToolPolicyDecision(a.action(),a.risk(),a.effect(),"other",a.visibleArgumentNames()).policyVersion());
  assertNotEquals(a.policyVersion(),new ToolPolicyDecision(a.action(),a.risk(),a.effect(),a.actionSummary(),Set.of()).policyVersion());
 }
 @Test void previewNamesAreImmutableAndSummaryBounded() {
  var names=new HashSet<>(Set.of("title"));
  var a=new ToolPolicyDecision(Action.REQUIRE_APPROVAL,RiskLevel.HIGH,Effect.WRITE,"append",names);
  names.add("secret"); assertEquals(Set.of("title"),a.visibleArgumentNames());
  assertThrows(IllegalArgumentException.class,()->new ToolPolicyDecision(Action.DENY,RiskLevel.LOW,Effect.READ_ONLY,"x".repeat(257),Set.of()));
 }
}
