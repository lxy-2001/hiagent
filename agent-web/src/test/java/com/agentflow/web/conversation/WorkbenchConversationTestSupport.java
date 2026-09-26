package com.agentflow.web.conversation;

import com.agentflow.web.agent.*;
import com.agentflow.web.run.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = ConversationQueryTest.App.class)
abstract class WorkbenchConversationTestSupport {
    @Autowired ConversationService service;
    @Autowired AgentSessionRepository sessions;
    @Autowired AgentTaskRepository tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired RunCoordinator coordinator;
    MockMvc mvc;
    String owner;

    @BeforeEach void prepareWorkbenchQueries() {
        reset(coordinator);
        owner = UUID.randomUUID().toString();
        var jwt = Jwt.withTokenValue("fixture").header("alg", "none").subject(owner).build();
        var resolver = new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
            public boolean supportsParameter(org.springframework.core.MethodParameter p) { return p.getParameterType() == Jwt.class; }
            public Object resolveArgument(org.springframework.core.MethodParameter p,
                    org.springframework.web.method.support.ModelAndViewContainer m,
                    org.springframework.web.context.request.NativeWebRequest r,
                    org.springframework.web.bind.support.WebDataBinderFactory b) { return jwt; }
        };
        mvc = MockMvcBuilders.standaloneSetup(new ConversationController(service))
                .setControllerAdvice(new RunApiExceptionHandler()).setCustomArgumentResolvers(resolver).build();
    }

    @AfterEach void resetWorkbenchCoordinator() { reset(coordinator); }

    String session() {
        String id = UUID.randomUUID().toString();
        sessions.saveAndFlush(new AgentSessionEntity(id, owner, "Conversation", Instant.now()));
        return id;
    }

    String turn(String session, long sequence, String status, String input) {
        String id = UUID.randomUUID().toString();
        tasks.saveAndFlush(new AgentTaskEntity(id, session, owner, input, status, Instant.now(), sequence));
        jdbc.update("update agent_session set last_turn_sequence=greatest(last_turn_sequence, ?) where id=?", sequence, session);
        return id;
    }
}
