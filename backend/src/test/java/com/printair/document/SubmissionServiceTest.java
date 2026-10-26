package com.printair.document;

import com.printair.security.RateLimitService;
import com.printair.shop.Shop;
import com.printair.shop.ShopRepository;
import com.printair.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubmissionServiceTest {
    private final ShopRepository shops = mock(ShopRepository.class);
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final UploadIntentRepository intents = mock(UploadIntentRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final Shop shop = mock(Shop.class);
    private SubmissionService service;

    @BeforeEach
    void setUp() {
        var environment = new MockEnvironment();
        when(shop.getId()).thenReturn(UUID.randomUUID());
        when(shops.findLockedBySlug("cafe")).thenReturn(Optional.of(shop));
        when(submissions.save(any(Submission.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(intents.save(any(UploadIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.presignPut(any(), any(), anyLong(), any())).thenReturn("http://minio.test/upload");
        service = new SubmissionService(shops, submissions, intents, documents, storage,
                new RateLimitService(), environment);
    }

    @Test
    void rejectsDeclaredOversizedFilesBeforeCreatingSubmission() {
        var request = new UploadRequest("Student", List.of(
                new UploadRequest.FileRequest("large.pdf", "application/pdf", 26L * 1024 * 1024)));

        assertThatThrownBy(() -> service.create("cafe", request.customerName(), request.files(), "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(submissions, never()).save(any());
        verify(storage, never()).presignPut(any(), any(), anyLong(), any());
    }

    @Test
    void rejectsContentTypeThatDoesNotMatchTheFileExtension() {
        var request = new UploadRequest("Student", List.of(
                new UploadRequest.FileRequest("report.pdf", "text/plain", 100)));

        assertThatThrownBy(() -> service.create("cafe", request.customerName(), request.files(), "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(storage, never()).presignPut(any(), any(), anyLong(), any());
    }

    @Test
    void createsOnlyShortLivedUploadGrantsForSupportedFiles() {
        var request = new UploadRequest(" Student ", List.of(
                new UploadRequest.FileRequest("report.PDF", "application/pdf", 100)));

        var response = service.create("cafe", request.customerName(), request.files(), "127.0.0.1");

        assertThat(response.uploads()).hasSize(1);
        assertThat(response.uploads().getFirst().url()).startsWith("http://minio.test/");
        verify(intents).save(any(UploadIntent.class));
    }
}
