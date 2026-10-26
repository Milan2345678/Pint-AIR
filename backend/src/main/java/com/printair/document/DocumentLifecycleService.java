package com.printair.document;

import com.printair.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentLifecycleService {
    private static final Logger logger = LoggerFactory.getLogger(DocumentLifecycleService.class);
    private final DocumentRepository documents;
    private final UploadIntentRepository intents;
    private final ObjectStorage storage;
    private final ConversionJobRepository jobs;
    private final TransactionTemplate transactions;
    private UUID reconciliationCursor;

    public DocumentLifecycleService(DocumentRepository documents, UploadIntentRepository intents,
                                    ConversionJobRepository jobs, ObjectStorage storage,
                                    PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.intents = intents;
        this.jobs = jobs;
        this.storage = storage;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void deleteDocument(UUID shopId, UUID documentId) {
        var keys = transactions.execute(status -> {
            var document = documents.findByIdAndShopId(documentId, shopId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            return markDeleting(document);
        });
        if (keys != null && !deleteAndVerify(keys, documentId, shopId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Document deletion could not be verified");
        }
    }

    @Scheduled(fixedDelayString = "${RETENTION_SCAN_MS:60000}")
    public void processExpiredDocuments() {
        var expiredIds = transactions.execute(status -> {
            var expired = documents.findByStatusNotInAndExpiresAtBefore(
                    List.of(DocumentStatus.DELETED.name(), DocumentStatus.DELETING.name()), Instant.now());
            expired.forEach(document -> document.setStatus(DocumentStatus.DELETING));
            return expired.stream().map(PrintDocument::getId).toList();
        });
        if (expiredIds != null) expiredIds.forEach(this::deleteExpiredDocument);

        var deleting = documents.findByStatusOrderByExpiresAtAsc(DocumentStatus.DELETING.name());
        deleting.forEach(document -> deleteExpiredDocument(document.getId()));
        cleanupExpiredUploads();
    }

    @Scheduled(fixedDelayString = "${RECONCILIATION_SCAN_MS:3600000}")
    public void reconcileStorageAndMetadata() {
        reconcileMissingDocumentObjects();
        reconcileOrphanedObjects();
    }

    private void deleteExpiredDocument(UUID documentId) {
        var details = transactions.execute(status -> documents.findById(documentId)
                .map(document -> new DeleteDetails(document.getShop().getId(), markDeleting(document)))
                .orElse(null));
        if (details != null) deleteAndVerify(details.keys(), documentId, details.shopId());
    }

    private List<String> markDeleting(PrintDocument document) {
        document.setStatus(DocumentStatus.DELETING);
        jobs.findByDocumentId(document.getId()).ifPresent(job -> job.setState("CANCELLED"));
        var keys = new java.util.LinkedHashSet<String>();
        keys.add(document.getSourceObjectKey());
        if (document.getPdfObjectKey() != null) keys.add(document.getPdfObjectKey());
        return List.copyOf(keys);
    }

    private void reconcileMissingDocumentObjects() {
        var active = List.of(DocumentStatus.READY.name(), DocumentStatus.PENDING_CONVERSION.name(),
                DocumentStatus.CONVERSION_FAILED.name());
        var batch = reconciliationCursor == null
                ? documents.findTop100ByStatusInAndExpiresAtAfterOrderByIdAsc(active, Instant.now())
                : documents.findTop100ByIdGreaterThanAndStatusInAndExpiresAtAfterOrderByIdAsc(
                        reconciliationCursor, active, Instant.now());
        if (batch.isEmpty()) {
            reconciliationCursor = null;
            return;
        }
        for (var document : batch) {
            reconciliationCursor = document.getId();
            if (!storage.exists(document.getSourceObjectKey())) {
                deleteExpiredDocument(document.getId());
            } else if (document.getStatus().equals(DocumentStatus.READY.name())
                    && !storage.exists(document.getPdfObjectKey())) {
                transactions.executeWithoutResult(status -> documents.findById(document.getId()).ifPresent(current -> {
                    current.setStatus(DocumentStatus.CONVERSION_FAILED);
                    current.setPdfObjectKey(null);
                    jobs.findByDocumentId(current.getId()).ifPresent(job -> {
                        job.setState("FAILED");
                        job.setLastError("PREVIEW_OBJECT_MISSING");
                    });
                }));
            }
        }
    }

    private void reconcileOrphanedObjects() {
        String token = null;
        var cutoff = Instant.now().minusSeconds(3600);
        do {
            var page = storage.list(token);
            var keys = page.objects().stream().map(ObjectStorage.StoredObject::key).toList();
            if (!keys.isEmpty()) {
                var referenced = new java.util.HashSet<>(intents.findReferencedObjectKeys(keys));
                for (Object[] row : documents.findReferencedObjectKeys(keys)) {
                    referenced.add((String) row[0]);
                    if (row[1] != null) referenced.add((String) row[1]);
                }
                for (var object : page.objects()) {
                    if (!referenced.contains(object.key()) && object.lastModified().isBefore(cutoff)) {
                        try {
                            storage.delete(object.key());
                            if (storage.exists(object.key())) {
                                throw new IllegalStateException("Orphan object deletion was not confirmed");
                            }
                            logger.info("Orphaned storage object deleted");
                        } catch (RuntimeException exception) {
                            logger.error("Orphaned storage cleanup failed error={}",
                                    exception.getClass().getSimpleName());
                        }
                    }
                }
            }
            token = page.nextToken();
        } while (token != null);
    }

    private boolean deleteAndVerify(List<String> keys, UUID documentId, UUID shopId) {
        try {
            for (String key : keys) {
                storage.delete(key);
                if (storage.exists(key)) throw new IllegalStateException("Object storage deletion was not confirmed");
            }
            transactions.executeWithoutResult(status -> documents.findById(documentId).ifPresent(document -> {
                document.setStatus(DocumentStatus.DELETED);
                document.setDeletedAt(Instant.now());
            }));
            logger.info("Document deleted id={} shop={}", documentId, shopId);
            return true;
        } catch (RuntimeException exception) {
            logger.error("Document deletion failed id={} error={}", documentId,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private void cleanupExpiredUploads() {
        var expired = intents.findByStateAndExpiresAtBefore(UploadIntent.State.PENDING.name(), Instant.now());
        for (var intent : expired) {
            try {
                storage.delete(intent.getObjectKey());
                if (storage.exists(intent.getObjectKey())) {
                    throw new IllegalStateException("Upload object deletion was not confirmed");
                }
                transactions.executeWithoutResult(status -> intents.findLockedById(intent.getId())
                        .ifPresent(current -> current.setState(UploadIntent.State.FAILED)));
            } catch (RuntimeException exception) {
                logger.error("Abandoned upload cleanup failed id={} error={}", intent.getId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    private record DeleteDetails(UUID shopId, List<String> keys) {
    }
}
