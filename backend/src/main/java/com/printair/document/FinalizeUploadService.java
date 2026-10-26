package com.printair.document;

import com.printair.storage.ObjectStorage;
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
    private final ObjectStorage storage;
    private final long retentionHours;
    private final long maxFileSize;

    public FinalizeUploadService(UploadIntentRepository intents, DocumentRepository documents,
                                 ConversionJobRepository jobs, ObjectStorage storage,
                                 org.springframework.core.env.Environment environment) {
        this.intents = intents;
        this.documents = documents;
        this.jobs = jobs;
        this.storage = storage;
        this.retentionHours = environment.getProperty("printair.retention-hours", Long.class, 48L);
        this.maxFileSize = environment.getProperty("printair.max-file-size-bytes", Long.class, 25L * 1024 * 1024);
    }

    @Transactional
    public boolean finalizeUpload(UUID id) {
        var intent = intents.findLockedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (intent.getState().equals(UploadIntent.State.FINALIZED.name())) return true;
        if (!intent.getState().equals(UploadIntent.State.PENDING.name())
                || intent.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE);
        }
        String uploadKey = intent.getObjectKey();
        String extension = intent.getContentType().equals("application/pdf") ? ".pdf" : ".docx";
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
        boolean pdf = intent.getContentType().equals("application/pdf");
        var document = documents.save(new PrintDocument(intent, storedObject.contentLength(), checksum,
                Instant.now().plus(Duration.ofHours(retentionHours)), pdf));
        if (!pdf) jobs.save(new ConversionJob(document));
        intent.setState(UploadIntent.State.FINALIZED);
        return true;
    }

    private void deleteAndVerify(String objectKey) {
        storage.delete(objectKey);
        if (storage.exists(objectKey)) {
            throw new IllegalStateException("Rejected upload deletion could not be verified");
        }
    }

    private boolean matchesIntent(long size, String contentType, UploadIntent intent) {
        return size == intent.getDeclaredSize() && size <= maxFileSize
                && intent.getContentType().equals(contentType);
    }

    private void reject(String uploadKey, String documentKey, UploadIntent intent) {
        if (storage.exists(documentKey)) deleteAndVerify(documentKey);
        deleteAndVerify(uploadKey);
        intent.setState(UploadIntent.State.FAILED);
    }
}
