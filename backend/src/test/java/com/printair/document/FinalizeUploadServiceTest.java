package com.printair.document;

import com.printair.shop.Shop;
import com.printair.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FinalizeUploadServiceTest {
    @Test
    void repeatedFinalizeDoesNotDuplicateDocumentOrRecheckStorage() {
        var shop = new Shop("Cafe", "cafe");
        var submission = new Submission(shop, "Student");
        var intent = new UploadIntent(submission, shop, "file.pdf", "application/pdf", 9,
                "shops/key/file.pdf", Instant.now().plusSeconds(300));
        var documentKey = "shops/" + shop.getId() + "/documents/" + intent.getId() + "/source.pdf";
        var intents = mock(UploadIntentRepository.class);
        var documents = mock(DocumentRepository.class);
        var jobs = mock(ConversionJobRepository.class);
        var storage = mock(ObjectStorage.class);
        when(intents.findLockedById(any())).thenReturn(Optional.of(intent));
        when(storage.exists(documentKey)).thenReturn(false);
        when(storage.inspect(any())).thenReturn(new ObjectStorage.StoredObjectInfo(9, "application/pdf"));
        when(storage.open(documentKey)).thenReturn(
                new ByteArrayInputStream("%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        var service = new FinalizeUploadService(intents, documents, jobs, storage, new MockEnvironment());

        assertThat(service.finalizeUpload(UUID.randomUUID())).isTrue();
        assertThat(service.finalizeUpload(UUID.randomUUID())).isTrue();

        verify(storage, times(1)).copy("shops/key/file.pdf",
                documentKey);
        assertThat(intent.getObjectKey()).isEqualTo(documentKey);
        verify(documents, times(1)).save(any(PrintDocument.class));
    }

    @Test
    void rejectsAndDeletesStoredObjectWhoseBytesDoNotMatchTheDeclaredPdfType() {
        var shop = new Shop("Cafe", "cafe");
        var submission = new Submission(shop, "Student");
        var intent = new UploadIntent(submission, shop, "spoofed.pdf", "application/pdf", 9,
                "shops/key/spoofed.pdf", Instant.now().plusSeconds(300));
        var documentKey = "shops/" + shop.getId() + "/documents/" + intent.getId() + "/source.pdf";
        var intents = mock(UploadIntentRepository.class);
        var documents = mock(DocumentRepository.class);
        var jobs = mock(ConversionJobRepository.class);
        var storage = mock(ObjectStorage.class);
        when(intents.findLockedById(any())).thenReturn(Optional.of(intent));
        when(storage.exists(documentKey)).thenReturn(false, true, false);
        when(storage.inspect("shops/key/spoofed.pdf")).thenReturn(
                new ObjectStorage.StoredObjectInfo(9, "application/pdf"));
        when(storage.inspect(documentKey)).thenReturn(
                new ObjectStorage.StoredObjectInfo(9, "application/pdf"));
        when(storage.open(documentKey)).thenReturn(
                new ByteArrayInputStream("not-pdf!!".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        var service = new FinalizeUploadService(intents, documents, jobs, storage, new MockEnvironment());

        assertThat(service.finalizeUpload(UUID.randomUUID())).isFalse();

        verify(storage).delete("shops/key/spoofed.pdf");
        verify(documents, never()).save(any());
    }

    @Test
    void canFinalizeAfterCopySucceededButTheUploadKeyWasAlreadyRemoved() {
        var shop = new Shop("Cafe", "cafe");
        var submission = new Submission(shop, "Student");
        var intent = new UploadIntent(submission, shop, "file.pdf", "application/pdf", 9,
                "shops/key/file.pdf", Instant.now().plusSeconds(300));
        var documentKey = "shops/" + shop.getId() + "/documents/" + intent.getId() + "/source.pdf";
        var intents = mock(UploadIntentRepository.class);
        var documents = mock(DocumentRepository.class);
        var jobs = mock(ConversionJobRepository.class);
        var storage = mock(ObjectStorage.class);
        when(intents.findLockedById(any())).thenReturn(Optional.of(intent));
        when(storage.exists(documentKey)).thenReturn(true);
        when(storage.inspect(documentKey)).thenReturn(new ObjectStorage.StoredObjectInfo(9, "application/pdf"));
        when(storage.open(documentKey)).thenReturn(
                new ByteArrayInputStream("%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        var service = new FinalizeUploadService(intents, documents, jobs, storage, new MockEnvironment());

        assertThat(service.finalizeUpload(UUID.randomUUID())).isTrue();
        verify(storage, never()).inspect("shops/key/file.pdf");
        verify(storage, never()).copy(any(), any());
        assertThat(intent.getObjectKey()).isEqualTo(documentKey);
    }
}
