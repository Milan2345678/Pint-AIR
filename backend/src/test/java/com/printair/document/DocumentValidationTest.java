package com.printair.document;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentValidationTest {
    @Test
    void acceptsPdfHeaderAndReturnsSha256() throws Exception {
        var bytes = "%PDF-1.7\nbody".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        var checksum = DocumentValidation.validateAndHash(
                new ByteArrayInputStream(bytes), "application/pdf");

        assertThat(checksum).hasSize(64);
    }

    @Test
    void rejectsSpoofedPdfExtensionWithWrongMagicBytes() {
        assertThatThrownBy(() -> DocumentValidation.validateAndHash(
                new ByteArrayInputStream("not a pdf".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                "application/pdf")).isInstanceOf(DocumentValidation.InvalidDocumentException.class);
    }

    @Test
    void acceptsDocxContainerAndRejectsZipWithoutWordDocument() throws Exception {
        var validDocx = docxZip("word/document.xml");
        var withTrailer = java.util.Arrays.copyOf(validDocx, validDocx.length + 4);
        withTrailer[validDocx.length] = 1;
        var checksum = DocumentValidation.validateAndHash(new ByteArrayInputStream(withTrailer),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(checksum).isEqualTo(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(withTrailer)));

        var invalidDocx = docxZip("not-a-word-document.xml");
        assertThatThrownBy(() -> DocumentValidation.validateAndHash(new ByteArrayInputStream(invalidDocx),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .isInstanceOf(DocumentValidation.InvalidDocumentException.class);
    }

    private byte[] docxZip(String documentEntry) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(documentEntry));
            zip.write("<document/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
