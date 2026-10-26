package com.printair.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("printair.storage")
public record StorageProperties(
        String endpoint,
        String presignEndpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        int presignSeconds,
        boolean pathStyle) {
}
