package com.agentflow.core.tool;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ToolArgumentDigestTest {
 @Test void canonicalOrderingNumbersAndUnicode() {
  var a = new LinkedHashMap<String,Object>(); a.put("z", List.of("秦", "\n\"", true)); a.put("a",1.0);
  var b = new LinkedHashMap<String,Object>(); b.put("a",1); b.put("z", List.of("秦", "\n\"", true));
  String digest=ToolArgumentDigest.digest(new ToolArguments(a));
  assertTrue(digest.matches("[a-f0-9]{64}"));
  assertEquals(digest,ToolArgumentDigest.digest(new ToolArguments(b)));
  assertEquals(hash(0),hash(-0.0)); assertNotEquals(hash(1),hash(2));
 }
 @Test void arraysAndStringsAreUnambiguous() {
  assertNotEquals(hash(List.of(1,2)),hash(List.of(2,1))); assertNotEquals(hash("1"),hash(1));
  assertNotEquals(hash("\n"),hash("\\n")); assertDoesNotThrow(()->hash("🚀"));
  assertThrows(IllegalArgumentException.class,()->hash("\ud800"));
  assertThrows(IllegalArgumentException.class,()->hash("\udc00"));
 }
 @Test void canonicalUtf8LimitIsExact() {
  var values=new LinkedHashMap<String,Object>();
  for(int i=0;i<7;i++) values.put("k"+i,"a".repeat(4096));
  values.put("k7","a".repeat(4031));
  assertDoesNotThrow(()->ToolArgumentDigest.digest(new ToolArguments(values)));
  values.put("k7","a".repeat(4032));
  assertThrows(IllegalArgumentException.class,()->ToolArgumentDigest.digest(new ToolArguments(values)));
  assertThrows(IllegalArgumentException.class,()->hash(List.of("秦".repeat(4096),"秦".repeat(4096),"秦".repeat(4096))));
 }
 @Test void definitionFingerprintBindsSchemaAndRisk() {
  var a=new ToolDefinition("lookup","description",RiskLevel.LOW,new ToolSchema(Map.of()));
  assertTrue(ToolArgumentDigest.definitionVersion(a).matches("[a-f0-9]{64}"));
  assertNotEquals(ToolArgumentDigest.definitionVersion(a),ToolArgumentDigest.definitionVersion(new ToolDefinition("lookup","description",RiskLevel.HIGH,a.schema())));
  assertNotEquals(ToolArgumentDigest.definitionVersion(a),ToolArgumentDigest.definitionVersion(new ToolDefinition("lookup","description",RiskLevel.LOW,new ToolSchema(Map.of("x",ParameterSpec.requiredString())))));
 }
 private String hash(Object value) { return ToolArgumentDigest.digest(new ToolArguments(Map.of("x",value))); }
}
