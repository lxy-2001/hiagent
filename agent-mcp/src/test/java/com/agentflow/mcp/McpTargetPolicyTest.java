package com.agentflow.mcp;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
class McpTargetPolicyTest {
    @Test void targetRequiresExactSecureOrLiteralLoopbackEndpoint() {
        for(String url:List.of("http://example.com/mcp","http://localhost/mcp","file:///tmp/mcp",
                "https://user:pass@example.com/mcp","https://example.com/mcp?q=x","https://example.com/mcp#x"))
            assertThrows(IllegalArgumentException.class,()->settings(url,""),url);
        for(String url:List.of("https://example.com/mcp","http://127.0.0.1:1234/mcp","http://[::1]:1234/mcp"))
            assertDoesNotThrow(()->settings(url,""));
    }
    @Test void rejectsHeaderInjectionWithoutEchoingCredential() {
        for(String key:List.of("private-fake-key\rX: value","private-fake-key\nX: value")) {
            var ex=assertThrows(IllegalArgumentException.class,()->settings("https://example.com/mcp",key));
            assertFalse(ex.toString().contains("private-fake-key"));
        }
        assertFalse(settings("https://example.com/mcp","private-fake-key").toString().contains("private-fake-key"));
    }
    static McpProperties settings(String url,String key) {
        return new McpProperties(true,List.of(new McpProperties.Server("demo",URI.create(url),key,Set.of("project_info"))),Duration.ofSeconds(5));
    }
}
