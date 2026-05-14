package com.agentflow.demo.config;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.demo.tool.AgentToolEntity;
import com.agentflow.demo.tool.AgentToolRepository;
import com.agentflow.web.auth.SysUser;
import com.agentflow.web.auth.SysUserRepository;
import com.agentflow.web.support.Ids;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;

@Configuration
public class InitialDataConfig {

    @Bean
    CommandLineRunner seedAdminUser(SysUserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            if (userRepository.findByUsername("admin").isEmpty()) {
                userRepository.save(new SysUser(Ids.newId(), "admin", passwordEncoder.encode("agentflow123"),
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
