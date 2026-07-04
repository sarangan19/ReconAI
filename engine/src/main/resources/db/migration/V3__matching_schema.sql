-- Match groups: one row per logical match (1 internal : 1 external, or 1 internal : N external for splits)
CREATE TABLE match_group (
    id          BIGSERIAL   PRIMARY KEY,
    batch_id    BIGINT      NOT NULL REFERENCES batch(id),
    pass_num    SMALLINT    NOT NULL,
    match_type  VARCHAR(30) NOT NULL,
    score       NUMERIC(5,4)
);

-- Per-pass statistics recorded after each pass
CREATE TABLE pass_stat (
    batch_id       BIGINT   NOT NULL REFERENCES batch(id),
    pass_num       SMALLINT NOT NULL,
    matched_count  INTEGER  NOT NULL DEFAULT 0,
    elapsed_ms     INTEGER  NOT NULL,
    PRIMARY KEY (batch_id, pass_num)
);

-- Breaks: one row per unresolved discrepancy
CREATE TABLE recon_break (
    id                   BIGSERIAL    PRIMARY KEY,
    batch_id             BIGINT       NOT NULL REFERENCES batch(id),
    detected_type        VARCHAR(30),
    detected_confidence  NUMERIC(5,4),
    status               VARCHAR(30)  NOT NULL DEFAULT 'OPEN'
                         CHECK (status IN ('OPEN','INVESTIGATING','RESOLUTION_PROPOSED','RESOLVED','ESCALATED')),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at          TIMESTAMPTZ,
    resolution_code      VARCHAR(50)
);

-- Which canonical_txn rows belong to which break
CREATE TABLE break_txn (
    break_id  BIGINT NOT NULL REFERENCES recon_break(id),
    txn_id    BIGINT NOT NULL REFERENCES canonical_txn(id),
    PRIMARY KEY (break_id, txn_id)
);

CREATE INDEX idx_match_group_batch ON match_group(batch_id);
CREATE INDEX idx_pass_stat_batch   ON pass_stat(batch_id);
CREATE INDEX idx_break_batch       ON recon_break(batch_id);
CREATE INDEX idx_break_status      ON recon_break(status);
CREATE INDEX idx_break_type        ON recon_break(detected_type);
CREATE INDEX idx_break_txn_break   ON break_txn(break_id);
CREATE INDEX idx_break_txn_txn     ON break_txn(txn_id);
