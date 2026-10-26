package com.printair.document;

import com.printair.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Profile("worker")
public class ConversionWorker {
    private static final Logger logger = LoggerFactory.getLogger(ConversionWorker.class);
    private static final long MAX_PDF_BYTES = 50L * 1024 * 1024;
    private final ConversionJobRepository jobs;
    private final DocumentRepository documents;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;

    public ConversionWorker(ConversionJobRepository jobs, DocumentRepository documents, ObjectStorage storage,
                            PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.documents = documents;
        this.storage = storage;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${CONVERSION_POLL_MS:2000}")
    public void processNext() {
        var claim = claim();
        if (claim == null) return;
        Path directory = null;
        try {
            directory = Files.createTempDirectory("printair-convert-");
            var input = directory.resolve("source.docx");
            try (var stream = storage.open(claim.sourceKey())) {
                Files.copy(stream, input);
            }
            var outputLog = directory.resolve("converter.log");
            var process = new ProcessBuilder("soffice", "--headless", "--convert-to", "pdf",
                    "--outdir", directory.toString(), input.toString())
                    .redirectErrorStream(true).redirectOutput(outputLog.toFile()).start();
            awaitProcess(process);
            var pdf = directory.resolve("source.pdf");
            if (process.exitValue() != 0 || !Files.isRegularFile(pdf)
                    || Files.size(pdf) == 0 || Files.size(pdf) > MAX_PDF_BYTES) {
                throw new IOException("Document conversion did not produce a valid PDF");
            }
            try (var stream = Files.newInputStream(pdf)) {
                DocumentValidation.validateAndHash(stream, "application/pdf");
            }
            String pdfKey = "shops/" + claim.shopId() + "/previews/" + claim.documentId() + ".pdf";
            storage.putPdf(pdfKey, pdf);
            if (!complete(claim, pdfKey)) {
                storage.delete(pdfKey);
                if (storage.exists(pdfKey)) throw new IOException("Unclaimed preview cleanup failed");
            }
        } catch (IOException | InterruptedException | RuntimeException | TimeoutException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            fail(claim, exception instanceof TimeoutException ? "TIMEOUT" : "CONVERSION_FAILED");
            logger.warn("Document conversion failed id={} error={}", claim.documentId(),
                    exception.getClass().getSimpleName());
        } finally {
            if (directory != null) removeTemporaryFiles(directory, claim.documentId());
        }
    }

    private Claim claim() {
        return transactions.execute(status -> {
            jobs.findByStateAndLockedAtBefore("PROCESSING", Instant.now().minusSeconds(300))
                    .forEach(job -> job.setState("PENDING"));
            var pending = jobs.findFirstByStateOrderByCreatedAtAsc("PENDING");
            if (pending.isEmpty()) return null;
            var job = pending.get();
            job.setState("PROCESSING");
            job.setAttempts(job.getAttempts() + 1);
            job.setLockedAt(Instant.now());
            var document = job.getDocument();
            if (!document.getStatus().equals(DocumentStatus.PENDING_CONVERSION.name())
                    || !document.getExpiresAt().isAfter(Instant.now())) {
                job.setState("CANCELLED");
                return null;
            }
            return new Claim(job.getId(), document.getId(), document.getShop().getId(),
                    document.getSourceObjectKey());
        });
    }

    static void awaitProcess(Process process) throws InterruptedException, TimeoutException {
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new TimeoutException("Document conversion timed out");
        }
    }

    private boolean complete(Claim claim, String pdfKey) {
        return transactions.execute(status -> {
            var document = documents.findById(claim.documentId()).orElseThrow();
            var job = jobs.findById(claim.jobId()).orElseThrow();
            if (!document.getStatus().equals(DocumentStatus.PENDING_CONVERSION.name())
                    || !document.getExpiresAt().isAfter(Instant.now())) {
                job.setState("CANCELLED");
                return false;
            }
            document.setPdfObjectKey(pdfKey);
            document.setStatus(DocumentStatus.READY);
            job.setState("COMPLETE");
            job.setLastError(null);
            return true;
        });
    }

    private void fail(Claim claim, String error) {
        transactions.executeWithoutResult(status -> {
            var document = documents.findById(claim.documentId()).orElseThrow();
            var job = jobs.findById(claim.jobId()).orElseThrow();
            if (!document.getStatus().equals(DocumentStatus.PENDING_CONVERSION.name())) {
                job.setState("CANCELLED");
                return;
            }
            document.setStatus(DocumentStatus.CONVERSION_FAILED);
            job.setState("FAILED");
            job.setLastError(error);
        });
    }

    private void removeTemporaryFiles(Path directory, UUID documentId) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            logger.error("Temporary conversion files could not be removed id={}", documentId);
        }
    }

    private record Claim(UUID jobId, UUID documentId, UUID shopId, String sourceKey) {
    }
}
