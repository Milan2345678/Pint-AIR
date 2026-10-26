package com.printair.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversionJobRepository extends JpaRepository<ConversionJob, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversionJob> findFirstByStateOrderByCreatedAtAsc(String state);
    Optional<ConversionJob> findByDocumentId(UUID documentId);

    List<ConversionJob> findByStateAndLockedAtBefore(String state, Instant before);
}
