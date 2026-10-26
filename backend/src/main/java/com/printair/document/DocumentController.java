package com.printair.document;

import com.printair.security.OperatorPrincipal;
import com.printair.storage.ObjectStorage;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/operator/documents")
public class DocumentController {
    private final DocumentRepository documents;
    private final ObjectStorage storage;
    private final DocumentLifecycleService lifecycle;

    public DocumentController(DocumentRepository documents, ObjectStorage storage, DocumentLifecycleService lifecycle) {
        this.documents = documents;
        this.storage = storage;
        this.lifecycle = lifecycle;
    }

    @GetMapping
    List<DocumentView> list(@AuthenticationPrincipal OperatorPrincipal operator) {
        return documents.findByShopIdAndStatusNotInAndExpiresAtAfterOrderByUploadedAtDesc(
                        operator.shopId(), List.of(DocumentStatus.DELETED.name(), DocumentStatus.DELETING.name()),
                        Instant.now()).stream()
                .map(DocumentView::from).toList();
    }

    @GetMapping("/{id}/preview")
    void preview(@PathVariable UUID id, @AuthenticationPrincipal OperatorPrincipal operator,
                 HttpServletResponse response) throws IOException {
        streamPdf(id, operator.shopId(), response);
    }

    @GetMapping("/{id}/print")
    void print(@PathVariable UUID id, @AuthenticationPrincipal OperatorPrincipal operator,
               HttpServletResponse response) throws IOException {
        streamPdf(id, operator.shopId(), response);
    }

    private void streamPdf(UUID id, UUID shopId, HttpServletResponse response) throws IOException {
        var document = documents.findByIdAndShopIdAndStatusNotAndExpiresAtAfter(id, shopId,
                        DocumentStatus.DELETED.name(), Instant.now())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (document.getStatus().equals(DocumentStatus.DELETING.name())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!document.getStatus().equals(DocumentStatus.READY.name()) || document.getPdfObjectKey() == null) {
            throw new ResponseStatusException(document.getStatus().equals(DocumentStatus.CONVERSION_FAILED.name())
                    ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CONFLICT);
        }
        response.setContentType(MediaType.APPLICATION_PDF_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                org.springframework.http.ContentDisposition.inline()
                        .filename(document.getOriginalFilename(), java.nio.charset.StandardCharsets.UTF_8).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        try (var input = storage.open(document.getPdfObjectKey())) {
            input.transferTo(response.getOutputStream());
        }
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable UUID id, @AuthenticationPrincipal OperatorPrincipal operator) {
        lifecycle.deleteDocument(operator.shopId(), id);
    }
}
