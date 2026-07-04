CREATE TABLE account (
    id       BIGSERIAL    PRIMARY KEY,
    name     VARCHAR(200) NOT NULL,
    currency VARCHAR(3)   NOT NULL,
    type     VARCHAR(20)  NOT NULL CHECK (type IN ('ASSET','LIABILITY','INCOME','EXPENSE','SUSPENSE'))
);

CREATE TABLE journal_entry (
    id              BIGSERIAL    PRIMARY KEY,
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE posting (
    id               BIGSERIAL      PRIMARY KEY,
    journal_entry_id BIGINT         NOT NULL REFERENCES journal_entry(id),
    account_id       BIGINT         NOT NULL REFERENCES account(id),
    amount           NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    direction        VARCHAR(6)     NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    currency         VARCHAR(3)     NOT NULL,
    value_date       DATE           NOT NULL,
    settlement_date  DATE,
    counterparty     VARCHAR(200),
    external_ref     VARCHAR(255)
);

CREATE INDEX idx_posting_journal_entry ON posting(journal_entry_id);
CREATE INDEX idx_posting_account       ON posting(account_id);
CREATE INDEX idx_journal_idempotency   ON journal_entry(idempotency_key);
