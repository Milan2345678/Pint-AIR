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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SubmissionService {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String JPEG = "image/jpeg";
    private static final Duration INTENT_LIFETIME = Duration.ofMinutes(30);
    private final ShopRepository shops;
    private final SubmissionRepository submissions;
    private final UploadIntentRepository intents;
    private final DocumentRepository documents;
    private final ObjectStorage storage;
    private final RateLimitService rateLimit;
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
        this.quotaBytes = environment.getProperty("printair.shop-quota-bytes", Long.class, 1024L * 1024 * 1024);
        this.presignSeconds = environment.getProperty("printair.storage.presign-seconds", Integer.class, 300);
    }

    @Transactional
    public UploadResponse create(String slug, UploadRequest request, String clientAddress) {
        if (!rateLimit.allow("submission-ip:" + clientAddress, 20, 60_000)
                || !rateLimit.allow("submission-shop:" + slug, 60, 60_000)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Submission limit reached");
        }
        var shop = shops.findLockedBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String customerName = request.customerName().strip();
        long requestedBytes = 0;
        Set<UUID> uploadIds = new HashSet<>();
        Map<UUID, String> contentTypes = new HashMap<>();
        for (var file : request.files()) {
            if (!uploadIds.add(file.uploadId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate upload identifier");
            }
            String contentType = validateFile(file);
            contentTypes.put(file.uploadId(), contentType);
            try {
                requestedBytes = Math.addExact(requestedBytes, file.sizeBytes());
            } catch (ArithmeticException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid upload size");
            }
        }

        var existing = submissions.findById(request.submissionId());
        if (existing.isPresent()) {
            var submission = existing.get();
            if (!submission.getShop().getId().equals(shop.getId())
                    || !submission.getCustomerName().equals(customerName)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Submission identifier is already in use");
            }
            var existingIntents = intents.findBySubmission_Id(submission.getId());
            if (existingIntents.size() != request.files().size()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Submission contents do not match");
            }
            var intentsById = new HashMap<UUID, UploadIntent>();
            existingIntents.forEach(intent -> intentsById.put(intent.getId(), intent));
            var grants = new ArrayList<UploadResponse.UploadGrant>();
            for (var file : request.files()) {
                var intent = intentsById.get(file.uploadId());
                if (intent == null || !matchesRequest(intent, submission, shop, file,
                        contentTypes.get(file.uploadId()))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Submission contents do not match");
                }
                grants.add(issueGrant(intent));
            }
            return new UploadResponse(grants);
        }

        long reserved = documents.activeBytes(shop.getId(), Instant.now(), DocumentStatus.DELETED.name())
                + intents.sumPendingBytes(shop.getId(), Instant.now());
        if (requestedBytes > quotaBytes || reserved > quotaBytes - requestedBytes) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Shop storage quota reached");
        }
        var submission = submissions.save(new Submission(request.submissionId(), shop, customerName));
        var grants = new ArrayList<UploadResponse.UploadGrant>();
        for (var file : request.files()) {
            String contentType = contentTypes.get(file.uploadId());
            String objectKey = stagingKey(shop.getId(), contentType);
            var intent = intents.save(new UploadIntent(file.uploadId(), submission, shop, file.fileName(),
                    contentType, file.sizeBytes(), objectKey, Instant.now().plus(INTENT_LIFETIME)));
            grants.add(issueGrant(intent));
        }
        return new UploadResponse(grants);
    }

    @Transactional
    public UploadResponse.UploadGrant retry(UUID uploadId) {
        var intent = intents.findLockedById(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return issueGrant(intent);
    }

    private String validateFile(UploadRequest.FileRequest file) {
        String contentType = canonicalContentType(file.fileName());
        if (file.sizeBytes() <= 0
                || file.fileName().contains("/") || file.fileName().contains("\\")
                || file.fileName().equals(".") || file.fileName().equals("..")
                || file.fileName().chars().anyMatch(Character::isISOControl)
                || !file.contentType().equals(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or invalid file");
        }
        return contentType;
    }

    private String canonicalContentType(String fileName) {
        var lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".pdf")) return "application/pdf";
        if (lowerName.endsWith(".docx")) return DOCX;
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) return JPEG;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF, DOCX, JPG, and JPEG are supported");
    }

    private UploadResponse.UploadGrant issueGrant(UploadIntent intent) {
        if (intent.getState().equals(UploadIntent.State.FINALIZED.name())) {
            return new UploadResponse.UploadGrant(intent.getId(), null, intent.getContentType(),
                    intent.getExpiresAt(), UploadIntent.State.FINALIZED.name());
        }
        if (!intent.getState().equals(UploadIntent.State.PENDING.name())
                || !intent.getExpiresAt().isAfter(Instant.now())) {
            if (storage.exists(intent.getObjectKey())) {
                storage.delete(intent.getObjectKey());
                if (storage.exists(intent.getObjectKey())) {
                    throw new IllegalStateException("Expired upload cleanup could not be verified");
                }
            }
            intent.setObjectKey(stagingKey(intent.getShop().getId(), intent.getContentType()));
            intent.setState(UploadIntent.State.PENDING);
        }
        intent.setExpiresAt(Instant.now().plus(INTENT_LIFETIME));
        String url = storage.presignPut(intent.getId(), intent.getObjectKey(), intent.getContentType(),
                intent.getDeclaredSize(), Duration.ofSeconds(presignSeconds));
        return new UploadResponse.UploadGrant(intent.getId(), url, intent.getContentType(),
                Instant.now().plusSeconds(presignSeconds), UploadIntent.State.PENDING.name());
    }

    private String stagingKey(UUID shopId, String contentType) {
        String extension = switch (contentType) {
            case "application/pdf" -> ".pdf";
            case DOCX -> ".docx";
            case JPEG -> ".jpg";
            default -> throw new IllegalArgumentException("Unsupported content type");
        };
        return "shops/" + shopId + "/uploads/" + UUID.randomUUID() + extension;
    }

    private boolean matchesRequest(UploadIntent intent, Submission submission, com.printair.shop.Shop shop,
                                   UploadRequest.FileRequest file, String contentType) {
        return intent.getSubmission().getId().equals(submission.getId())
                && intent.getShop().getId().equals(shop.getId())
                && intent.getOriginalFilename().equals(file.fileName())
                && intent.getContentType().equals(contentType)
                && intent.getDeclaredSize() == file.sizeBytes();
    }
}
