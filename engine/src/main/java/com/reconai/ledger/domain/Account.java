package com.reconai.ledger.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    protected Account() {}

    public Account(String name, String currency, AccountType type) {
        this.name = name;
        this.currency = currency;
        this.type = type;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getCurrency() { return currency; }
    public AccountType getType() { return type; }
}
