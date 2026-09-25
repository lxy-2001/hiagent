package com.agentflow.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Delegates all JSON work to the SDK mapper; records only a safe parse-failure flag. */
final class ProtocolJsonMapper implements McpJsonMapper {
    private final McpJsonMapper delegate;
    private final AtomicBoolean malformed;
    ProtocolJsonMapper(McpJsonMapper delegate,AtomicBoolean malformed) {
        this.delegate=delegate;this.malformed=malformed;
    }
    public <T> T readValue(String data,Class<T> type) throws IOException {
        try {return delegate.readValue(data,type);} catch(IOException | RuntimeException failure) {throw malformed();}
    }
    public <T> T readValue(byte[] data,Class<T> type) throws IOException {
        try {return delegate.readValue(data,type);} catch(IOException | RuntimeException failure) {throw malformed();}
    }
    public <T> T readValue(String data,TypeRef<T> type) throws IOException {
        try {return delegate.readValue(data,type);} catch(IOException | RuntimeException failure) {throw malformed();}
    }
    public <T> T readValue(byte[] data,TypeRef<T> type) throws IOException {
        try {return delegate.readValue(data,type);} catch(IOException | RuntimeException failure) {throw malformed();}
    }
    public <T> T convertValue(Object data,Class<T> type) {
        try {return delegate.convertValue(data,type);} catch(RuntimeException failure) {throw conversion();}
    }
    public <T> T convertValue(Object data,TypeRef<T> type) {
        try {return delegate.convertValue(data,type);} catch(RuntimeException failure) {throw conversion();}
    }
    public String writeValueAsString(Object value) throws IOException {return delegate.writeValueAsString(value);}
    public byte[] writeValueAsBytes(Object value) throws IOException {return delegate.writeValueAsBytes(value);}
    private IOException malformed() {malformed.set(true);return new IOException("MCP_PROTOCOL_ERROR");}
    private IllegalArgumentException conversion() {return new IllegalArgumentException("MCP typed conversion failed");}
}
