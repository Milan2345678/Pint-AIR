package com.printair.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import org.springframework.data.domain.Pageable;

public interface DocumentRepository extends JpaRepository<PrintDocument, UUID> {
    List<PrintDocument> findByShopIdAndStatusNotInAndExpiresAtAfterOrderByUploadedAtDesc(
            UUID shopId, java.util.Collection<String> statuses, Instant now);
    Optional<PrintDocument> findByIdAndShopIdAndStatusNotAndExpiresAtAfter(UUID id, UUID shopId,
                                                                           String status, Instant now);
    List<PrintDocument> findByStatusAndExpiresAtBefore(String status, Instant before);
    List<PrintDocument> findByStatusNotInAndExpiresAtBefore(java.util.Collection<String> statuses, Instant before);
    List<PrintDocument> findByStatusOrderByExpiresAtAsc(String status);
    Optional<PrintDocument> findByIdAndShopId(UUID id, UUID shopId);
    long sumSizeBytesByShopIdAndExpiresAtAfterAndStatusNot(UUID shopId, Instant now, String status);

    @Query("select coalesce(sum(document.sizeBytes), 0) from PrintDocument document where document.shop.id = :shopId and document.expiresAt > :now and document.status <> :deleted")
    long activeBytes(@Param("shopId") UUID shopId, @Param("now") Instant now,
                     @Param("deleted") String deleted);

    @Query("select document.sourceObjectKey, document.pdfObjectKey from PrintDocument document where document.sourceObjectKey in :keys or document.pdfObjectKey in :keys")
    List<Object[]> findReferencedObjectKeys(@Param("keys") Collection<String> keys);

    List<PrintDocument> findTop100ByStatusInAndExpiresAtAfterOrderByIdAsc(
            Collection<String> statuses, Instant now);

    List<PrintDocument> findTop100ByIdGreaterThanAndStatusInAndExpiresAtAfterOrderByIdAsc(
            UUID afterId, Collection<String> statuses, Instant now);
}
