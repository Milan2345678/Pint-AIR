package com.printair.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

public interface UploadIntentRepository extends JpaRepository<UploadIntent, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select intent from UploadIntent intent where intent.id = :id")
    Optional<UploadIntent> findLockedById(@Param("id") UUID id);

    @Query("select coalesce(sum(intent.declaredSize), 0) from UploadIntent intent where intent.shop.id = :shopId and intent.state = 'PENDING' and intent.expiresAt > :now")
    long sumPendingBytes(@Param("shopId") UUID shopId, @Param("now") Instant now);

    List<UploadIntent> findByStateAndExpiresAtBefore(String state, Instant before);

    @Query("select intent.objectKey from UploadIntent intent where intent.objectKey in :keys")
    List<String> findReferencedObjectKeys(@Param("keys") Collection<String> keys);
}
