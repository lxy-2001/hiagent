package com.agentflow.demo.config;

import com.agentflow.demo.tool.AgentToolRepository;
import com.agentflow.web.auth.SysUser;
import com.agentflow.web.auth.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InitialDataConfigTest {

    @Test
    void doesNotCreateAdminWhenNoInitialCredentialsAreConfigured() {
        runner().run(context -> {
            CommandLineRunner seed = context.getBean("seedAdminUser", CommandLineRunner.class);
            seed.run();

            verify(context.getBean(SysUserRepository.class), never()).findByUsername(any());
            verify(context.getBean(SysUserRepository.class), never()).save(any(SysUser.class));
        });
    }

    @Test
    void rejectsPartiallyConfiguredInitialCredentials() {
        runner().withPropertyValues("agentflow.initial-admin.username=test-admin")
                .run(context -> {
                    CommandLineRunner seed = context.getBean("seedAdminUser", CommandLineRunner.class);

                    assertThatThrownBy(seed::run)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("agentflow.initial-admin");
                });
    }

    @Test
    void createsOnlyExplicitlyConfiguredInitialAdmin() {
        runner().withPropertyValues(
                        "agentflow.initial-admin.username=test-admin",
                        "agentflow.initial-admin.password=test-password")
                .run(context -> {
                    SysUserRepository repository = context.getBean(SysUserRepository.class);
                    PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
                    when(encoder.encode("test-password")).thenReturn("encoded-password");

                    context.getBean("seedAdminUser", CommandLineRunner.class).run();

                    verify(repository).save(argThat(user ->
                            user.getUsername().equals("test-admin")
                                    && user.getPasswordHash().equals("encoded-password")));
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(InitialDataConfig.class)
                .withBean(SysUserRepository.class, () -> mock(SysUserRepository.class))
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
                .withBean(AgentToolRepository.class, () -> mock(AgentToolRepository.class));
    }
}
