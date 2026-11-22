package com.printair;

import com.printair.security.OperatorPrincipal;
import com.printair.document.FinalizeUploadService;
import com.printair.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PrintAirApplicationIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort
    int port;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FinalizeUploadService finalizer;
    @SpyBean ObjectStorage storage;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("printair.storage.backend", () -> "local");
        registry.add("printair.storage.root-dir", () -> System.getProperty("java.io.tmpdir") + "/printair-test-storage");
        registry.add("printair.cors.allowed-origins", () -> "http://localhost");
        registry.add("printair.public-base-url", () -> "http://localhost");
        registry.add("printair.shop-quota-bytes", () -> "100");
        registry.add("spring.mail.host", () -> "mailpit");
        registry.add("spring.mail.port", () -> "1025");
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("printair.mail-from", () -> "noreply@printair.local");
        registry.add("printair.dashboard-base-url", () -> "http://localhost:5173");
    }


    @Test
    void startsAndConnectsToPostgres() {
        var response = new TestRestTemplate().getForEntity(
                "http://localhost:" + port + "/actuator/health/readiness", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('public.shop_operator_email_verification_tokens') IS NOT NULL",
                Boolean.class)).isTrue();
    }

    @Test
    void configuresExactlyOneSecurityFilterChain(ApplicationContext context) {
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
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
    void printStreamsTheStoredPdfBytesUnchanged() throws Exception {
        UUID[] document = insertDocument(UUID.randomUUID(), "READY", Instant.now().plusSeconds(3600));
        byte[] pdfBytes = "%PDF-1.7\noriginal-pdf-content".getBytes(StandardCharsets.UTF_8);
        String objectKey = "shops/" + document[1] + "/documents/original.pdf";
        storage.put(objectKey, new ByteArrayInputStream(pdfBytes), "application/pdf", pdfBytes.length);
        jdbc.update("UPDATE documents SET source_object_key = ?, pdf_object_key = ? WHERE id = ?",
                objectKey, objectKey, document[0]);

        var response = mockMvc.perform(get("/api/operator/documents/{id}/print", document[0])
                        .with(authentication(authenticationFor(principal(document[1])))))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse();

        assertThat(response.getContentAsByteArray()).containsExactly(pdfBytes);
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
    void documentListContainsOnlyUnexpiredDocumentsFromTheAuthenticatedShop() throws Exception {
        UUID shopId = UUID.randomUUID();
        UUID otherShopId = UUID.randomUUID();
        UUID[] first = insertDocument(shopId, "READY", Instant.now().plusSeconds(3600));
        UUID[] second = insertDocument(shopId, "READY", Instant.now().plusSeconds(3600));
        UUID[] expired = insertDocument(shopId, "READY", Instant.now().minusSeconds(10));
        UUID[] otherShop = insertDocument(otherShopId, "READY", Instant.now().plusSeconds(3600));

        String response = mockMvc.perform(get("/api/operator/documents")
                        .with(authentication(authenticationFor(principal(shopId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains(first[0].toString(), second[0].toString())
                .doesNotContain(expired[0].toString(), otherShop[0].toString());
    }

    @Test
    void rejectsMalformedDocumentIds() throws Exception {
        mockMvc.perform(get("/api/operator/documents/not-a-uuid/preview")
                        .with(authentication(authenticationFor(principal(UUID.randomUUID())))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createsOneSubmissionWithIndependentUploadGrantsForMultipleFiles() throws Exception {
        UUID shopId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID pdfId = UUID.randomUUID();
        UUID docxId = UUID.randomUUID();
        UUID jpegId = UUID.randomUUID();
        String slug = "shop-" + shopId;
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, ?, ?)", shopId, "Test shop", slug);
        String body = """
                {"submissionId":"%s","customerName":"Student","files":[
                  {"uploadId":"%s","fileName":"one.pdf","contentType":"application/pdf","sizeBytes":8},
                  {"uploadId":"%s","fileName":"two.docx","contentType":"application/vnd.openxmlformats-officedocument.wordprocessingml.document","sizeBytes":9},
                  {"uploadId":"%s","fileName":"three.jpeg","contentType":"image/jpeg","sizeBytes":10}
                ]}
                """.formatted(submissionId, pdfId, docxId, jpegId);

        mockMvc.perform(post("/api/public/shops/{slug}/submissions", slug)
                        .contentType("application/json").content(body).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploads.length()").value(3));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM submissions WHERE id = ?", Integer.class, submissionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_intents WHERE submission_id = ?",
                Integer.class, submissionId)).isEqualTo(3);
    }

    @Test
    void rejectsUnsupportedSubmissionFileTypes() throws Exception {
        UUID shopId = UUID.randomUUID();
        String slug = "shop-" + shopId;
        UUID submissionId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, ?, ?)", shopId, "Test shop", slug);
        String body = """
                {"submissionId":"%s","customerName":"Student","files":[
                  {"uploadId":"%s","fileName":"script.exe","contentType":"application/octet-stream","sizeBytes":8}
                ]}
                """.formatted(submissionId, UUID.randomUUID());

        mockMvc.perform(post("/api/public/shops/{slug}/submissions", slug)
                        .contentType("application/json").content(body).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM submissions WHERE id = ?", Integer.class, submissionId)).isZero();
    }

    @Test
    void rejectsNonPositiveSubmissionFileSizes() throws Exception {
        UUID shopId = UUID.randomUUID();
        String slug = "shop-" + shopId;
        UUID submissionId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, ?, ?)", shopId, "Test shop", slug);
        String body = """
                {"submissionId":"%s","customerName":"Student","files":[
                  {"uploadId":"%s","fileName":"empty.pdf","contentType":"application/pdf","sizeBytes":0}
                ]}
                """.formatted(submissionId, UUID.randomUUID());

        mockMvc.perform(post("/api/public/shops/{slug}/submissions", slug)
                        .contentType("application/json").content(body).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM submissions WHERE id = ?", Integer.class, submissionId)).isZero();
    }

    @Test
    void serializesConcurrentReservationsAgainstTheShopQuota() throws Exception {
        UUID shopId = UUID.randomUUID();
        String slug = "quota-shop-" + shopId;
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, ?, ?)", shopId, "Quota shop", slug);

        Callable<Integer> first = createSingleFileSubmission(slug, "127.0.0.2");
        Callable<Integer> second = createSingleFileSubmission(slug, "127.0.0.3");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = executor.invokeAll(List.of(first, second));
            assertThat(results.stream().map(result -> {
                try {
                    return result.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            })).containsExactlyInAnyOrder(200, 429);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM submissions WHERE shop_id = ?",
                Integer.class, shopId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(declared_size) FROM upload_intents WHERE shop_id = ?",
                Long.class, shopId)).isEqualTo(60L);
    }

    @Test
    void finalizationAndReservationCannotUnderCountTheShopQuota() throws Exception {
        UUID shopId = UUID.randomUUID();
        String slug = "shop-" + shopId;
        String uploadKey = "shops/" + shopId + "/uploads/finalize-race.pdf";
        UUID uploadId = insertUploadIntent(shopId, "finalize-race.pdf", "application/pdf", uploadKey, 60L,
                Instant.now().plusSeconds(300));
        byte[] pdf = new byte[60];
        java.util.Arrays.fill(pdf, (byte) ' ');
        byte[] header = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, pdf, 0, header.length);
        storage.put(uploadKey, new ByteArrayInputStream(pdf), "application/pdf", pdf.length);

        String documentKey = "shops/" + shopId + "/documents/" + uploadId + "/source.pdf";
        var copyStarted = new CountDownLatch(1);
        var allowCopy = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            copyStarted.countDown();
            if (!allowCopy.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to resume the test copy");
            }
            return invocation.callRealMethod();
        }).when(storage).copy(uploadKey, documentKey);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var finalizeFuture = executor.submit(() -> finalizer.finalizeUpload(uploadId));
            assertThat(copyStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var reservationFuture = executor.submit(
                    createSingleFileSubmission(slug, "127.0.0.9"));
            assertThatThrownBy(() -> reservationFuture.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowCopy.countDown();
            assertThat(finalizeFuture.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reservationFuture.get(10, TimeUnit.SECONDS)).isEqualTo(429);
        } finally {
            allowCopy.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT state FROM upload_intents WHERE id = ?",
                String.class, uploadId)).isEqualTo("FINALIZED");
        assertThat(jdbc.queryForObject("SELECT sum(size_bytes) FROM documents WHERE shop_id = ?",
                Long.class, shopId)).isEqualTo(60L);
        assertThat(jdbc.queryForObject(
                "SELECT coalesce(sum(declared_size), 0) FROM upload_intents WHERE shop_id = ? AND state = 'PENDING'",
                Long.class, shopId)).isZero();
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

    private Callable<Integer> createSingleFileSubmission(String slug, String remoteAddress) {
        UUID submissionId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        String body = """
                {"submissionId":"%s","customerName":"Student","files":[
                  {"uploadId":"%s","fileName":"quota.pdf","contentType":"application/pdf","sizeBytes":60}
                ]}
                """.formatted(submissionId, uploadId);
        return () -> mockMvc.perform(post("/api/public/shops/{slug}/submissions", slug)
                        .contentType("application/json")
                        .content(body)
                        .with(csrf())
                        .with(remoteAddress(remoteAddress)))
                .andReturn().getResponse().getStatus();
    }

    private RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private UUID[] insertDocument(UUID shopId, String state, Instant expiry) {
        UUID submissionId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        String sourceObjectKey = "shops/" + shopId + "/source/" + intentId + "/test.pdf";
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING",
                shopId, "Test shop", "shop-" + shopId);
        jdbc.update("INSERT INTO submissions (id, shop_id, customer_name) VALUES (?, ?, ?)",
                submissionId, shopId, "Student");
        jdbc.update("""
                INSERT INTO upload_intents
                    (id, submission_id, shop_id, original_filename, content_type, declared_size, object_key, state, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, intentId, submissionId, shopId, "test.pdf", "application/pdf", 8,
                sourceObjectKey, "FINALIZED", Timestamp.from(expiry));
        jdbc.update("""
                INSERT INTO documents
                    (id, submission_id, upload_intent_id, shop_id, original_filename, content_type, size_bytes,
                     sha256, source_object_key, pdf_object_key, status, uploaded_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, documentId, submissionId, intentId, shopId, "test.pdf", "application/pdf", 8,
                    "0".repeat(64), sourceObjectKey, sourceObjectKey,
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
