package com.printair.document;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/public")
public class PublicSubmissionController {
    private final SubmissionService submissions;
    private final FinalizeUploadService finalizer;

    public PublicSubmissionController(SubmissionService submissions, FinalizeUploadService finalizer) {
        this.submissions = submissions;
        this.finalizer = finalizer;
    }

    @PostMapping("/shops/{slug}/submissions")
    UploadResponse create(@PathVariable String slug, @Valid @RequestBody UploadRequest request,
                          HttpServletRequest servletRequest) {
        return submissions.create(slug, request.customerName(), request.files(), servletRequest.getRemoteAddr());
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
