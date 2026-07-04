package com.reconai.groundtruth;

import com.reconai.recon.domain.DiscrepancyCode;
import com.reconai.recon.domain.TxnSide;
import jakarta.persistence.*;

/**
 * Isolated ground-truth manifest. Engine runtime code NEVER reads this table.
 * Only agent/eval.py and benchmarks may read it.
 */
@Entity
@Table(name = "ground_truth")
public class GroundTruth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "external_ref", nullable = false, length = 255)
    private String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TxnSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "injected_code", nullable = false, length = 30)
    private DiscrepancyCode injectedCode;

    protected GroundTruth() {}

    public GroundTruth(Long batchId, String externalRef, TxnSide side, DiscrepancyCode injectedCode) {
        this.batchId = batchId;
        this.externalRef = externalRef;
        this.side = side;
        this.injectedCode = injectedCode;
    }

    public Long getId() { return id; }
    public Long getBatchId() { return batchId; }
    public String getExternalRef() { return externalRef; }
    public TxnSide getSide() { return side; }
    public DiscrepancyCode getInjectedCode() { return injectedCode; }
}
