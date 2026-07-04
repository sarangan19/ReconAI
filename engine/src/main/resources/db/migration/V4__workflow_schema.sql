-- audit_event: append-only audit log (trigger enforces immutability)
CREATE TABLE audit_event (
    id         BIGSERIAL   PRIMARY KEY,
    break_id   BIGINT      NOT NULL REFERENCES recon_break(id),
    actor      VARCHAR(10) NOT NULL CHECK (actor IN ('SYSTEM','AGENT','USER')),
    action     VARCHAR(50) NOT NULL,
    payload    JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_event_break ON audit_event (break_id);

CREATE OR REPLACE FUNCTION fn_audit_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_event rows are immutable: % on row id=%', TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION fn_audit_immutable();

-- agent_verdict: AI-generated root cause assessments
CREATE TABLE agent_verdict (
    id               BIGSERIAL    PRIMARY KEY,
    break_id         BIGINT       NOT NULL REFERENCES recon_break(id),
    root_cause_code  VARCHAR(30)  NOT NULL,
    confidence       NUMERIC(5,4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    explanation      TEXT         NOT NULL,
    suggested_action VARCHAR(50)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_verdict_break ON agent_verdict (break_id);
