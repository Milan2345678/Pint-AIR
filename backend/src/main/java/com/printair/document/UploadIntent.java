package com.printair.document;

import com.printair.shop.Shop;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upload_intents")
public class UploadIntent {
    public enum State { PENDING, FINALIZED, FAILED }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "declared_size", nullable = false)
    private long declaredSize;

    @Column(name = "object_key", nullable = false, unique = true, length = 512)
    private String objectKey;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected UploadIntent() {
    }

    public UploadIntent(Submission submission, Shop shop, String filename, String contentType,
                        long declaredSize, String objectKey, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.submission = submission;
        this.shop = shop;
        this.originalFilename = filename;
        this.contentType = contentType;
        this.declaredSize = declaredSize;
        this.objectKey = objectKey;
        this.state = State.PENDING.name();
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Submission getSubmission() { return submission; }
    public Shop getShop() { return shop; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getDeclaredSize() { return declaredSize; }
    public String getObjectKey() { return objectKey; }
    public String getState() { return state; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public void setState(State state) { this.state = state.name(); }
}
