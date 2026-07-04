# ReconAI — Architecture

## Component Overview

```mermaid
graph TB
    subgraph Client
        DASH[Dashboard<br/>React + Vite]
    end

    subgraph Engine["Engine (Spring Boot 3 / Java 21)"]
        API[REST API<br/>:8080]
        SIM[BatchSimulator]
        ING[Ingestion<br/>FeedIngestionService]
        MATCH[MatchingService<br/>4-pass engine]
        WF[WorkflowService<br/>state machine]
        EVAL[EvalController<br/>harness-only]
    end

    subgraph DB["PostgreSQL 16"]
        LEDGER[Ledger tables<br/>account / journal_entry / posting]
        FEED[Feed tables<br/>raw_txn / canonical_txn / batch]
        RECON[Reconciliation<br/>match_group / pass_stat / recon_break]
        AUDIT[Workflow<br/>audit_event / agent_verdict]
        GT[ground_truth]
    end

    subgraph Agent["Agent (Python / LangGraph)"]
        RUNNER[runner.py]
        GRAPH[LangGraph graph<br/>fetch_context → analyze]
        RULES[rules.py<br/>deterministic]
        LLM[Azure Responses API<br/>gpt-5.4]
        EVALPY[eval.py<br/>harness]
    end

    DASH --> API
    API --> SIM --> DB
    API --> ING --> DB
    API --> MATCH --> DB
    API --> WF --> DB
    API --> EVAL --> DB

    RUNNER --> GRAPH
    GRAPH --> RULES
    GRAPH --> LLM
    RUNNER --> API
    EVALPY --> API
    EVALPY -.->|eval only| EVAL
```

## Matching Pipeline (4-pass)

```mermaid
flowchart LR
    START([Unmatched txns]) --> P1

    P1["Pass 1 — Exact\nexternal_ref + amount\n+ currency"]
    P2["Pass 2 — Tolerance\n±0.5% amount,\nsame ref + currency"]
    P3["Pass 3 — Fuzzy\nJaro-Winkler ref ≥ 0.85,\nsame amount + currency"]
    P4["Pass 4 — Split\nsum of externals =\ninternal amount"]

    P1 -->|unmatched| P2
    P2 -->|unmatched| P3
    P3 -->|unmatched| P4
    P4 -->|still unmatched| BREAKS

    P1 -->|matched| MG1[match_group<br/>pass=1]
    P2 -->|matched| MG2[match_group<br/>pass=2]
    P3 -->|matched| MG3[match_group<br/>pass=3]
    P4 -->|matched| MG4[match_group<br/>pass=4]

    BREAKS([recon_break\nMISSING / DUP])
```

## Break Workflow State Machine

```mermaid
stateDiagram-v2
    [*] --> OPEN : break created
    OPEN --> INVESTIGATING : manual triage
    OPEN --> RESOLUTION_PROPOSED : direct proposal
    INVESTIGATING --> RESOLUTION_PROPOSED : agent verdict posted
    INVESTIGATING --> ESCALATED : needs escalation
    RESOLUTION_PROPOSED --> RESOLVED : approved
    RESOLUTION_PROPOSED --> OPEN : rejected
    ESCALATED --> INVESTIGATING : reassigned
    ESCALATED --> RESOLVED : override approved
    RESOLVED --> [*]
```

## Agent LangGraph Flow

```mermaid
graph LR
    START([__start__])
    FC[fetch_context\nGET /api/breaks/{id}/context]
    AN[analyze\nrules or LLM]
    END([__end__])

    START --> FC --> AN --> END
```

### Rules mode decision tree

```
DUP_EXTERNAL detected type OR dup_scan non-empty
  → DUP_EXTERNAL / REVERSE_DUPLICATE (conf 0.92)

Near-miss exists:
  refs differ + nm.status = MATCHED
    → DUP_EXTERNAL / REVERSE_DUPLICATE (conf 0.85)
  same ref + diff_pct > 5%
    → AMT_FAT_FINGER / MANUAL_REVIEW (conf 0.72)
  same ref + diff_pct ≤ 0.5% + same date
    → AMT_FX_ROUNDING / APPROVE_TOLERANCE (conf 0.84)
  same ref + diff_pct ≈ 0 + date_diff ≤ 2
    → DATE_TIMING / WAIT_SELF_CLEAR (conf 0.80)
  refs differ + nm.status ≠ MATCHED
    → REF_CORRUPTION / REPROCESS_WITH_CORRECT_REF (conf 0.76)

Detected type fallback:
  MISSING_EXTERNAL → CREATE_MISSING_POSTING
  MISSING_INTERNAL → CREATE_MISSING_POSTING

Default:
  → MANUAL_REVIEW (conf 0.40)
```

## Database Schema (key tables)

```
batch                canonical_txn          match_group
├── id               ├── id                 ├── id
├── status           ├── batch_id           ├── batch_id
├── n                ├── side               ├── pass_number
└── seed             ├── external_ref       └── created_at
                     ├── amount
                     ├── currency           recon_break
                     └── value_date         ├── id
                                            ├── batch_id
ground_truth                                ├── detected_type
├── batch_id                                ├── status (state machine)
├── external_ref                            └── created_at
└── injected_code
                     agent_verdict          audit_event (immutable)
                     ├── break_id           ├── break_id
                     ├── root_cause_code    ├── actor
                     ├── confidence         ├── action
                     └── explanation        └── payload (JSONB)
```
