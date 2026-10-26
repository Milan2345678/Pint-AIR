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
@Table(name = "submissions")
public class Submission {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Submission() {
    }

    public Submission(Shop shop, String customerName) {
        this.id = UUID.randomUUID();
        this.shop = shop;
        this.customerName = customerName;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Shop getShop() { return shop; }
    public String getCustomerName() { return customerName; }
}
