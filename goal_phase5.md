# ReconAI — Phase 5: Break Workflow + Audit

## What this phase delivers
- Break status machine with server-side transition validation (invalid moves → 409)
- Append-only `audit_event` table enforced by PostgreSQL trigger (UPDATE/DELETE raise exception)
- `agent_verdict` table populated by the agent in Phase 6
- `GET /api/breaks/{id}/context` — single call returning everything the agent needs
- `POST /api/breaks/{id}/transition` — validated status transitions + audit write
- `POST /api/breaks/{id}/verdict` — agent posts verdict + audit write

## State machine

```
         [OPEN] ──────────────────────┐
           │                          │
           ▼                          │
    [INVESTIGATING] ──► [ESCALATED]   │
           │                │         │
           ▼                ▼         │
  [RESOLUTION_PROPOSED] ◄───┘         │
     │         │                      │
     ▼         └───────► [OPEN] ──────┘
  [RESOLVED]
```

Valid transitions:
- OPEN → INVESTIGATING | RESOLUTION_PROPOSED
- INVESTIGATING → RESOLUTION_PROPOSED | ESCALATED
- RESOLUTION_PROPOSED → RESOLVED | OPEN
- ESCALATED → INVESTIGATING | RESOLVED

## New schema (V4 migration)

```sql
audit_event(id, break_id, actor[SYSTEM|AGENT|USER], action, payload JSONB, created_at)
  -- immutable: trigger raises exception on UPDATE or DELETE

agent_verdict(id, break_id, root_cause_code, confidence, explanation, suggested_action, created_at)
```

## Context endpoint response shape

```json
{
  "break": { id, batchId, detectedType, detectedConfidence, status, createdAt },
  "transactions": [ full canonical_txn rows for this break ],
  "nearMissCandidates": [ top-5 other-side txns closest in amount+date, same counterparty+currency ],
  "counterpartyHistory": [ other txns from same counterparty in this batch (matched or not), limit 10 ],
  "duplicateScan": [ external txns with same (ref, amount, currency) — populated for DUP_EXTERNAL ],
  "auditTrail": [ audit events for this break in chronological order ],
  "agentVerdicts": [ any prior agent verdicts ]
}
```

## Packages

```
com.reconai.workflow   — WorkflowService, BreakContext, VerdictRequest, TransitionRequest
```
(BreakController extended with 3 new endpoints)

## Audit immutability (DB-level enforcement)

```sql
CREATE OR REPLACE FUNCTION fn_audit_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_event rows are immutable: % on row %', TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION fn_audit_immutable();
```

## Tests

- `WorkflowServiceTest` (unit): transition map coverage, illegal transitions throw exception
- `WorkflowIntegrationTest` (real DB):
  - Simulate → reconcile → transition OPEN→INVESTIGATING → audit event written
  - Illegal transition OPEN→RESOLVED → 409
  - Audit immutability: direct UPDATE → exception from trigger
  - Context endpoint: all expected fields present for each break type
  - Verdict posted → audit event written, verdict retrievable

## Acceptance criteria

- [ ] All valid state transitions succeed; all invalid ones return 409
- [ ] `audit_event` UPDATE/DELETE rejected by DB trigger
- [ ] `GET /breaks/{id}/context` returns transactions + near-miss candidates + audit trail
- [ ] Verdict post writes both `agent_verdict` row and audit event
- [ ] No existing tests broken
