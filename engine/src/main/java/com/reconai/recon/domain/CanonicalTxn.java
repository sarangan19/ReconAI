package com.reconai.recon.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "canonical_txn")
public class CanonicalTxn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TxnSide side;

    @Column(name = "external_ref", length = 255)
    private String externalRef;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 200)
    private String counterparty;

    @Column(name = "trade_date")
    private LocalDate tradeDate;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private com.reconai.ledger.domain.Direction direction;

    @Column(name = "match_id")
    private Long matchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TxnStatus status;

    public Long getId() { return id; }
    public Long getBatchId() { return batchId; }
    public TxnSide getSide() { return side; }
    public String getExternalRef() { return externalRef; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getCounterparty() { return counterparty; }
    public LocalDate getTradeDate() { return tradeDate; }
    public LocalDate getValueDate() { return valueDate; }
    public LocalDate getSettlementDate() { return settlementDate; }
    public com.reconai.ledger.domain.Direction getDirection() { return direction; }
    public Long getMatchId() { return matchId; }
    public TxnStatus getStatus() { return status; }
    public void setStatus(TxnStatus status) { this.status = status; }
    public void setMatchId(Long matchId) { this.matchId = matchId; }
}
