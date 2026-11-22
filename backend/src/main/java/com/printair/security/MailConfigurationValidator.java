package com.printair.security;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MailConfigurationValidator implements SmartInitializingSingleton {
    private final MailProperties mail;
    private final String mailFrom;
    private final String dashboardBaseUrl;

    public MailConfigurationValidator(
            MailProperties mail,
            @Value("${printair.mail-from:}") String mailFrom,
            @Value("${printair.dashboard-base-url:}") String dashboardBaseUrl) {
        this.mail = mail;
        this.mailFrom = mailFrom;
        this.dashboardBaseUrl = dashboardBaseUrl;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (isBlank(mail.getHost())) {
            throw new IllegalStateException("MAIL_HOST must be configured for the web application");
        }
        if (mail.getPort() < 1 || mail.getPort() > 65_535) {
            throw new IllegalStateException("MAIL_PORT must be between 1 and 65535");
        }
        if (isBlank(mailFrom)) {
            throw new IllegalStateException("MAIL_FROM must be configured for the web application");
        }
        if (isBlank(dashboardBaseUrl)) {
            throw new IllegalStateException("DASHBOARD_BASE_URL must be configured for the web application");
        }

        String smtpAuthValue = mail.getProperties().getOrDefault("mail.smtp.auth", "true");
        if (!"true".equalsIgnoreCase(smtpAuthValue) && !"false".equalsIgnoreCase(smtpAuthValue)) {
            throw new IllegalStateException("MAIL_SMTP_AUTH must be true or false");
        }
        boolean smtpAuth = Boolean.parseBoolean(smtpAuthValue);
        if (smtpAuth && (isBlank(mail.getUsername()) || isBlank(mail.getPassword()))) {
            throw new IllegalStateException(
                    "MAIL_USERNAME and MAIL_PASSWORD are required when SMTP authentication is enabled");
        }

        validateDashboardBaseUrl();
    }

    private void validateDashboardBaseUrl() {
        try {
            URI uri = URI.create(dashboardBaseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalStateException(
                        "DASHBOARD_BASE_URL must be an HTTP(S) origin without a path, query, or fragment");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "DASHBOARD_BASE_URL must be an HTTP(S) origin without a path, query, or fragment");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
