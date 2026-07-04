package com.reconai.breaks;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "recon_break")
public class ReconBreak {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "detected_type", length = 30)
    private String detectedType;

    @Column(name = "detected_confidence", precision = 5, scale = 4)
    private BigDecimal detectedConfidence;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_code", length = 50)
    private String resolutionCode;

    public Long getId() { return id; }
    public Long getBatchId() { return batchId; }
    public String getDetectedType() { return detectedType; }
    public BigDecimal getDetectedConfidence() { return detectedConfidence; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolutionCode() { return resolutionCode; }
}
