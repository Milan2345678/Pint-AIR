package com.printair.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UploadRequest(
        @NotNull UUID submissionId,
        @NotBlank @Size(max = 100) String customerName,
        @NotEmpty List<@Valid FileRequest> files) {
    public record FileRequest(
            @NotNull UUID uploadId,
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 100) String contentType,
            @Positive long sizeBytes) {
    }
}
