package com.agentflow.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

@org.springframework.boot.context.properties.ConfigurationProperties("agentflow.mcp")
public record McpProperties(boolean enabled, List<Server> servers, Duration requestTimeout) {
    public McpProperties {
        servers = servers == null ? List.of() : List.copyOf(servers);
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        if (enabled) {
            if (servers.isEmpty() || servers.size() > 4 || requestTimeout.isZero() || requestTimeout.isNegative()
                    || requestTimeout.compareTo(Duration.ofSeconds(10)) > 0) invalid();
            Set<String> ids=new java.util.HashSet<>();
            for (Server server:servers) {
                if (server.id()==null || !server.id().matches("[a-z][a-z0-9-]{0,31}") || !ids.add(server.id())) invalid();
                URI uri=server.url();
                if (uri==null || uri.getHost()==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null
                        || uri.getRawFragment()!=null || uri.getPort()==0 || uri.getPort()>65535) invalid();
                boolean https="https".equals(uri.getScheme());
                boolean loopback="127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
                if (!https && !("http".equals(uri.getScheme()) && loopback)) invalid();
                if (uri.getRawPath()==null || !uri.getRawPath().startsWith("/") || !uri.normalize().equals(uri)) invalid();
                if (server.apiKey()!=null && (server.apiKey().contains("\r") || server.apiKey().contains("\n"))) invalid();
                if (server.allowedTools()==null || server.allowedTools().isEmpty() || server.allowedTools().size()>32) invalid();
                for(String name:server.allowedTools()) new McpSchemaMapper().internalName(server.id(),name);
            }
        }
    }
    private static void invalid() { throw new IllegalArgumentException("MCP_CONFIG_INVALID"); }
    @Override public String toString() { return "McpProperties[enabled="+enabled+", serverCount="+servers.size()+"]"; }
    public record Server(String id, URI url, String apiKey, Set<String> allowedTools) {
        public Server { allowedTools=allowedTools==null?Set.of():Set.copyOf(allowedTools); }
        @Override public String toString() { return "McpServer[id=" + id + "]"; }
    }
}
