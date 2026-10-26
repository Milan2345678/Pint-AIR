package com.printair.document;

import com.printair.security.RateLimitService;
import com.printair.shop.ShopRepository;
import com.printair.storage.ObjectStorage;
import jakarta.transaction.Transactional;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

@Service
public class SubmissionService {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final ShopRepository shops;
    private final SubmissionRepository submissions;
    private final UploadIntentRepository intents;
    private final DocumentRepository documents;
    private final ObjectStorage storage;
    private final RateLimitService rateLimit;
    private final long maxFileSize;
    private final int maxFiles;
    private final long quotaBytes;
    private final int presignSeconds;

    public SubmissionService(ShopRepository shops, SubmissionRepository submissions, UploadIntentRepository intents,
                             DocumentRepository documents, ObjectStorage storage, RateLimitService rateLimit,
                             Environment environment) {
        this.shops = shops;
        this.submissions = submissions;
        this.intents = intents;
        this.documents = documents;
        this.storage = storage;
        this.rateLimit = rateLimit;
        this.maxFileSize = environment.getProperty("printair.max-file-size-bytes", Long.class, 25L * 1024 * 1024);
        this.maxFiles = environment.getProperty("printair.max-files-per-submission", Integer.class, 5);
        this.quotaBytes = environment.getProperty("printair.shop-quota-bytes", Long.class, 1024L * 1024 * 1024);
        this.presignSeconds = environment.getProperty("printair.storage.presign-seconds", Integer.class, 300);
    }

    @Transactional
    public UploadResponse create(String slug, String customerName, java.util.List<UploadRequest.FileRequest> files,
                                 String clientAddress) {
        if (!rateLimit.allow("submission-ip:" + clientAddress, 20, 60_000)
                || !rateLimit.allow("submission-shop:" + slug, 60, 60_000)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Submission limit reached");
        }
        if (files.size() > maxFiles) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many files");
        var shop = shops.findLockedBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        long requestedBytes = 0;
        for (var file : files) {
            validateFile(file);
            try {
                requestedBytes = Math.addExact(requestedBytes, file.sizeBytes());
            } catch (ArithmeticException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid upload size");
            }
        }
        long reserved = documents.activeBytes(shop.getId(), Instant.now(), DocumentStatus.DELETED.name())
                + intents.sumPendingBytes(shop.getId(), Instant.now());
        if (requestedBytes > quotaBytes || reserved > quotaBytes - requestedBytes) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Shop storage quota reached");
        }
        var submission = submissions.save(new Submission(shop, customerName.strip()));
        var grants = new ArrayList<UploadResponse.UploadGrant>();
        for (var file : files) {
            String contentType = canonicalContentType(file.fileName());
            String extension = contentType.equals("application/pdf") ? ".pdf" : ".docx";
            String objectKey = "shops/" + shop.getId() + "/uploads/" + UUID.randomUUID() + extension;
            var intent = intents.save(new UploadIntent(submission, shop, file.fileName(),
                    contentType, file.sizeBytes(), objectKey, Instant.now().plus(Duration.ofMinutes(30))));
            String url = storage.presignPut(objectKey, contentType, file.sizeBytes(),
                    Duration.ofSeconds(presignSeconds));
            grants.add(new UploadResponse.UploadGrant(intent.getId(), url, contentType,
                    Instant.now().plusSeconds(presignSeconds)));
        }
        return new UploadResponse(grants);
    }

    private void validateFile(UploadRequest.FileRequest file) {
        if (file.sizeBytes() <= 0 || file.sizeBytes() > maxFileSize
                || file.fileName().contains("/") || file.fileName().contains("\\")
                || file.fileName().equals(".") || file.fileName().equals("..")
                || file.fileName().chars().anyMatch(Character::isISOControl)
                || !file.contentType().equals(canonicalContentType(file.fileName()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or invalid file");
        }
    }

    private String canonicalContentType(String fileName) {
        var lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".pdf")) return "application/pdf";
        if (lowerName.endsWith(".docx")) return DOCX;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF and DOCX are supported");
    }
}
