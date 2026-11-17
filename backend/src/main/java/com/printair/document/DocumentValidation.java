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
            } else if (contentType.equals("image/jpeg")) {
                validateJpeg(digestStream);
            } else if (contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
                validateDocx(digestStream);
            } else {
                throw new InvalidDocumentException();
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

    private static void validateJpeg(InputStream input) throws IOException {
        var imageSource = new java.io.PushbackInputStream(input, 3);
        byte[] signature = imageSource.readNBytes(3);
        if (signature.length != 3 || (signature[0] & 0xff) != 0xff
                || (signature[1] & 0xff) != 0xd8 || (signature[2] & 0xff) != 0xff) {
            throw new InvalidDocumentException();
        }
        imageSource.unread(signature);
        try (var imageInput = new javax.imageio.stream.FileCacheImageInputStream(imageSource, null)) {
            var readers = javax.imageio.ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new InvalidDocumentException();
            var reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) throw new InvalidDocumentException();
                int sample = Math.max(1, (int) Math.ceil(Math.max(width, height) / 2048.0));
                var parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                if (reader.read(0, parameters) == null) throw new InvalidDocumentException();
            } catch (javax.imageio.IIOException exception) {
                throw new InvalidDocumentException();
            } finally {
                reader.dispose();
            }
        }
        imageSource.transferTo(java.io.OutputStream.nullOutputStream());
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
