package com.agentflow.demo;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes=AgentFlowDemoApplication.class,properties={
        "spring.datasource.url=jdbc:h2:mem:timeout;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false","agentflow.knowledge.bootstrap.enabled=false",
        "agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32"})
class RunDatabaseTimeoutTest {
    @Autowired javax.sql.DataSource dataSource;
    @Test void demoBindsFinitePoolWaits() {
        HikariDataSource hikari=(HikariDataSource)dataSource;
        assertThat(hikari.getConnectionTimeout()).isEqualTo(2000); assertThat(hikari.getValidationTimeout()).isEqualTo(1000);
        assertThat(hikari.getDataSourceProperties()).containsEntry("connectTimeout","2000").containsEntry("socketTimeout","3000");
    }
}
