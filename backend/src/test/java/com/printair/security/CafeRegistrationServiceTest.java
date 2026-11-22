package com.printair.security;

import com.printair.shop.Shop;
import com.printair.shop.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CafeRegistrationServiceTest {
    private final ShopRepository shops = mock(ShopRepository.class);
    private final ShopOperatorRepository operators = mock(ShopOperatorRepository.class);
    private final EmailVerificationTokenRepository tokens = mock(EmailVerificationTokenRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final RateLimitService rateLimit = mock(RateLimitService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final Map<UUID, ShopOperator> savedOperators = new HashMap<>();
    private final Map<UUID, EmailVerificationToken> savedTokens = new HashMap<>();
    private CafeRegistrationService service;

    @BeforeEach
    void setUp() {
        savedOperators.clear();
        savedTokens.clear();
        service = new CafeRegistrationService(shops, operators, tokens, passwordEncoder, mailSender, rateLimit,
                transactionManager, "smtp.example.test", "noreply@example.test", "http://localhost:5173/");
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(rateLimit.allow(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(shops.save(any(Shop.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(operators.save(any(ShopOperator.class))).thenAnswer(invocation -> {
            ShopOperator operator = invocation.getArgument(0);
            savedOperators.put(operator.getId(), operator);
            return operator;
        });
        when(operators.findById(any(UUID.class))).thenAnswer(invocation ->
                Optional.ofNullable(savedOperators.get(invocation.getArgument(0))));
        when(operators.findLockedById(any(UUID.class))).thenAnswer(invocation ->
                Optional.ofNullable(savedOperators.get(invocation.getArgument(0))));
        when(tokens.save(any(EmailVerificationToken.class))).thenAnswer(invocation -> {
            EmailVerificationToken token = invocation.getArgument(0);
            savedTokens.put(token.getOperatorId(), token);
            return token;
        });
        when(tokens.findById(any(UUID.class))).thenAnswer(invocation ->
                Optional.ofNullable(savedTokens.get(invocation.getArgument(0))));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
    }

    @Test
    void createsAPendingCafeAndEmailsAHashedOneTimeVerificationToken() throws Exception {
        when(operators.findByEmailIgnoreCase("owner@example.test")).thenReturn(Optional.empty());

        service.register("  Paper Corner  ", " OWNER@example.test ", "a-long-password");

        var operatorCaptor = org.mockito.ArgumentCaptor.forClass(ShopOperator.class);
        verify(operators).save(operatorCaptor.capture());
        ShopOperator operator = operatorCaptor.getValue();
        assertThat(operator.getEmail()).isEqualTo("owner@example.test");
        assertThat(operator.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(operator.isEnabled()).isFalse();
        assertThat(operator.getShop().getName()).isEqualTo("Paper Corner");
        assertThat(operator.getShop().getSlug()).matches("paper-corner-[0-9a-f]{12}");
        verify(passwordEncoder).encode("a-long-password");

        var messageCaptor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage message = messageCaptor.getValue();
        assertThat(message.getTo()).containsExactly("owner@example.test");
        assertThat(message.getText()).contains("http://localhost:5173/verify-email#token=");

        Matcher tokenMatcher = Pattern.compile("#token=([A-Za-z0-9_-]+)").matcher(message.getText());
        assertThat(tokenMatcher.find()).isTrue();
        String rawToken = tokenMatcher.group(1);
        var tokenCaptor = org.mockito.ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokens).save(tokenCaptor.capture());
        EmailVerificationToken storedToken = tokenCaptor.getValue();
        assertThat(storedToken.getOperatorId()).isEqualTo(operator.getId());
        assertThat(storedToken.getTokenHash()).isEqualTo(hash(rawToken));
        assertThat(storedToken.getExpiresAt()).isAfter(Instant.now());
        var order = inOrder(transactionManager, mailSender);
        order.verify(transactionManager).commit(any(TransactionStatus.class));
        order.verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void verifiesAnUnexpiredTokenAndEnablesTheOperator() throws Exception {
        String rawToken = "one-time-token";
        String tokenHash = hash(rawToken);
        ShopOperator operator = new ShopOperator(new Shop("Cafe", "cafe-identity"), "owner@example.test",
                "encoded-password", false);
        EmailVerificationToken token = new EmailVerificationToken(operator.getId(), tokenHash,
                Instant.now().plusSeconds(60));
        when(tokens.findByTokenHash(tokenHash)).thenReturn(Optional.of(token));
        when(tokens.deleteUnexpiredByTokenHash(eq(tokenHash), any())).thenReturn(1);
        when(operators.findById(operator.getId())).thenReturn(Optional.of(operator));
        when(operators.findLockedById(operator.getId())).thenReturn(Optional.of(operator));

        service.verify(rawToken);

        assertThat(operator.isEnabled()).isTrue();
        verify(tokens).deleteUnexpiredByTokenHash(eq(tokenHash), any());
    }

    @Test
    void rejectsExpiredOrUnknownVerificationTokens() throws Exception {
        String tokenHash = hash("expired-token");
        when(tokens.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify("expired-token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid or expired");
        verify(operators, never()).findById(any(UUID.class));
    }

    @Test
    void rejectsTokenWhenAtomicConsumptionFindsItExpiredOrAlreadyConsumed() throws Exception {
        String rawToken = "consumed-token";
        String tokenHash = hash(rawToken);
        var operator = new ShopOperator(new Shop("Cafe", "cafe-identity"), "owner@example.test",
                "encoded-password", false);
        var token = new EmailVerificationToken(operator.getId(), tokenHash, Instant.now().minusSeconds(1));
        when(tokens.findByTokenHash(tokenHash)).thenReturn(Optional.of(token));
        when(tokens.deleteUnexpiredByTokenHash(eq(tokenHash), any())).thenReturn(0);

        assertThatThrownBy(() -> service.verify(rawToken))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid or expired");
        verify(operators, never()).findById(any(UUID.class));
    }

    @Test
    void mailFailureDoesNotRollBackThePendingAccountOrVerificationToken() {
        when(operators.findByEmailIgnoreCase("owner@example.test")).thenReturn(Optional.empty());
        doThrow(new MailSendException("sensitive transport details"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> service.register("Paper Corner", "owner@example.test", "a-long-password"))
                .doesNotThrowAnyException();

        verify(operators).save(any(ShopOperator.class));
        verify(tokens).save(any(EmailVerificationToken.class));
        verify(transactionManager, times(2)).commit(any(TransactionStatus.class));
    }

    @Test
    void duplicatePendingRegistrationResendsVerificationWithoutCreatingAnotherShopOrOperator() {
        var existing = new ShopOperator(new Shop("Existing Cafe", "existing-cafe"),
                "owner@example.test", "encoded-password", false);
        when(operators.findByEmailIgnoreCase("owner@example.test")).thenReturn(Optional.of(existing));
        when(operators.findLockedById(existing.getId())).thenReturn(Optional.of(existing));

        service.register("Ignored Name", " OWNER@example.test ", "another-password");

        verify(shops, never()).save(any(Shop.class));
        verify(operators, never()).save(any(ShopOperator.class));
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void duplicateVerifiedRegistrationDoesNotCreateAnotherAccountOrSendMail() {
        var existing = new ShopOperator(new Shop("Existing Cafe", "existing-cafe"),
                "owner@example.test", "encoded-password", true);
        when(operators.findByEmailIgnoreCase("owner@example.test")).thenReturn(Optional.of(existing));

        service.register("Ignored Name", " OWNER@example.test ", "another-password");

        verify(shops, never()).save(any(Shop.class));
        verify(operators, never()).save(any(ShopOperator.class));
        verifyNoInteractions(mailSender);
    }

    private static String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
    }
}
