package com.printair.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversion_jobs")
public class ConversionJob {
    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, unique = true)
    private PrintDocument document;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConversionJob() {
    }

    public ConversionJob(PrintDocument document) {
        this.id = UUID.randomUUID();
        this.document = document;
        this.state = "PENDING";
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public PrintDocument getDocument() { return document; }
    public String getState() { return state; }
    public int getAttempts() { return attempts; }
    public void setState(String state) { this.state = state; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public void setLockedAt(Instant lockedAt) { this.lockedAt = lockedAt; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
