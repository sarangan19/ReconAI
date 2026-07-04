CREATE TABLE batch (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'CREATED'
                CHECK (status IN ('CREATED','SIMULATED','RECONCILING','RECONCILED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE canonical_txn (
    id              BIGSERIAL      PRIMARY KEY,
    batch_id        BIGINT         NOT NULL REFERENCES batch(id),
    side            VARCHAR(8)     NOT NULL CHECK (side IN ('INTERNAL','EXTERNAL')),
    external_ref    VARCHAR(255),
    amount          NUMERIC(19,4)  NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    counterparty    VARCHAR(200),
    trade_date      DATE,
    value_date      DATE           NOT NULL,
    settlement_date DATE,
    direction       VARCHAR(6)     NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    raw_payload     JSONB,
    match_id        BIGINT,
    status          VARCHAR(20)    NOT NULL DEFAULT 'UNMATCHED'
                    CHECK (status IN ('UNMATCHED','MATCHED','PARTIALLY_MATCHED'))
);

-- Ground truth is isolated: engine runtime NEVER queries this table.
-- Only agent/eval.py and benchmarks/run_bench.sh may read it.
CREATE TABLE ground_truth (
    id            BIGSERIAL   PRIMARY KEY,
    batch_id      BIGINT      NOT NULL REFERENCES batch(id),
    external_ref  VARCHAR(255) NOT NULL,
    side          VARCHAR(8)  NOT NULL,
    injected_code VARCHAR(30) NOT NULL
);

CREATE INDEX idx_ctxn_batch        ON canonical_txn(batch_id);
CREATE INDEX idx_ctxn_batch_side   ON canonical_txn(batch_id, side);
CREATE INDEX idx_ctxn_status       ON canonical_txn(status);
CREATE INDEX idx_ctxn_ref          ON canonical_txn(external_ref);
CREATE INDEX idx_ctxn_batch_ref    ON canonical_txn(batch_id, external_ref);
CREATE INDEX idx_gt_batch          ON ground_truth(batch_id);
