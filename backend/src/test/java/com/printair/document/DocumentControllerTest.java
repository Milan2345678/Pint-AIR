package com.printair.document;

import com.printair.security.OperatorPrincipal;
import com.printair.shop.Shop;
import com.printair.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentControllerTest {
    @Test
    void previewsOriginalJpegWithItsImageContentType() throws Exception {
        var shop = new Shop("Cafe", "cafe");
        var intent = new UploadIntent(new Submission(shop, "Student"), shop, "photo.jpeg", "image/jpeg",
                4, "shops/cafe/photo.jpg", Instant.now().plusSeconds(300));
        var document = new PrintDocument(intent, 4, "0".repeat(64), Instant.now().plusSeconds(3600));
        byte[] image = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        var documents = mock(DocumentRepository.class);
        var storage = mock(ObjectStorage.class);
        when(documents.findByIdAndShopIdAndStatusNotAndExpiresAtAfter(
                eq(document.getId()), eq(shop.getId()), eq(DocumentStatus.DELETED.name()), any()))
                .thenReturn(Optional.of(document));
        when(storage.open(document.getSourceObjectKey())).thenReturn(new ByteArrayInputStream(image));
        var controller = new DocumentController(documents, storage, mock(DocumentLifecycleService.class));
        var response = new MockHttpServletResponse();
        var operator = new OperatorPrincipal(UUID.randomUUID(), shop.getId(), "operator@example.test", "hash", true);

        controller.preview(document.getId(), operator, response);

        assertThat(response.getContentType()).isEqualTo("image/jpeg");
        assertThat(response.getContentAsByteArray()).containsExactly(image);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }
}
