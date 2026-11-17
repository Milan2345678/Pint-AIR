package com.printair.document;

import com.printair.storage.ObjectStorage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/public")
public class PublicSubmissionController {
    private final SubmissionService submissions;
    private final FinalizeUploadService finalizer;
    private final UploadIntentRepository intents;
    private final ObjectStorage storage;

    public PublicSubmissionController(SubmissionService submissions, FinalizeUploadService finalizer,
                                    UploadIntentRepository intents, ObjectStorage storage) {
        this.submissions = submissions;
        this.finalizer = finalizer;
        this.intents = intents;
        this.storage = storage;
    }

    @PostMapping("/shops/{slug}/submissions")
    UploadResponse create(@PathVariable String slug, @Valid @RequestBody UploadRequest request,
                          HttpServletRequest servletRequest) {
        return submissions.create(slug, request, servletRequest.getRemoteAddr());
    }

    @PostMapping("/uploads/{uploadId}/retry")
    UploadResponse.UploadGrant retry(@PathVariable UUID uploadId) {
        return submissions.retry(uploadId);
    }

    @PutMapping("/uploads/{uploadId}/content")
    ResponseEntity<Void> uploadContent(@PathVariable UUID uploadId,
                                      @RequestParam String key,
                                      @RequestHeader(value = "Content-Type", required = false) String contentType,
                                      HttpServletRequest request) throws IOException {
        var intent = intents.findById(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!intent.getObjectKey().equals(key) || intent.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload grant expired or invalid");
        }
        storage.put(key, request.getInputStream(),
                contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType,
                request.getContentLengthLong() > 0 ? request.getContentLengthLong() : 0L);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/uploads/{uploadId}/finalize")
    Map<String, String> finalizeUpload(@PathVariable UUID uploadId) {
        if (!finalizer.finalizeUpload(uploadId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The uploaded file did not pass validation; submit it again.");
        }
        return Map.of("result", "accepted");
    }
}
