package com.printair.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Primary
@ConditionalOnProperty(prefix = "printair.storage", name = "backend", havingValue = "local")
public class LocalObjectStorage implements ObjectStorage {
    private final Path root;

    public LocalObjectStorage(StorageProperties properties) {
        String configured = properties.rootDir();
        this.root = Path.of(configured == null || configured.isBlank() ? "/var/lib/printair/storage" : configured)
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create local storage directory", exception);
        }
    }

    @Override
    public String presignPut(UUID uploadId, String key, String contentType, long contentLength, Duration validity) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        String encodedType = URLEncoder.encode(contentType, StandardCharsets.UTF_8);
        return "http://localhost:8080/api/public/uploads/" + uploadId + "/content?key=" + encodedKey
                + "&contentType=" + encodedType;
    }

    @Override
    public void put(String key, InputStream input, String contentType, long contentLength) throws IOException {
        Path target = resolvePath(key);
        Files.createDirectories(target.getParent());
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            input.transferTo(output);
        }
    }

    @Override
    public StoredObjectInfo inspect(String key) {
        Path resolved = resolvePath(key);
        if (!Files.exists(resolved)) {
            throw new IllegalStateException("Local object does not exist: " + key);
        }
        try {
            return new StoredObjectInfo(Files.size(resolved), detectContentType(resolved));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect local object: " + key, exception);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return Files.newInputStream(resolvePath(key));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to open local object: " + key, exception);
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        try {
            Path source = resolvePath(sourceKey);
            Path target = resolvePath(targetKey);
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to copy local object " + sourceKey + " -> " + targetKey, exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolvePath(key));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to delete local object: " + key, exception);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return Files.exists(resolvePath(key));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public void putPdf(String key, Path file) {
        try {
            Path target = resolvePath(key);
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save converted PDF: " + key, exception);
        }
    }

    @Override
    public ObjectPage list(String continuationToken) {
        try (var stream = Files.walk(root)) {
            List<StoredObject> objects = new ArrayList<>();
            for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
                objects.add(new StoredObject(root.relativize(path).toString().replace('\\', '/'),
                        Files.getLastModifiedTime(path).toInstant()));
            }
            return new ObjectPage(objects, null);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to list local storage objects", exception);
        }
    }

    private Path resolvePath(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage key must not be blank");
        }
        String safeKey = key.replace('\\', '/');
        if (safeKey.startsWith("/")) {
            safeKey = safeKey.substring(1);
        }
        if (safeKey.contains("..")) {
            throw new IllegalArgumentException("Storage key contains an invalid path segment: " + key);
        }
        Path candidate = root.resolve(safeKey).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Storage key resolves outside the configured root: " + key);
        }
        return candidate;
    }

    private String detectContentType(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        if (filename.endsWith(".pdf")) return "application/pdf";
        if (filename.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        try {
            String contentType = Files.probeContentType(path);
            return contentType == null ? "application/octet-stream" : contentType;
        } catch (IOException exception) {
            return "application/octet-stream";
        }
    }
}
