package com.printair.document;

import com.printair.shop.Shop;
import com.printair.shop.ShopRepository;
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
    private final ShopRepository shops = mock(ShopRepository.class);

    @Test
    void finalizesJpegAsReadyAndDoesNotQueueDocxConversion() throws Exception {
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", bytes);
        var jpeg = bytes.toByteArray();
        var shop = new Shop("Cafe", "cafe");
        var submission = new Submission(shop, "Student");
        var intent = new UploadIntent(submission, shop, "photo.jpeg", "image/jpeg", jpeg.length,
                "shops/key/photo.jpg", Instant.now().plusSeconds(300));
        var documentKey = "shops/" + shop.getId() + "/documents/" + intent.getId() + "/source.jpg";
        var intents = mock(UploadIntentRepository.class);
        var documents = mock(DocumentRepository.class);
        var jobs = mock(ConversionJobRepository.class);
        var storage = mock(ObjectStorage.class);
        when(intents.findLockedById(any())).thenReturn(Optional.of(intent));
        when(storage.exists(documentKey)).thenReturn(false);
        when(storage.inspect(any())).thenReturn(new ObjectStorage.StoredObjectInfo(jpeg.length, "image/jpeg"));
        when(storage.open(documentKey)).thenReturn(new ByteArrayInputStream(jpeg));
        var service = service(shop, intent, intents, documents, jobs, storage);

        assertThat(service.finalizeUpload(intent.getId())).isTrue();

        var saved = org.mockito.ArgumentCaptor.forClass(PrintDocument.class);
        verify(documents).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DocumentStatus.READY.name());
        assertThat(saved.getValue().getSourceObjectKey()).isEqualTo(documentKey);
        assertThat(saved.getValue().getPdfObjectKey()).isNull();
        verify(jobs, never()).save(any(ConversionJob.class));
    }

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
        var service = service(shop, intent, intents, documents, jobs, storage);

        assertThat(service.finalizeUpload(intent.getId())).isTrue();
        assertThat(service.finalizeUpload(intent.getId())).isTrue();

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
        var service = service(shop, intent, intents, documents, jobs, storage);

        assertThat(service.finalizeUpload(intent.getId())).isFalse();

        verify(storage).delete("shops/key/spoofed.pdf");
        verify(documents, never()).save(any());
    }

    @Test
    void rejectsAndDeletesUploadWhoseActualSizeExceedsItsReservation() {
        var shop = new Shop("Cafe", "cafe");
        var submission = new Submission(shop, "Student");
        var intent = new UploadIntent(submission, shop, "report.pdf", "application/pdf", 9,
                "shops/key/report.pdf", Instant.now().plusSeconds(300));
        var documentKey = "shops/" + shop.getId() + "/documents/" + intent.getId() + "/source.pdf";
        var intents = mock(UploadIntentRepository.class);
        var documents = mock(DocumentRepository.class);
        var jobs = mock(ConversionJobRepository.class);
        var storage = mock(ObjectStorage.class);
        when(intents.findLockedById(any())).thenReturn(Optional.of(intent));
        when(storage.exists(documentKey)).thenReturn(false);
        when(storage.exists("shops/key/report.pdf")).thenReturn(false);
        when(storage.inspect("shops/key/report.pdf")).thenReturn(
                new ObjectStorage.StoredObjectInfo(10, "application/pdf"));
        var service = service(shop, intent, intents, documents, jobs, storage);

        assertThat(service.finalizeUpload(intent.getId())).isFalse();

        verify(storage).delete("shops/key/report.pdf");
        verify(storage, never()).copy(any(), any());
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
        var service = service(shop, intent, intents, documents, jobs, storage);

        assertThat(service.finalizeUpload(intent.getId())).isTrue();
        verify(storage, never()).inspect("shops/key/file.pdf");
        verify(storage, never()).copy(any(), any());
        assertThat(intent.getObjectKey()).isEqualTo(documentKey);
    }

    private FinalizeUploadService service(Shop shop, UploadIntent intent, UploadIntentRepository intents,
                                          DocumentRepository documents, ConversionJobRepository jobs,
                                          ObjectStorage storage) {
        when(shops.findLockedBySlug(shop.getSlug())).thenReturn(Optional.of(shop));
        when(intents.findById(intent.getId())).thenReturn(Optional.of(intent));
        return new FinalizeUploadService(intents, documents, jobs, shops, storage, new MockEnvironment());
    }
}
