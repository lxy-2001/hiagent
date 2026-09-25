package com.agentflow.mcp;
import com.agentflow.core.tool.*;
import com.agentflow.mcp.fixture.LoopbackMcpServer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class McpAgentToolTest {
    @Test void realTextBlocksPreserveOrderAndNeverGainRetrievalEvidence() {
        var result=call("{\"content\":[{\"type\":\"text\",\"text\":\"one\"},{\"type\":\"text\",\"text\":\"two\"}],\"isError\":false}");
        assertEquals("one\ntwo",result.output());assertNull(result.retrievalPayload());
    }
    @Test void emptyTextBlocksStillPreserveTheirSeparator() {
        assertEquals("\nx",call("{\"content\":[{\"type\":\"text\",\"text\":\"\"},{\"type\":\"text\",\"text\":\"x\"}]}").output());
    }
    @Test void unsupportedOrFailedResultsAreNeverReportedAsSuccess() {
        for(var entry:Map.of(
                "{\"content\":[],\"isError\":false}","TOOL_RESULT_INVALID",
                "{\"content\":[{\"type\":\"text\",\"text\":\"failure\"}],\"isError\":true}","MCP_TOOL_ERROR",
                "{\"content\":[{\"type\":\"image\",\"data\":\"abc\",\"mimeType\":\"image/png\"}]}","MCP_RESULT_UNSUPPORTED",
                "{\"content\":[{\"type\":\"text\",\"text\":\"x\"}],\"structuredContent\":{\"x\":1}}","MCP_RESULT_UNSUPPORTED").entrySet()) {
            var result=call(entry.getKey());assertEquals(ToolResultStatus.FAILED,result.status());assertEquals(entry.getValue(),result.errorCode());
        }
        assertEquals("TOOL_RESULT_TOO_LARGE",call("{\"content\":[{\"type\":\"text\",\"text\":\""+"a".repeat(8193)+"\"}]}").errorCode());
    }
    private ToolResult call(String json) {
        try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().callResult(json));var client=SdkMcpClientOperationsTest.client(server)) {
            client.initialize(SdkMcpClientOperationsTest.control());
            var tool=new McpAgentTool(new ToolDefinition("mcp.demo.project_info","fixture",RiskLevel.LOW,new ToolSchema(Map.of())),"project_info",client);
            var result=tool.execute(new ToolArguments(Map.of()),new ToolContext("run","session","owner"));
            assertEquals(1,server.callCount());return result;
        }
    }
}
