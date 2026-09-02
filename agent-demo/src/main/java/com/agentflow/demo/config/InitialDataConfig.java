package com.agentflow.demo.config;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.demo.tool.AgentToolEntity;
import com.agentflow.demo.tool.AgentToolRepository;
import com.agentflow.web.auth.SysUser;
import com.agentflow.web.auth.SysUserRepository;
import com.agentflow.web.support.Ids;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;

@Configuration
public class InitialDataConfig {

    @Bean
    CommandLineRunner seedAdminUser(
            SysUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${agentflow.initial-admin.username:}") String adminUsername,
            @Value("${agentflow.initial-admin.password:}") String adminPassword) {
        return args -> {
            boolean usernameConfigured = adminUsername != null && !adminUsername.isBlank();
            boolean passwordConfigured = adminPassword != null && !adminPassword.isBlank();
            if (!usernameConfigured && !passwordConfigured) {
                return;
            }
            if (!usernameConfigured || !passwordConfigured) {
                throw new IllegalStateException(
                        "agentflow.initial-admin.username and agentflow.initial-admin.password must be configured together");
            }
            if (userRepository.findByUsername(adminUsername).isEmpty()) {
                userRepository.save(new SysUser(Ids.newId(), adminUsername, passwordEncoder.encode(adminPassword),
                        "AgentFlow Admin", true, Instant.now()));
            }
        };
    }

    @Bean
    CommandLineRunner seedToolCatalog(AgentToolRepository repository, List<AgentTool> tools) {
        return args -> {
            for (AgentTool tool : tools) {
                if (repository.findByName(tool.name()).isEmpty()) {
                    repository.save(new AgentToolEntity(Ids.newId(), tool.name(), tool.description(), "{}",
                            true, tool.riskLevel().name(), Instant.now()));
                }
            }
        };
    }
}
