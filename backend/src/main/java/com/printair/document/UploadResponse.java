package com.printair.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UploadResponse(List<UploadGrant> uploads) {
    public record UploadGrant(UUID uploadId, String url, String contentType, Instant expiresAt) {
    }
}
