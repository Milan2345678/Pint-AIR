package com.printair.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mail.MailProperties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailConfigurationValidatorTest {
    @Test
    void acceptsMailpitConfigurationWithoutSmtpAuthentication() {
        MailProperties mail = new MailProperties();
        mail.setHost("mailpit");
        mail.setPort(1025);
        mail.getProperties().put("mail.smtp.auth", "false");

        new MailConfigurationValidator(mail, "noreply@printair.local", "http://localhost:5173")
                .afterSingletonsInstantiated();
    }

    @Test
    void rejectsMissingProductionMailConfigurationWithAnActionableError() {
        MailProperties mail = new MailProperties();
        mail.setPort(587);

        assertThatThrownBy(() -> new MailConfigurationValidator(mail, "", "")
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_HOST");
    }

    @Test
    void requiresCredentialsWhenSmtpAuthenticationIsEnabled() {
        MailProperties mail = new MailProperties();
        mail.setHost("smtp.example.test");
        mail.setPort(587);
        mail.getProperties().put("mail.smtp.auth", "true");

        assertThatThrownBy(() -> new MailConfigurationValidator(
                mail, "noreply@example.test", "https://dashboard.example.test")
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_USERNAME and MAIL_PASSWORD");
    }

    @Test
    void rejectsInvalidSmtpAuthenticationConfiguration() {
        MailProperties mail = new MailProperties();
        mail.setHost("smtp.example.test");
        mail.setPort(587);
        mail.getProperties().put("mail.smtp.auth", "sometimes");

        assertThatThrownBy(() -> new MailConfigurationValidator(
                mail, "noreply@example.test", "https://dashboard.example.test")
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_SMTP_AUTH must be true or false");
    }
}
