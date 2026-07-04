package com.reconai.ledger.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "posting")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry journalEntry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    @Column(length = 200)
    private String counterparty;

    @Column(name = "external_ref", length = 255)
    private String externalRef;

    protected Posting() {}

    public Posting(Account account, BigDecimal amount, Direction direction,
                   String currency, LocalDate valueDate) {
        this.account = account;
        this.amount = amount;
        this.direction = direction;
        this.currency = currency;
        this.valueDate = valueDate;
    }

    void setJournalEntry(JournalEntry entry) { this.journalEntry = entry; }

    public Long getId() { return id; }
    public JournalEntry getJournalEntry() { return journalEntry; }
    public Account getAccount() { return account; }
    public BigDecimal getAmount() { return amount; }
    public Direction getDirection() { return direction; }
    public String getCurrency() { return currency; }
    public LocalDate getValueDate() { return valueDate; }
    public LocalDate getSettlementDate() { return settlementDate; }
    public String getCounterparty() { return counterparty; }
    public String getExternalRef() { return externalRef; }

    public void setSettlementDate(LocalDate settlementDate) { this.settlementDate = settlementDate; }
    public void setCounterparty(String counterparty) { this.counterparty = counterparty; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
}
