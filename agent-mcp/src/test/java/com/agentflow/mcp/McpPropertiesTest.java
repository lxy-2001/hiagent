package com.agentflow.mcp;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class McpPropertiesTest {
    @Test void enabledConfigurationMustBeUsableAndBounded() {
        assertThrows(IllegalArgumentException.class,()->new McpProperties(true,List.of(),Duration.ofSeconds(5)));
        var server=new McpProperties.Server("demo",URI.create("https://example.com/mcp"),"",Set.of("lookup"));
        assertThrows(IllegalArgumentException.class,()->new McpProperties(true,List.of(server,server),Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,()->new McpProperties(true,List.of(server),Duration.ofSeconds(11)));
        assertThrows(IllegalArgumentException.class,()->new McpProperties(true,List.of(server),Duration.ZERO));
        assertThrows(IllegalArgumentException.class,()->new McpProperties(true,List.of(new McpProperties.Server("demo",server.url(),"",Set.of())),Duration.ofSeconds(5)));
        assertDoesNotThrow(()->new McpProperties(false,List.of(new McpProperties.Server("invalid",URI.create("file:///tmp/x"),"",Set.of())),Duration.ZERO));
    }
}
