package com.printair.document;

import com.printair.shop.Shop;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class PrintDocument {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "upload_intent_id", nullable = false, unique = true)
    private UploadIntent uploadIntent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "source_object_key", nullable = false, unique = true, length = 512)
    private String sourceObjectKey;

    @Column(name = "pdf_object_key", length = 512)
    private String pdfObjectKey;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected PrintDocument() {
    }

    public PrintDocument(UploadIntent intent, long sizeBytes, String sha256, Instant expiresAt, boolean pdf) {
        this.id = UUID.randomUUID();
        this.submission = intent.getSubmission();
        this.uploadIntent = intent;
        this.shop = intent.getShop();
        this.originalFilename = intent.getOriginalFilename();
        this.contentType = intent.getContentType();
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.sourceObjectKey = intent.getObjectKey();
        this.pdfObjectKey = pdf ? intent.getObjectKey() : null;
        this.status = pdf ? DocumentStatus.READY.name() : DocumentStatus.PENDING_CONVERSION.name();
        this.uploadedAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public Submission getSubmission() { return submission; }
    public Shop getShop() { return shop; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getSourceObjectKey() { return sourceObjectKey; }
    public String getPdfObjectKey() { return pdfObjectKey; }
    public String getStatus() { return status; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setStatus(DocumentStatus status) { this.status = status.name(); }
    public void setPdfObjectKey(String key) { this.pdfObjectKey = key; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
