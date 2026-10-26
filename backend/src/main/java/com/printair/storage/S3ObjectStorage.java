package com.printair.storage;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;

@Component
public class S3ObjectStorage implements ObjectStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public S3ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public String presignPut(String key, String contentType, long contentLength, Duration validity) {
        var put = PutObjectRequest.builder().bucket(properties.bucket()).key(key)
                .contentType(contentType).contentLength(contentLength).build();
        var request = PutObjectPresignRequest.builder().signatureDuration(validity).putObjectRequest(put).build();
        return presigner.presignPutObject(request).url().toString();
    }

    @Override
    public StoredObjectInfo inspect(String key) {
        var object = s3.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build());
        return new StoredObjectInfo(object.contentLength(), object.contentType());
    }

    @Override
    public InputStream open(String key) {
        return s3.getObject(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build());
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        s3.copyObject(CopyObjectRequest.builder().bucket(properties.bucket()).key(targetKey)
                .copySource(properties.bucket() + "/" + sourceKey).build());
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build());
            return true;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception exception) {
            if (exception.statusCode() == 404) return false;
            throw exception;
        }
    }

    @Override
    public void putPdf(String key, Path file) {
        s3.putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(key)
                        .contentType("application/pdf").build(),
                RequestBody.fromFile(file));
    }

    @Override
    public ObjectPage list(String continuationToken) {
        var request = ListObjectsV2Request.builder().bucket(properties.bucket()).prefix("shops/")
                .continuationToken(continuationToken).maxKeys(1000).build();
        var page = s3.listObjectsV2(request);
        return new ObjectPage(page.contents().stream()
                .map(object -> new StoredObject(object.key(), object.lastModified())).toList(),
                page.isTruncated() ? page.nextContinuationToken() : null);
    }
}
