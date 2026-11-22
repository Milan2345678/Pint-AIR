package com.printair;

import com.printair.document.ConversionWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("worker")
class WorkerProfileContextIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ApplicationContext context;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("printair.storage.backend", () -> "local");
        registry.add("printair.storage.root-dir", () -> System.getProperty("java.io.tmpdir") + "/printair-worker-test-storage");
        registry.add("printair.cors.allowed-origins", () -> "http://localhost");
        registry.add("printair.public-base-url", () -> "http://localhost");
        registry.add("CONVERSION_POLL_MS", () -> "60000");
    }

    @Test
    void startsAsNonWebWorkerWithoutSecurityWebBeans() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
        assertThat(context.getBeansOfType(ConversionWorker.class)).hasSize(1);
        assertThat(context.getBeansOfType(PasswordEncoder.class)).hasSize(1);
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
        assertThat(context.getBeansOfType(HttpSecurity.class)).isEmpty();
    }
}
