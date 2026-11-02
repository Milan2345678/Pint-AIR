package com.printair.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ObjectStorage {
    String presignPut(UUID uploadId, String key, String contentType, long contentLength, Duration validity);
    void put(String key, InputStream input, String contentType, long contentLength) throws java.io.IOException;
    StoredObjectInfo inspect(String key);
    InputStream open(String key);
    void copy(String sourceKey, String targetKey);
    void delete(String key);
    boolean exists(String key);
    void putPdf(String key, Path file);
    ObjectPage list(String continuationToken);

    record StoredObjectInfo(long contentLength, String contentType) {
    }

    record StoredObject(String key, Instant lastModified) {
    }

    record ObjectPage(List<StoredObject> objects, String nextToken) {
    }
}
