package com.agentflow.mcp;

import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.ToolSchema;
import com.agentflow.core.tool.ValueType;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;

/** Strict mapping; unsupported constraints disable a tool instead of being discarded. */
public final class McpSchemaMapper {
    private static final Set<String> ROOT_KEYS=Set.of("type","properties","required","additionalProperties","title","description","$comment","$schema");
    private static final Set<String> META=Set.of("type","title","description","$comment");
    private static final long SAFE_INTEGER=9007199254740991L;
    private final JsonMapper json=JsonMapper.builder().build();

    public ToolSchema map(Map<String,Object> schema) {
        if (schema==null || json.writeValueAsBytes(schema).length>16*1024 || !ROOT_KEYS.containsAll(schema.keySet())
                || !"object".equals(schema.get("type")) || !Boolean.FALSE.equals(schema.get("additionalProperties"))) fail();
        if (schema.containsKey("$schema") && !"https://json-schema.org/draft/2020-12/schema".equals(schema.get("$schema"))) fail();
        metadata(schema);
        Object rawProperties=schema.getOrDefault("properties",Map.of());
        if (!(rawProperties instanceof Map<?,?>)) fail();
        Map<?,?> properties=(Map<?,?>)rawProperties;
        if (properties.size()>32) fail();
        Object rawRequired=schema.getOrDefault("required",List.of());
        if (!(rawRequired instanceof List<?>)) fail();
        Set<String> required=new HashSet<>();
        for (Object value:(List<?>)rawRequired) {
            if (!(value instanceof String name) || !required.add(name) || !properties.containsKey(name)) fail();
        }
        Map<String,ParameterSpec> mapped=new LinkedHashMap<>();
        for(var entry:properties.entrySet()) {
            if (!(entry.getKey() instanceof String name) || name.isBlank() || !name.equals(name.strip()) || name.length()>64
                    || !(entry.getValue() instanceof Map<?,?>)) fail();
            String name=(String)entry.getKey();
            Map<?,?> property=(Map<?,?>)entry.getValue();
            metadata(property);
            ValueType type=switch(String.valueOf(property.get("type"))) {
                case "string" -> ValueType.STRING; case "integer" -> ValueType.INTEGER;
                case "number" -> ValueType.NUMBER; case "boolean" -> ValueType.BOOLEAN;
                default -> throw unsupported();
            };
            Set<String> allowed=new HashSet<>(META);
            if(type==ValueType.STRING) allowed.add("enum");
            if(type==ValueType.INTEGER) {allowed.add("minimum");allowed.add("maximum");}
            if(!allowed.containsAll(property.keySet())) fail();
            if ((property.containsKey("minimum") && property.get("minimum")==null)
                    || (property.containsKey("maximum") && property.get("maximum")==null)) fail();
            Set<String> values=new LinkedHashSet<>();
            if(property.containsKey("enum")) {
                if(!(property.get("enum") instanceof List<?> options) || options.isEmpty() || options.size()>32) fail();
                for(Object value:(List<?>)property.get("enum")) {
                    if(!(value instanceof String)) fail();
                    values.add((String)value);
                }
            }
            mapped.put(name,new ParameterSpec(type,required.contains(name),false,
                    (String)property.getOrDefault("description",null),null,null,
                    integer(property.get("minimum")),integer(property.get("maximum")),null,null,values,null));
        }
        return new ToolSchema(mapped,required,false);
    }
    public String internalName(String serverId,String remoteName) {
        String name="mcp."+serverId+"."+remoteName;
        if(serverId==null || !serverId.matches("[a-z][a-z0-9-]{0,31}") || remoteName==null
                || !remoteName.matches("[A-Za-z0-9_-]{1,64}") || name.length()>100) fail();
        return name;
    }
    private static Long integer(Object value) {
        if(value==null) return null;
        if(!(value instanceof Number)) throw unsupported();
        try {
            long number=new BigDecimal(value.toString()).longValueExact();
            if(number < -SAFE_INTEGER || number > SAFE_INTEGER) throw unsupported();
            return number;
        } catch(ArithmeticException | NumberFormatException ex) { throw unsupported(); }
    }
    private static void metadata(Map<?,?> schema) {
        for(String key:List.of("description","title","$comment"))
            if(schema.containsKey(key) && !(schema.get(key) instanceof String)) fail();
    }
    private static void fail() {throw unsupported();}
    private static IllegalArgumentException unsupported() {return new IllegalArgumentException("SCHEMA_UNSUPPORTED");}
}
