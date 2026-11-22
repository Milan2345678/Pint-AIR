package com.printair.document;

import com.printair.security.RateLimitService;
import com.printair.shop.Shop;
import com.printair.shop.ShopRepository;
import com.printair.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
        when(shop.getSlug()).thenReturn("cafe");
        when(shops.findLockedBySlug("cafe")).thenReturn(Optional.of(shop));
        when(submissions.save(any(Submission.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(intents.save(any(UploadIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(intents.findBySubmission_Id(any())).thenReturn(List.of());
        when(storage.presignPut(any(), any(), any(), anyLong(), any())).thenReturn("http://minio.test/upload");
        service = new SubmissionService(shops, submissions, intents, documents, storage,
                new RateLimitService(), environment);
    }

    @Test
    void createsOneSubmissionForMixedFilesWithinTheShopQuota() {
        var submissionId = UUID.randomUUID();
        var files = List.of(
                file("large.pdf", "application/pdf", 26L * 1024 * 1024),
                file("assignment.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 200),
                file("photo.jpg", "image/jpeg", 300),
                file("certificate.jpeg", "image/jpeg", 400),
                file("another.pdf", "application/pdf", 500),
                file("extra.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        900L * 1024 * 1024));

        var response = service.create("cafe", new UploadRequest(submissionId, " Student ", files), "127.0.0.1");

        assertThat(response.uploads()).hasSize(6).allSatisfy(grant -> {
            assertThat(grant.url()).startsWith("http://minio.test/");
            assertThat(grant.status()).isEqualTo(UploadIntent.State.PENDING.name());
        });
        verify(submissions).save(argThat(submission -> submission.getId().equals(submissionId)
                && submission.getCustomerName().equals("Student")));
        verify(intents, times(6)).save(any(UploadIntent.class));
    }

    @Test
    void rejectsReservationsThatWouldExceedActiveAndPendingShopStorage() {
        var environment = new MockEnvironment().withProperty("printair.shop-quota-bytes", "100");
        when(documents.activeBytes(any(), any(), any())).thenReturn(40L);
        when(intents.sumPendingBytes(any(), any())).thenReturn(50L);
        service = new SubmissionService(shops, submissions, intents, documents, storage,
                new RateLimitService(), environment);
        var request = new UploadRequest(UUID.randomUUID(), "Student",
                List.of(file("report.pdf", "application/pdf", 11)));

        assertThatThrownBy(() -> service.create("cafe", request, "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(submissions, never()).save(any());
    }

    @Test
    void rejectsContentTypeThatDoesNotMatchTheFileExtension() {
        var request = new UploadRequest(UUID.randomUUID(), "Student",
                List.of(file("report.jpg", "application/pdf", 100)));

        assertThatThrownBy(() -> service.create("cafe", request, "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(storage, never()).presignPut(any(), any(), any(), anyLong(), any());
    }

    @Test
    void reusesExistingSubmissionAndUploadIntentsForAnIdempotentRetry() {
        var submissionId = UUID.randomUUID();
        var pdf = file("report.pdf", "application/pdf", 100);
        var jpeg = file("photo.jpeg", "image/jpeg", 200);
        var request = new UploadRequest(submissionId, "Student", List.of(pdf, jpeg));
        var existing = new Submission(submissionId, shop, "Student");
        when(submissions.findById(submissionId)).thenReturn(Optional.of(existing));
        when(intents.findBySubmission_Id(submissionId)).thenReturn(List.of(
                new UploadIntent(pdf.uploadId(), existing, shop, pdf.fileName(), pdf.contentType(), pdf.sizeBytes(),
                        "shops/cafe/pdf", Instant.now().plusSeconds(300)),
                new UploadIntent(jpeg.uploadId(), existing, shop, jpeg.fileName(), jpeg.contentType(), jpeg.sizeBytes(),
                        "shops/cafe/jpeg", Instant.now().plusSeconds(300))));

        var response = service.create("cafe", request, "127.0.0.2");

        assertThat(response.uploads()).hasSize(2);
        verify(submissions, never()).save(any());
        verify(intents, never()).save(any());
    }

    @Test
    void replacesFailedUploadObjectKeyWhenRetryingWithFreshGrant() {
        var submission = new Submission(shop, "Student");
        var intent = new UploadIntent(submission, shop, "photo.jpg", "image/jpeg", 100,
                "shops/cafe/failed.jpg", Instant.now().plusSeconds(300));
        intent.setState(UploadIntent.State.FAILED);
        when(intents.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(intents.findLockedById(intent.getId())).thenReturn(Optional.of(intent));
        when(storage.exists("shops/cafe/failed.jpg")).thenReturn(false);
        when(storage.presignPut(any(), any(), any(), anyLong(), any())).thenReturn("http://minio.test/retry");

        var grant = service.retry(intent.getId());

        assertThat(grant.url()).isEqualTo("http://minio.test/retry");
        assertThat(grant.status()).isEqualTo(UploadIntent.State.PENDING.name());
        assertThat(intent.getObjectKey()).isNotEqualTo("shops/cafe/failed.jpg");
        verify(storage).presignPut(eq(intent.getId()), eq(intent.getObjectKey()), eq("image/jpeg"), eq(100L), any());
    }

    private UploadRequest.FileRequest file(String name, String contentType, long size) {
        return new UploadRequest.FileRequest(UUID.randomUUID(), name, contentType, size);
    }
}
