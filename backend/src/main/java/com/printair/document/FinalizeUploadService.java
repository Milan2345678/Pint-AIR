package com.printair.document;

import com.printair.storage.ObjectStorage;
import com.printair.shop.ShopRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class FinalizeUploadService {
    private final UploadIntentRepository intents;
    private final DocumentRepository documents;
    private final ConversionJobRepository jobs;
    private final ShopRepository shops;
    private final ObjectStorage storage;
    private final long retentionHours;

    public FinalizeUploadService(UploadIntentRepository intents, DocumentRepository documents,
                                 ConversionJobRepository jobs, ShopRepository shops, ObjectStorage storage,
                                 org.springframework.core.env.Environment environment) {
        this.intents = intents;
        this.documents = documents;
        this.jobs = jobs;
        this.shops = shops;
        this.storage = storage;
        this.retentionHours = environment.getProperty("printair.retention-hours", Long.class, 48L);
    }

    @Transactional
    public boolean finalizeUpload(UUID id) {
        var existing = intents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var shop = shops.findLockedBySlug(existing.getShop().getSlug())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var intent = intents.findLockedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!intent.getShop().getId().equals(shop.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (intent.getState().equals(UploadIntent.State.FINALIZED.name())) return true;
        if (!intent.getState().equals(UploadIntent.State.PENDING.name())
                || intent.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE);
        }
        String uploadKey = intent.getObjectKey();
        String extension = extensionFor(intent.getContentType());
        String documentKey = "shops/" + intent.getShop().getId() + "/documents/"
                + intent.getId() + "/source" + extension;
        if (!storage.exists(documentKey)) {
            var uploaded = storage.inspect(uploadKey);
            if (!matchesIntent(uploaded.contentLength(), uploaded.contentType(), intent)) {
                reject(uploadKey, documentKey, intent);
                return false;
            }
            storage.copy(uploadKey, documentKey);
        }

        var storedObject = storage.inspect(documentKey);
        if (!matchesIntent(storedObject.contentLength(), storedObject.contentType(), intent)) {
            reject(uploadKey, documentKey, intent);
            return false;
        }

        String checksum;
        try (var input = storage.open(documentKey)) {
            checksum = DocumentValidation.validateAndHash(input, intent.getContentType());
        } catch (DocumentValidation.InvalidDocumentException exception) {
            reject(uploadKey, documentKey, intent);
            return false;
        } catch (IOException exception) {
            throw new IllegalStateException("Stored document validation could not be completed", exception);
        }
        deleteAndVerify(uploadKey);
        intent.setObjectKey(documentKey);
        var document = documents.save(new PrintDocument(intent, storedObject.contentLength(), checksum,
                Instant.now().plus(Duration.ofHours(retentionHours))));
        if (intent.getContentType().equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
            jobs.save(new ConversionJob(document));
        }
        intent.setState(UploadIntent.State.FINALIZED);
        return true;
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "image/jpeg" -> ".jpg";
            default -> throw new IllegalArgumentException("Unsupported content type");
        };
    }

    private void deleteAndVerify(String objectKey) {
        storage.delete(objectKey);
        if (storage.exists(objectKey)) {
            throw new IllegalStateException("Rejected upload deletion could not be verified");
        }
    }

    private boolean matchesIntent(long size, String contentType, UploadIntent intent) {
        return size == intent.getDeclaredSize()
                && intent.getContentType().equals(contentType);
    }

    private void reject(String uploadKey, String documentKey, UploadIntent intent) {
        if (storage.exists(documentKey)) deleteAndVerify(documentKey);
        deleteAndVerify(uploadKey);
        intent.setState(UploadIntent.State.FAILED);
    }
}
