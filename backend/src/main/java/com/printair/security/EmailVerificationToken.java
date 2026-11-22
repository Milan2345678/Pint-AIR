package com.printair.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shop_operator_email_verification_tokens")
public class EmailVerificationToken {
    @Id
    @Column(name = "operator_id", nullable = false)
    private UUID operatorId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected EmailVerificationToken() {
    }

    public EmailVerificationToken(UUID operatorId, String tokenHash, Instant expiresAt) {
        this.operatorId = operatorId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getOperatorId() { return operatorId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }

    public void rotate(String tokenHash, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }
}
