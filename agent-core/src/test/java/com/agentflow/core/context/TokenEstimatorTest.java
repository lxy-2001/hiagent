package com.agentflow.core.context;

import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolSchema;
import com.agentflow.core.tool.ValueType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {
    private final Utf8TokenEstimator estimator = new Utf8TokenEstimator();

    @Test
    void checkedArithmeticRejectsOverflowAndNegativeCounts() {
        assertEquals(Long.MAX_VALUE, Utf8TokenEstimator.checkedAdd(Long.MAX_VALUE - 1, 1));
        assertThrows(ArithmeticException.class, () -> Utf8TokenEstimator.checkedAdd(Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> Utf8TokenEstimator.checkedAdd(-1, 1));
    }

    @Test
    void countsEmptyAsciiChineseAndEmojiByHand() {
        assertEquals(0, estimator.estimateInput(List.of(), List.of()));
        assertEquals(37, estimator.estimateInput(List.of(ModelMessage.user("A")), List.of()));
        assertEquals(42, estimator.estimateInput(List.of(ModelMessage.user("中文")), List.of()));
        assertEquals(40, estimator.estimateInput(List.of(ModelMessage.user("😀")), List.of()));
        assertEquals("utf8-v1", estimator.version());
    }

    @Test
    void countsEscapedArgumentsAndNestedStructureByHand() {
        var call = new ToolCall("c", "t", new ToolArguments(Map.of("q", "\"\n")));
        // 32 + assistant(9) + name/id(2) + call name/id(2)
        // + object braces(2), quoted key(3), separators(2), quoted escaped value(6).
        assertEquals(58, estimator.estimateInput(List.of(ModelMessage.assistantToolCall(call)), List.of()));
        var nested = new ToolCall("c", "t", new ToolArguments(Map.of("q", List.of(true, 12))));
        assertEquals(61, estimator.estimateInput(List.of(ModelMessage.assistantToolCall(nested)), List.of()));
    }

    @Test
    void countsEverySchemaConstraintAndRequiredNames() {
        var plain = new ParameterSpec(ValueType.STRING, false, false);
        var constrained = new ParameterSpec(ValueType.STRING, true, true, "description", 1, 8,
                null, null, null, null, Set.of("x", "中文"), "x+");
        long base = estimateTool(plain, false, false);
        assertTrue(estimateTool(constrained, true, true) > base + 50);
        assertTrue(estimateTool(plain, true, false) > base);
        var min = new ParameterSpec(ValueType.INTEGER, false, false, "", null, null,
                Long.MIN_VALUE, Long.MAX_VALUE, null, null, Set.of(), null);
        assertTrue(estimateTool(min, false, false) > base + 30);
        var number = new ParameterSpec(ValueType.NUMBER, false, false, "", null, null,
                null, null, -1.5, 2.5, Set.of(), null);
        assertTrue(estimateTool(number, false, false) > base);
    }

    @Test
    void rejectsCountsAndTextBeforeExpensiveTraversal() {
        assertThrows(IllegalArgumentException.class, () -> estimator.estimateInput(
                Collections.nCopies(65, ModelMessage.user("x")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> estimator.estimateInput(
                List.of(ModelMessage.user("x".repeat(262145))), List.of()));
        var huge = new ToolDefinition("t", "x".repeat(65537), RiskLevel.LOW, new ToolSchema(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> estimator.estimateInput(List.of(), List.of(huge)));
        var tool = new ToolDefinition("t", "d", RiskLevel.LOW, new ToolSchema(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> estimator.estimateInput(List.of(), Collections.nCopies(33, tool)));
    }

    private long estimateTool(ParameterSpec spec, boolean required, boolean additional) {
        var schema = new ToolSchema(Map.of("q", spec), required ? Set.of("q") : Set.of(), additional);
        return estimator.estimateInput(List.of(), List.of(new ToolDefinition("t", "d", RiskLevel.LOW, schema)));
    }
}

