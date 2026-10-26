package com.printair.document;

import java.time.Instant;
import java.util.UUID;

public record DocumentView(UUID id, String customerName, String filename, String contentType, long sizeBytes,
                           Instant uploadedAt, Instant expiresAt, String status) {
    static DocumentView from(PrintDocument document) {
        return new DocumentView(document.getId(), document.getSubmission().getCustomerName(),
                document.getOriginalFilename(), document.getContentType(), document.getSizeBytes(),
                document.getUploadedAt(), document.getExpiresAt(), document.getStatus());
    }
}
