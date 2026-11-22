package com.printair.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from EmailVerificationToken token where token.tokenHash = :tokenHash and token.expiresAt > :now")
    int deleteUnexpiredByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") Instant now);
}
