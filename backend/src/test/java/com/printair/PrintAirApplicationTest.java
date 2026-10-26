package com.printair;

import com.printair.security.OperatorPrincipal;
import com.printair.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PrintAirApplicationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> minio = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "printair-test")
            .withEnv("MINIO_ROOT_PASSWORD", "printair-test-password")
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @LocalServerPort
    int port;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired S3Client s3;
    @Autowired ObjectStorage storage;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("printair.storage.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        registry.add("printair.storage.presign-endpoint",
                () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        registry.add("printair.storage.region", () -> "us-east-1");
        registry.add("printair.storage.bucket", () -> "printair-test");
        registry.add("printair.storage.access-key", () -> "printair-test");
        registry.add("printair.storage.secret-key", () -> "printair-test-password");
        registry.add("printair.storage.presign-seconds", () -> "300");
        registry.add("printair.storage.path-style", () -> "true");
        registry.add("printair.cors.allowed-origins", () -> "http://localhost");
        registry.add("printair.public-base-url", () -> "http://localhost");
    }

    @BeforeEach
    void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket("printair-test").build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) throw exception;
            s3.createBucket(CreateBucketRequest.builder().bucket("printair-test").build());
        }
    }

    @Test
    void startsAndConnectsToPostgres() {
        var response = new TestRestTemplate().getForEntity("http://localhost:" + port + "/actuator/health", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void deniesUnauthenticatedPreviewAndPrint() throws Exception {
        UUID[] document = insertDocument(UUID.randomUUID(), "READY", Instant.now().plusSeconds(3600));

        mockMvc.perform(get("/api/operator/documents/{id}/preview", document[0]))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/operator/documents/{id}/print", document[0]))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shopCannotPreviewAnotherShopsDocument() throws Exception {
        UUID otherShop = UUID.randomUUID();
        UUID[] document = insertDocument(otherShop, "READY", Instant.now().plusSeconds(3600));
        var operator = principal(UUID.randomUUID());

        mockMvc.perform(get("/api/operator/documents/{id}/preview", document[0])
                        .with(authentication(authenticationFor(operator))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletionRemovesPrivateObjectBeforeMarkingDocumentDeleted() throws Exception {
        UUID shopId = UUID.randomUUID();
        UUID[] document = insertDocument(shopId, "READY", Instant.now().plusSeconds(3600));
        String objectKey = "shops/" + shopId + "/source/test.pdf";
        s3.putObject(builder -> builder.bucket("printair-test").key(objectKey)
                        .contentType("application/pdf"),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes("%PDF-1.7".getBytes()));
        jdbc.update("UPDATE documents SET source_object_key = ?, pdf_object_key = ? WHERE id = ?",
                objectKey, objectKey, document[0]);

        mockMvc.perform(delete("/api/operator/documents/{id}", document[0])
                        .with(authentication(authenticationFor(principal(shopId))))
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(storage.exists(objectKey)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, document[0]))
                .isEqualTo("DELETED");
    }

    private UUID[] insertDocument(UUID shopId, String state, Instant expiry) {
        UUID submissionId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, ?, ?)", shopId, "Test shop", "shop-" + shopId);
        jdbc.update("INSERT INTO submissions (id, shop_id, customer_name) VALUES (?, ?, ?)",
                submissionId, shopId, "Student");
        jdbc.update("""
                INSERT INTO upload_intents
                    (id, submission_id, shop_id, original_filename, content_type, declared_size, object_key, state, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intentId, submissionId, shopId, "test.pdf", "application/pdf", 8,
                "shops/" + shopId + "/source/test.pdf", "FINALIZED", expiry);
        jdbc.update("""
                INSERT INTO documents
                    (id, submission_id, upload_intent_id, shop_id, original_filename, content_type, size_bytes,
                     sha256, source_object_key, pdf_object_key, status, uploaded_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, documentId, submissionId, intentId, shopId, "test.pdf", "application/pdf", 8,
                "0".repeat(64), "shops/" + shopId + "/source/test.pdf", "shops/" + shopId + "/source/test.pdf",
                state, Instant.now(), expiry);
        return new UUID[] { documentId, shopId };
    }

    private OperatorPrincipal principal(UUID shopId) {
        return new OperatorPrincipal(UUID.randomUUID(), shopId, "operator@example.test", "hash", true);
    }

    private UsernamePasswordAuthenticationToken authenticationFor(OperatorPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
