package com.agentflow.mcp;

import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpSchemaMapperTest {
    private final McpSchemaMapper mapper=new McpSchemaMapper();
    static Map<String,Object> schema(Map<String,Object> property) {
        return Map.of("type","object","properties",Map.of("value",property),"required",List.of("value"),"additionalProperties",false);
    }
    @Test void preservesRequiredScalarTypesAndBounds() {
        var mapped=mapper.map(schema(Map.of("type","integer","minimum",2,"maximum",5)));
        assertFalse(mapped.validate(new ToolArguments(Map.of())).valid());
        assertFalse(mapped.validate(new ToolArguments(Map.of("value",1))).valid());
        assertTrue(mapped.validate(new ToolArguments(Map.of("value",2))).valid());
        assertFalse(mapped.validate(new ToolArguments(Map.of("value",6))).valid());
        assertFalse(mapped.validate(new ToolArguments(Map.of("value",2,"unknown",true))).valid());
        var strings=mapper.map(schema(Map.of("type","string","enum",List.of("one","two"))));
        assertTrue(strings.validate(new ToolArguments(Map.of("value","one"))).valid());
        assertFalse(strings.validate(new ToolArguments(Map.of("value","other"))).valid());
    }
    @Test void unsupportedConstraintsNeverDisappear() {
        for(var property:List.<Map<String,Object>>of(
                Map.of("type","string","pattern","x"), Map.of("type","string","minLength",1),
                Map.of("type","string","maxLength",4),Map.of("type",List.of("string","null")),
                Map.of("type","object"),Map.of("type","array"),Map.of("type","string","enum",List.of()),
                Map.of("type","string","enum",List.of(1)),Map.of("type","number","minimum",0),
                Map.of("type","integer","minimum",9007199254740992L),Map.of("type","integer","minimum",1.5),
                Map.of("type","integer","exclusiveMinimum",0),Map.of("type","string","default","injected"),
                Map.of("type","string","format","uri"),Map.of("type","boolean","nullable",true))) {
            assertThrows(IllegalArgumentException.class,()->mapper.map(schema(property)),property.toString());
        }
    }
    @Test void rootIsStrictAndBounded() {
        for(var root:List.<Map<String,Object>>of(Map.of("type","object"),
                Map.of("type","object","additionalProperties",true),Map.of("type","object","additionalProperties",false,"allOf",List.of()),
                Map.of("type","object","additionalProperties",false,"required",List.of("missing")),
                Map.of("type","object","additionalProperties",false,"$schema","unknown"),
                Map.of("type","object","additionalProperties",false,"description","x".repeat(17000))))
            assertThrows(IllegalArgumentException.class,()->mapper.map(root));
        assertDoesNotThrow(()->mapper.map(Map.of("type","object","additionalProperties",false)));
    }
    @Test void nameLimitsIncludeComposedInternalName() {
        assertEquals("mcp.demo.project_info",mapper.internalName("demo","project_info"));
        assertEquals(100,mapper.internalName("a".repeat(31),"x".repeat(64)).length());
        for(var pair:List.of(List.of("a".repeat(32),"x".repeat(64)),List.of("Demo","x"),List.of("demo","../x"),List.of("demo","x.y")))
            assertThrows(IllegalArgumentException.class,()->mapper.internalName(pair.get(0),pair.get(1)));
    }

    @Test void explicitNullNumericConstraintCannotBeSilentlyDropped() {
        var property=new HashMap<String,Object>();property.put("type","integer");property.put("minimum",null);
        assertThrows(IllegalArgumentException.class,()->mapper.map(schema(property)));
    }
}
