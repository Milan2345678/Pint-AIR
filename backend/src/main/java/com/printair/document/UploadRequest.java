package com.printair.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UploadRequest(
        @NotBlank @Size(max = 100) String customerName,
        @NotEmpty @Size(max = 5) List<@Valid FileRequest> files) {
    public record FileRequest(
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 100) String contentType,
            @Positive long sizeBytes) {
    }
}
