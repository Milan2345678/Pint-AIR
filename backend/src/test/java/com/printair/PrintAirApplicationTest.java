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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PrintAirApplicationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort
    int port;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectStorage storage;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("printair.storage.backend", () -> "local");
        registry.add("printair.storage.root-dir", () -> System.getProperty("java.io.tmpdir") + "/printair-test-storage");
        registry.add("printair.cors.allowed-origins", () -> "http://localhost");
        registry.add("printair.public-base-url", () -> "http://localhost");
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
    void publicUploadWithValidGrantSucceedsWithoutSession() throws Exception {
        UUID shopId = UUID.randomUUID();
        String objectKey = "shops/" + shopId + "/uploads/valid.pdf";
        UUID uploadId = insertUploadIntent(shopId, "valid.pdf", "application/pdf", objectKey, 8L,
                Instant.now().plusSeconds(300));

        mockMvc.perform(put("/api/public/uploads/{uploadId}/content", uploadId)
                        .param("key", objectKey)
                        .param("contentType", "application/pdf")
                        .contentType("application/pdf")
                        .content("%PDF-1.7".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isNoContent());
    }

    @Test
    void invalidUploadIdIsRejected() throws Exception {
        mockMvc.perform(put("/api/public/uploads/{uploadId}/content", UUID.randomUUID())
                        .param("key", "shops/invalid/document.pdf")
                        .param("contentType", "application/pdf")
                        .contentType("application/pdf")
                        .content("%PDF-1.7".getBytes(StandardCharsets.UTF_8))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiredUploadIntentIsRejected() throws Exception {
        UUID shopId = UUID.randomUUID();
        String objectKey = "shops/" + shopId + "/uploads/expired.pdf";
        UUID uploadId = insertUploadIntent(shopId, "expired.pdf", "application/pdf", objectKey, 8L,
                Instant.now().minusSeconds(60));

        mockMvc.perform(put("/api/public/uploads/{uploadId}/content", uploadId)
                        .param("key", objectKey)
                        .param("contentType", "application/pdf")
                        .contentType("application/pdf")
                        .content("%PDF-1.7".getBytes(StandardCharsets.UTF_8))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongObjectKeyIsRejected() throws Exception {
        UUID shopId = UUID.randomUUID();
        String objectKey = "shops/" + shopId + "/uploads/valid.pdf";
        UUID uploadId = insertUploadIntent(shopId, "valid.pdf", "application/pdf", objectKey, 8L,
                Instant.now().plusSeconds(300));

        mockMvc.perform(put("/api/public/uploads/{uploadId}/content", uploadId)
                        .param("key", "shops/other/value.pdf")
                        .param("contentType", "application/pdf")
                        .contentType("application/pdf")
                        .content("%PDF-1.7".getBytes(StandardCharsets.UTF_8))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletionRemovesPrivateObjectBeforeMarkingDocumentDeleted() throws Exception {
        UUID shopId = UUID.randomUUID();
        UUID[] document = insertDocument(shopId, "READY", Instant.now().plusSeconds(3600));
        String objectKey = "shops/" + shopId + "/source/test.pdf";
        storage.put(objectKey, new ByteArrayInputStream("%PDF-1.7".getBytes(StandardCharsets.UTF_8)),
                "application/pdf", 9L);
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

    private UUID insertUploadIntent(UUID shopId, String fileName, String contentType, String objectKey,
                                   long declaredSize, Instant expiresAt) {
        UUID submissionId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, ?, ?)", shopId, "Test shop", "shop-" + shopId);
        jdbc.update("INSERT INTO submissions (id, shop_id, customer_name) VALUES (?, ?, ?)",
                submissionId, shopId, "Student");
        jdbc.update("""
                INSERT INTO upload_intents
                    (id, submission_id, shop_id, original_filename, content_type, declared_size, object_key, state, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intentId, submissionId, shopId, fileName, contentType, declaredSize,
                objectKey, "PENDING", Timestamp.from(expiresAt));
        return intentId;
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
                "shops/" + shopId + "/source/test.pdf", "FINALIZED", Timestamp.from(expiry));
        jdbc.update("""
                INSERT INTO documents
                    (id, submission_id, upload_intent_id, shop_id, original_filename, content_type, size_bytes,
                     sha256, source_object_key, pdf_object_key, status, uploaded_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, documentId, submissionId, intentId, shopId, "test.pdf", "application/pdf", 8,
                "0".repeat(64), "shops/" + shopId + "/source/test.pdf", "shops/" + shopId + "/source/test.pdf",
                state, Timestamp.from(Instant.now()), Timestamp.from(expiry));
        return new UUID[] { documentId, shopId };
    }

    private OperatorPrincipal principal(UUID shopId) {
        return new OperatorPrincipal(UUID.randomUUID(), shopId, "operator@example.test", "hash", true);
    }

    private UsernamePasswordAuthenticationToken authenticationFor(OperatorPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
