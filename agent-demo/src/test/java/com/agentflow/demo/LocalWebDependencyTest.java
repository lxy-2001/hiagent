package com.agentflow.demo;

import com.agentflow.web.auth.SysUser;
import com.agentflow.web.support.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class LocalWebDependencyTest {
    @TempDir Path root;
    @Test void authenticatesRefreshesRevokesAcrossRestartAndLimitsRequestsWithoutRedis() throws Exception {
        String revokedAccess;
        String revokedRefresh;
        try(var app=ContextHistoryApiTest.start(root)) {
            assertThat(app.getBean(WebRequestStateStore.class)).isInstanceOf(LocalWebRequestStateStore.class);
            new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->
                app.getBean(EntityManager.class).persist(new SysUser("owner","local-user",app.getBean(PasswordEncoder.class).encode("password"),"Local",true,Instant.now())));
            var login=ContextHistoryApiTest.request(app,"POST","/api/auth/login",null,"{\"username\":\"local-user\",\"password\":\"password\"}");
            assertThat(login.statusCode()).isEqualTo(200);
            var first=app.getBean(ObjectMapper.class).readTree(login.body());
            var refresh=ContextHistoryApiTest.request(app,"POST","/api/auth/refresh",null,"{\"refreshToken\":\""+first.get("refreshToken").asText()+"\"}");
            assertThat(refresh.statusCode()).isEqualTo(200);
            assertThat(ContextHistoryApiTest.request(app,"POST","/api/auth/refresh",null,"{\"refreshToken\":\""+first.get("refreshToken").asText()+"\"}").statusCode()).isEqualTo(401);
            var current=app.getBean(ObjectMapper.class).readTree(refresh.body());
            revokedAccess=current.get("accessToken").asText(); revokedRefresh=current.get("refreshToken").asText();
            assertThat(ContextHistoryApiTest.request(app,"GET","/api/me",revokedAccess,null).statusCode()).isEqualTo(200);
            assertThat(ContextHistoryApiTest.request(app,"POST","/api/auth/logout",revokedAccess,"{\"refreshToken\":\""+revokedRefresh+"\"}").statusCode()).isEqualTo(200);
            assertThat(ContextHistoryApiTest.request(app,"GET","/api/agent/sessions/missing/context",revokedAccess,null).statusCode()).isEqualTo(401);
            // Rate behavior uses the real filter; prefill its actual local counter to avoid 121 HTTP calls.
            var store=app.getBean(WebRequestStateStore.class);
            String key="rate:127.0.0.1:"+Instant.now().getEpochSecond()/60;
            for(int i=0;i<121;i++) store.incrementRate(key,Instant.now().plusSeconds(120));
            assertThat(ContextHistoryApiTest.request(app,"GET","/api/agent/sessions/missing/context",null,null).statusCode()).isEqualTo(429);
        }
        try(var app=ContextHistoryApiTest.start(root)) {
            var denied=ContextHistoryApiTest.request(app,"GET","/api/agent/sessions/missing/context",revokedAccess,null);
            assertThat(denied.statusCode()).isEqualTo(401);
            assertThat(denied.body()).contains("UNAUTHORIZED");
            assertThat(ContextHistoryApiTest.request(app,"POST","/api/auth/refresh",null,"{\"refreshToken\":\""+revokedRefresh+"\"}").statusCode()).isEqualTo(401);
            app.getBean(JdbcTemplate.class).execute("drop table auth_access_revocation");
            assertThat(ContextHistoryApiTest.request(app,"GET","/api/agent/sessions/missing/context",revokedAccess,null).statusCode()).isEqualTo(503);
        }
    }
}
