package com.printair.security;

import com.printair.shop.Shop;
import com.printair.shop.ShopRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class CafeRegistrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CafeRegistrationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long TOKEN_LIFETIME_SECONDS = 86_400;

    private final ShopRepository shops;
    private final ShopOperatorRepository operators;
    private final EmailVerificationTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final RateLimitService rateLimit;
    private final TransactionTemplate transactions;
    private final String mailHost;
    private final String mailFrom;
    private final String dashboardBaseUrl;

    public CafeRegistrationService(
            ShopRepository shops,
            ShopOperatorRepository operators,
            EmailVerificationTokenRepository tokens,
            PasswordEncoder passwordEncoder,
            JavaMailSender mailSender,
            RateLimitService rateLimit,
            PlatformTransactionManager transactionManager,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${printair.mail-from:}") String mailFrom,
            @Value("${printair.dashboard-base-url:}") String dashboardBaseUrl) {
        this.shops = shops;
        this.operators = operators;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.rateLimit = rateLimit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.mailHost = mailHost;
        this.mailFrom = mailFrom;
        this.dashboardBaseUrl = dashboardBaseUrl.replaceAll("/+$", "");
    }

    public void register(String shopName, String email, String password) {
        var verification = transactions.execute(status -> registerInTransaction(shopName, email, password));
        sendVerification(verification);
    }

    private VerificationEmail registerInTransaction(String shopName, String email, String password) {
        requireMailConfiguration();
        String normalizedEmail = normalizeEmail(email);
        limitVerificationEmail(normalizedEmail);
        var existing = operators.findByEmailIgnoreCase(normalizedEmail);
        if (existing.isPresent()) {
            if (existing.get().isEnabled()) return null;
            return operators.findLockedById(existing.get().getId())
                    .filter(operator -> !operator.isEnabled())
                    .map(this::createVerification)
                    .orElse(null);
        }

        String normalizedShopName = shopName.trim();
        String slug = shopSlug(normalizedShopName);
        Shop shop = shops.save(new Shop(normalizedShopName, slug));
        ShopOperator operator = operators.save(new ShopOperator(
                shop, normalizedEmail, passwordEncoder.encode(password), false));
        return createVerification(operator);
    }

    public void resendVerification(String email) {
        var verification = transactions.execute(status -> {
            requireMailConfiguration();
            String normalizedEmail = normalizeEmail(email);
            limitVerificationEmail(normalizedEmail);
            var candidate = operators.findByEmailIgnoreCase(normalizedEmail)
                    .filter(operator -> !operator.isEnabled())
                    .orElse(null);
            if (candidate == null) return null;
            return operators.findLockedById(candidate.getId())
                    .filter(operator -> !operator.isEnabled())
                    .map(this::createVerification)
                    .orElse(null);
        });
        sendVerification(verification);
    }

    @Transactional
    public void verify(String token) {
        String tokenHash = hashToken(token);
        EmailVerificationToken verification = tokens.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Verification link is invalid or expired"));
        ShopOperator operator = operators.findLockedById(verification.getOperatorId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Verification link is invalid or expired"));
        if (tokens.deleteUnexpiredByTokenHash(tokenHash, Instant.now()) != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Verification link is invalid or expired");
        }
        operator.enable();
    }

    private VerificationEmail createVerification(ShopOperator operator) {
        String rawToken = newToken();
        Instant expiresAt = Instant.now().plusSeconds(TOKEN_LIFETIME_SECONDS);
        tokens.findById(operator.getId()).ifPresentOrElse(
                existing -> existing.rotate(hashToken(rawToken), expiresAt),
                () -> tokens.save(new EmailVerificationToken(operator.getId(), hashToken(rawToken), expiresAt)));

        return new VerificationEmail(operator.getId(), operator.getEmail(), operator.getShop().getName(), rawToken);
    }

    private void sendVerification(VerificationEmail verification) {
        if (verification == null) return;
        transactions.executeWithoutResult(status -> {
            var operator = operators.findLockedById(verification.operatorId()).orElse(null);
            if (operator == null || operator.isEnabled()) return;
            boolean isCurrent = tokens.findById(operator.getId())
                    .map(token -> token.getTokenHash().equals(hashToken(verification.token())))
                    .orElse(false);
            if (!isCurrent) return;
            deliverVerification(verification);
        });
    }

    private void deliverVerification(VerificationEmail verification) {
        String verificationUrl = dashboardBaseUrl + "/verify-email#token=" + verification.token();
        var message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(verification.email());
        message.setSubject("Verify your Print AIR cafe account");
        message.setText("Verify the email address for " + verification.shopName()
                + " by opening this link within 24 hours:\n\n" + verificationUrl
                + "\n\nIf you did not request this account, you can ignore this message.");
        try {
            mailSender.send(message);
        } catch (MailException failure) {
            LOGGER.warn("Verification email delivery failed; resend may be required ({})",
                    failure.getClass().getSimpleName());
        }
    }

    private void limitVerificationEmail(String normalizedEmail) {
        String emailKey = HexFormat.of().formatHex(sha256(normalizedEmail.getBytes(StandardCharsets.UTF_8)));
        if (!rateLimit.allow("verification-email:" + emailKey, 3, 60_000)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Verification email request limit reached");
        }
    }

    private void requireMailConfiguration() {
        if (mailHost.isBlank() || mailFrom.isBlank() || dashboardBaseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Email verification is not configured");
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String shopSlug(String shopName) {
        String base = Normalizer.normalize(shopName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isBlank()) base = "shop";
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return base.substring(0, Math.min(base.length(), 60)) + "-" + suffix;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashToken(String token) {
        return HexFormat.of().formatHex(sha256(token.getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record VerificationEmail(UUID operatorId, String email, String shopName, String token) {
    }
}
