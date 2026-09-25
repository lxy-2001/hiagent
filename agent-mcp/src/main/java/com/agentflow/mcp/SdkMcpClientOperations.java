package com.agentflow.mcp;

import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.core.tool.ToolArguments;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded operation per server; waits and network requests share the caller deadline. */
public final class SdkMcpClientOperations implements McpClientOperations {
    private final McpAsyncClient client;
    private final ProfiledMcpTransport transport;
    private final Duration timeout;
    private final Semaphore operation = new Semaphore(1);
    private final AtomicReference<McpSchema.JSONRPCResponse> response = new AtomicReference<>();
    private volatile boolean closed;

    public SdkMcpClientOperations(McpProperties.Server server, Duration timeout) {
        new McpProperties(true,List.of(server),timeout);
        this.timeout=timeout;
        transport=ProfiledMcpTransport.create(server.url(),server.apiKey());
        transport.observeResponses(response::set);
        client=McpClient.async(transport).requestTimeout(timeout).initializationTimeout(timeout)
                .clientInfo(new McpSchema.Implementation("hiagent","0.1.0"))
                .capabilities(McpSchema.ClientCapabilities.builder().build()).enableCallToolSchemaCaching(false).build();
    }
    @Override public void initialize(ToolExecutionControl control) {
        McpSchema.InitializeResult initialized=perform(client.initialize(),control,false);
        if (initialized==null || !"2025-11-25".equals(initialized.protocolVersion())
                || initialized.capabilities()==null || initialized.capabilities().tools()==null) {
            close();throw new McpOperationException("MCP_PROTOCOL_ERROR");
        }
    }
    @Override public Page listTools(String cursor,ToolExecutionControl control) {
        Map<String,Object> result=performRaw(client.listTools(cursor),control);
        if (!(result.get("tools") instanceof List<?> tools)) throw new McpOperationException("MCP_PROTOCOL_ERROR");
        List<Map<String,Object>> mapped=new ArrayList<>();
        for(Object tool:tools) mapped.add(object(tool));
        Object next=result.get("nextCursor");
        if(next!=null && !(next instanceof String)) throw new McpOperationException("MCP_PROTOCOL_ERROR");
        return new Page(mapped,(String)next);
    }
    @Override public Map<String,Object> call(String name,ToolArguments args,ToolExecutionControl control) {
        return performRaw(client.callTool(new McpSchema.CallToolRequest(name,args.values())),control);
    }
    private Map<String,Object> performRaw(Mono<?> mono,ToolExecutionControl control) {
        return perform(mono,control,true);
    }
    @SuppressWarnings("unchecked")
    private <T> T perform(Mono<?> mono,ToolExecutionControl parent,boolean raw) {
        ToolExecutionControl control=parent.child(timeout);
        boolean acquired=false;
        CompletableFuture<?> future=null;
        try {
            while (!(acquired=operation.tryAcquire(25,TimeUnit.MILLISECONDS))) control.checkActive();
            control.checkActive();
            if (closed) throw new McpOperationException("MCP_UNAVAILABLE");
            response.set(null);
            future=mono.toFuture(); // subscribe exactly once; never repeat an ambiguous call
            while (true) {
                if (transport.protocolViolation()) throw new McpOperationException("MCP_PROTOCOL_ERROR");
                control.checkActive();
                try {
                    Object value=future.get(Math.max(1,Math.min(25,control.remainingTime().toMillis())),TimeUnit.MILLISECONDS);
                    return raw?(T)responseObject():(T)value;
                } catch(TimeoutException pending) {
                    // Poll cancellation without retaining an unbounded callback queue.
                } catch(ExecutionException failure) {
                    // The SDK's typed Tool/Content records cannot represent every schema/content.
                    // Only an already-correlated, complete decoded response can bypass that conversion.
                    McpSchema.JSONRPCResponse received=response.get();
                    if(raw && received!=null && received.error()==null && received.result() instanceof Map<?,?>)
                        return (T)responseObject();
                    throw new McpOperationException(failureCode(failure,received));
                }
            }
        } catch(McpOperationException ex) {
            if(acquired) close();throw ex;
        } catch(ToolExecutionControl.ExecutionTimedOutException ex) {
            if(acquired) close();throw new McpOperationException("MCP_TIMEOUT");
        } catch(CancellationException ex) {
            if(acquired) close();throw new McpOperationException("CANCELLED");
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();close();throw new McpOperationException("CANCELLED");
        } finally {
            if(future!=null && !future.isDone()) future.cancel(true);
            if(acquired) operation.release();
        }
    }
    private String failureCode(Throwable failure,McpSchema.JSONRPCResponse received) {
        if(transport.protocolViolation() || received!=null) return "MCP_PROTOCOL_ERROR";
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof TimeoutException) return "MCP_TIMEOUT";
            if(cause instanceof tools.jackson.core.JacksonException) return "MCP_PROTOCOL_ERROR";
        }
        return "MCP_UNAVAILABLE";
    }
    private Map<String,Object> responseObject() {
        McpSchema.JSONRPCResponse received=response.get();
        if(received==null || received.error()!=null) throw new McpOperationException("MCP_PROTOCOL_ERROR");
        return object(received.result());
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        if(!(value instanceof Map<?,?> map) || map.keySet().stream().anyMatch(key->!(key instanceof String)))
            throw new McpOperationException("MCP_PROTOCOL_ERROR");
        return (Map<String,Object>)value;
    }
    @Override public void close() {closed=true;client.close();}
}
