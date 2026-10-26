package com.printair.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Configuration {
    @Bean
    StaticCredentialsProvider storageCredentials(StorageProperties properties) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                properties.accessKey(), properties.secretKey()));
    }

    @Bean
    S3Client s3Client(StorageProperties properties, StaticCredentialsProvider credentials) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle()).build())
                .build();
    }

    @Bean
    S3Presigner s3Presigner(StorageProperties properties, StaticCredentialsProvider credentials) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.presignEndpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle()).build())
                .build();
    }
}
