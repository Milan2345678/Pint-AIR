package com.printair.document;

import com.printair.security.OperatorPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/operator/documents")
public class ConversionRetryController {
    private final DocumentRepository documents;
    private final ConversionJobRepository jobs;

    public ConversionRetryController(DocumentRepository documents, ConversionJobRepository jobs) {
        this.documents = documents;
        this.jobs = jobs;
    }

    @PostMapping("/{id}/conversion/retry")
    void retry(@PathVariable UUID id, @AuthenticationPrincipal OperatorPrincipal operator) {
        var document = documents.findByIdAndShopIdAndStatusNotAndExpiresAtAfter(
                        id, operator.shopId(), DocumentStatus.DELETED.name(), Instant.now())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!document.getStatus().equals(DocumentStatus.CONVERSION_FAILED.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        var job = jobs.findByDocumentId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        job.setState("PENDING");
        job.setLastError(null);
        document.setStatus(DocumentStatus.PENDING_CONVERSION);
    }
}
