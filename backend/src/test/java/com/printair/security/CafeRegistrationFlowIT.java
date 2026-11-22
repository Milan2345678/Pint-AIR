package com.printair.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "management.health.mail.enabled=false")
class CafeRegistrationFlowIT {
    private static final String PASSWORD = "a-long-local-password";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.mock.mockito.MockBean JavaMailSender mailSender;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("printair.storage.backend", () -> "local");
        registry.add("printair.storage.root-dir", () -> System.getProperty("java.io.tmpdir") + "/printair-registration-test");
        registry.add("printair.cors.allowed-origins", () -> "http://localhost");
        registry.add("printair.public-base-url", () -> "http://localhost");
        registry.add("spring.mail.host", () -> "mailpit");
        registry.add("spring.mail.port", () -> "1025");
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("printair.mail-from", () -> "noreply@printair.local");
        registry.add("printair.dashboard-base-url", () -> "http://localhost:5173");
    }

    @Test
    void registrationResendVerificationAndLoginFlow() throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@example.test";
        register(email);

        UUID operatorId = jdbc.queryForObject(
                "SELECT id FROM shop_operators WHERE email = ?", UUID.class, email);
        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM shop_operators WHERE id = ?", Boolean.class, operatorId)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shop_operator_email_verification_tokens WHERE operator_id = ?",
                Integer.class, operatorId)).isEqualTo(1);

        ArgumentCaptor<SimpleMailMessage> messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messages.capture());
        String firstToken = extractToken(messages.getValue());
        assertThat(jdbc.queryForObject(
                "SELECT token_hash FROM shop_operator_email_verification_tokens WHERE operator_id = ?",
                String.class, operatorId)).isEqualTo(hash(firstToken));

        mockMvc.perform(post("/api/auth/login")
                        .param("email", email).param("password", PASSWORD).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\"}")
                        .with(csrf()))
                .andExpect(status().isNoContent());
        ArgumentCaptor<SimpleMailMessage> resentMessages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, org.mockito.Mockito.times(2)).send(resentMessages.capture());
        String resentToken = extractToken(resentMessages.getAllValues().get(1));
        assertThat(resentToken).isNotEqualTo(firstToken);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json").content("{\"token\":\"" + firstToken + "\"}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> consumeToken = () -> mockMvc.perform(post("/api/auth/verify-email")
                            .contentType("application/json")
                            .content("{\"token\":\"" + resentToken + "\"}")
                            .with(csrf()))
                    .andReturn().getResponse().getStatus();
            var results = executor.invokeAll(List.of(consumeToken, consumeToken));
            assertThat(results.stream().map(result -> {
                try {
                    return result.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            })).containsExactlyInAnyOrder(204, 400);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM shop_operators WHERE id = ?", Boolean.class, operatorId)).isTrue();
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json").content("{\"token\":\"" + resentToken + "\"}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/login")
                        .param("email", email).param("password", "incorrect-password").with(csrf()))
                .andExpect(status().isUnauthorized());
        var login = mockMvc.perform(post("/api/auth/login")
                        .param("email", email).param("password", PASSWORD).with(csrf()))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(login.getRequest().getSession(false)).isNotNull();
        mockMvc.perform(get("/api/auth/me").session(
                        (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false)))
                .andExpect(status().isOk());
    }

    @Test
    void failedEmailDeliveryKeepsPendingAccountAndAllowsResend() throws Exception {
        String email = "failed-" + UUID.randomUUID() + "@example.test";
        int operatorsBefore = jdbc.queryForObject("SELECT count(*) FROM shop_operators", Integer.class);
        int tokensBefore = jdbc.queryForObject(
                "SELECT count(*) FROM shop_operator_email_verification_tokens", Integer.class);
        doThrow(new MailSendException("local mail service unavailable"))
                .doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("{\"shopName\":\"Test Shop\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}")
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_operators", Integer.class))
                .isEqualTo(operatorsBefore + 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shop_operator_email_verification_tokens", Integer.class))
                .isEqualTo(tokensBefore + 1);

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\"}")
                        .with(csrf()))
                .andExpect(status().isNoContent());
        ArgumentCaptor<SimpleMailMessage> messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, org.mockito.Mockito.times(2)).send(messages.capture());
        String deliveredToken = extractToken(messages.getAllValues().get(1));
        UUID operatorId = jdbc.queryForObject(
                "SELECT id FROM shop_operators WHERE email = ?", UUID.class, email);
        assertThat(jdbc.queryForObject(
                "SELECT token_hash FROM shop_operator_email_verification_tokens WHERE operator_id = ?",
                String.class, operatorId)).isEqualTo(hash(deliveredToken));
    }

    @Test
    void duplicatePendingRegistrationReturnsSuccessWithoutCreatingAnotherAccount() throws Exception {
        String email = "duplicate-" + UUID.randomUUID() + "@example.test";
        register(email);
        register(email);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shop_operators WHERE email = ?", Integer.class, email)).isEqualTo(1);
        UUID operatorId = jdbc.queryForObject(
                "SELECT id FROM shop_operators WHERE email = ?", UUID.class, email);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shop_operator_email_verification_tokens WHERE operator_id = ?",
                Integer.class, operatorId)).isEqualTo(1);
        verify(mailSender, org.mockito.Mockito.times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void concurrentResendsDoNotDeliverATokenAfterAnotherResendInvalidatesIt() throws Exception {
        String email = "resend-race-" + UUID.randomUUID() + "@example.test";
        register(email, "127.0.0.10");
        org.mockito.Mockito.clearInvocations(mailSender);

        var firstSendStarted = new CountDownLatch(1);
        var releaseFirstSend = new CountDownLatch(1);
        var sendCount = new AtomicInteger();
        var deliveredTokens = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        org.mockito.Mockito.doAnswer(invocation -> {
            SimpleMailMessage message = invocation.getArgument(0);
            String token = extractToken(message);
            if (sendCount.getAndIncrement() == 0) {
                firstSendStarted.countDown();
                if (!releaseFirstSend.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to resume the test email");
                }
            }
            UUID operatorId = jdbc.queryForObject(
                    "SELECT id FROM shop_operators WHERE email = ?", UUID.class, email);
            assertThat(jdbc.queryForObject(
                    "SELECT token_hash FROM shop_operator_email_verification_tokens WHERE operator_id = ?",
                    String.class, operatorId)).isEqualTo(hash(token));
            deliveredTokens.add(token);
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));

        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstResend = executor.submit(() -> resend(email, "127.0.0.10"));
            assertThat(firstSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var secondStarted = new CountDownLatch(1);
            var secondResend = executor.submit(() -> {
                secondStarted.countDown();
                return resend(email, "127.0.0.10");
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> secondResend.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirstSend.countDown();
            assertThat(firstResend.get(10, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(secondResend.get(10, TimeUnit.SECONDS)).isEqualTo(204);
        } finally {
            releaseFirstSend.countDown();
            executor.shutdownNow();
        }

        assertThat(sendCount).hasValue(2);
        assertThat(deliveredTokens).hasSize(2);
        UUID operatorId = jdbc.queryForObject(
                "SELECT id FROM shop_operators WHERE email = ?", UUID.class, email);
        assertThat(jdbc.queryForObject(
                "SELECT token_hash FROM shop_operator_email_verification_tokens WHERE operator_id = ?",
                String.class, operatorId)).isEqualTo(hash(deliveredTokens.get(1)));
    }

    @Test
    void expiredTokenCannotActivateAnAccount() throws Exception {
        String email = "expired-" + UUID.randomUUID() + "@example.test";
        register(email);
        ArgumentCaptor<SimpleMailMessage> messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messages.capture());
        String token = extractToken(messages.getValue());
        UUID operatorId = jdbc.queryForObject(
                "SELECT id FROM shop_operators WHERE email = ?", UUID.class, email);
        jdbc.update("UPDATE shop_operator_email_verification_tokens SET expires_at = ? WHERE operator_id = ?",
                Timestamp.from(java.time.Instant.now().minusSeconds(1)), operatorId);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json").content("{\"token\":\"" + token + "\"}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM shop_operators WHERE id = ?", Boolean.class, operatorId)).isFalse();
    }

    private void register(String email) throws Exception {
        register(email, "127.0.0.1");
    }

    private void register(String email, String remoteAddress) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("{\"shopName\":\"Test Shop\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}")
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        })
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    private int resend(String email) throws Exception {
        return resend(email, "127.0.0.1");
    }

    private int resend(String email, String remoteAddress) throws Exception {
        return mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\"}")
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        })
                        .with(csrf()))
                .andReturn().getResponse().getStatus();
    }

    private static String extractToken(SimpleMailMessage message) {
        Matcher matcher = Pattern.compile("#token=([A-Za-z0-9_-]+)").matcher(message.getText());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
    }
}
