package com.reconai.recon.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "batch")
public class Batch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BatchStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Batch() {}

    public Batch(String name) {
        this.name = name;
        this.status = BatchStatus.CREATED;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public BatchStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setStatus(BatchStatus status) { this.status = status; }
}
