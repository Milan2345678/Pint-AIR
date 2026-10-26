package com.printair.document;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class DocumentValidation {
    private static final int MAX_ZIP_ENTRIES = 10_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 150L * 1024 * 1024;

    private DocumentValidation() {
    }

    static String validateAndHash(InputStream source, String contentType) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var digestStream = new java.security.DigestInputStream(source, digest);
            if (contentType.equals("application/pdf")) {
                validatePdf(digestStream);
            } else {
                validateDocx(digestStream);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void validatePdf(InputStream input) throws IOException {
        byte[] header = input.readNBytes(1024);
        String prefix = new String(header, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!prefix.contains("%PDF-")) throw new InvalidDocumentException();
        input.transferTo(java.io.OutputStream.nullOutputStream());
    }

    private static void validateDocx(InputStream input) throws IOException {
        boolean contentTypes = false;
        boolean documentXml = false;
        long expanded = 0;
        int entries = 0;
        var zip = new ZipInputStream(input);
        try {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new InvalidDocumentException();
                contentTypes |= entry.getName().equals("[Content_Types].xml");
                documentXml |= entry.getName().equals("word/document.xml");
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > MAX_UNCOMPRESSED_BYTES) throw new InvalidDocumentException();
                }
                zip.closeEntry();
            }
        } catch (java.util.zip.ZipException exception) {
            throw new InvalidDocumentException();
        }
        if (!contentTypes || !documentXml) throw new InvalidDocumentException();
        input.transferTo(java.io.OutputStream.nullOutputStream());
    }

    static final class InvalidDocumentException extends RuntimeException {
    }
}
