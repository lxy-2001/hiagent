package example.hiagent.plain;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlainConsumerTest {
    @Test void runsOnlyPublicCoreWithoutSpring() {
        var result = new PlainConsumer().run(request -> new FinalAnswerDecision("fixture", "plain-ok", TokenUsage.empty()), "hello");
        assertEquals("plain-ok", result.finalAnswer());
        assertFalse(result.steps().isEmpty());
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.context.ApplicationContext"));
        assertTrue(com.agentflow.core.AgentRuntime.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith(".jar"));
    }
}
