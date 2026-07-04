# ReconAI

Production-grade transaction reconciliation platform with a 4-pass matching engine and an AI break-investigation agent (rules + LLM modes).

## Quickstart

**Prerequisites:** Docker, Java 21, Maven, Node 20, Python 3.11+

```bash
# 1. Start postgres + engine
make up

# 2. Run all tests (Java + Python + dashboard build)
make test

# 3. Seed a demo batch (2000 transactions, 10% injected discrepancies)
make seed N=2000 SEED=42
# → returns {"id": 105, ...}

# 4. Reconcile it
make recon BATCH_ID=105

# 5. Run AI agent eval (rules mode, no API key needed)
make agent-eval BATCH_ID=105 MODE=rules

# 6. Run the dashboard
cd dashboard && npm run dev
# → http://localhost:5173
```

## Architecture

```
engine/      Java 21 / Spring Boot 3.3 — ledger, 4-pass matching engine, break workflow, REST API
agent/       Python 3.11 / LangGraph  — AI break investigator (rules + Azure OpenAI LLM modes)
dashboard/   React 18 / Vite / TypeScript + recharts — 3-view ops dashboard
benchmarks/  Generated metrics (METRICS.md, agent_eval_*.json) — never hand-written
docs/        Architecture diagrams, demo script
```

See [docs/architecture.md](docs/architecture.md) for Mermaid diagrams (component view, matching pipeline, agent LangGraph flow, state machine).

## Matching Engine (4-pass)

| Pass | Strategy | What it catches |
|------|----------|-----------------|
| 1 | Exact | `external_ref + amount + currency` — clean matches |
| 2 | Tolerance | ±0.5% amount with same ref — FX rounding |
| 3 | Fuzzy | JaroWinkler(ref) ≥ 0.85 — reference corruption/truncation |
| 4 | Split-settlement | Sum of external legs = internal amount |

## Break Workflow States

`OPEN → INVESTIGATING → RESOLUTION_PROPOSED → RESOLVED`  
`INVESTIGATING → ESCALATED → RESOLVED`

Transitions are validated by the engine; the audit log is append-only (Postgres trigger).

## AI Agent

Two interchangeable modes selected via `AGENT_MODE` in `.env`:

| Mode | Accuracy (batch 105, n=29) | API key required |
|------|---------------------------|-----------------|
| `rules` | **72.4%** (21/29) | No |
| `llm` | **72.4%** (21/29) | Yes (Azure Responses API) |

Rules mode: deterministic decision tree over break context — DUP detection via near-miss MATCHED status, fat-finger via >5% amount diff, FX rounding via ≤0.5%, date timing via shifted value_date, ref corruption via differing refs + UNMATCHED near-miss.

LLM mode: Azure Responses API with JSON output format.

## Key Accuracy Results (rules mode, batch 105)

| Type | Support | Precision | Recall | F1 |
|------|---------|-----------|--------|----|
| AMT_FAT_FINGER | 2 | 1.000 | 1.000 | 1.000 |
| DUP_EXTERNAL | 8 | 0.500 | 1.000 | 0.667 |
| MISSING_EXTERNAL | 9 | 1.000 | 0.556 | 0.714 |
| MISSING_INTERNAL | 10 | 1.000 | 0.600 | 0.750 |

Full metrics: `benchmarks/METRICS.md` — regenerate with `make bench N=2000 SEED=42`.

## Build Status

| Phase | Status |
|-------|--------|
| 0+1: Scaffold + Ledger | Done |
| 2+3: Simulator + Ingestion | Done |
| 4: 4-pass Matching Engine | Done |
| 5: Break Workflow + Audit | Done |
| 6: AI Agent + Eval Harness | Done |
| 7: Dashboard + Benchmarks + Docs | Done |

## Limitations

- Eval accuracy of 72.4% is on a 29-break sample (small n). Wider batch sizes will give more stable estimates.
- LLM mode DUP_EXTERNAL recall is 0% — the rules engine outperforms the LLM on pattern-based duplicates; use `MODE=rules` for DUP-heavy batches.
- The `ground_truth` table and `/api/eval/ground-truth` endpoint exist only for benchmarking; production deployments should remove them.
- No authentication on the REST API — add Spring Security before exposing externally.

## Environment Variables

Copy `.env.example` to `.env` and fill in your values:

```
AZURE_OPENAI_ENDPOINT=https://<resource>.cognitiveservices.azure.com/openai/responses?api-version=...
AZURE_OPENAI_API_KEY=<key>
AZURE_OPENAI_DEPLOYMENT=<deployment-name>
AZURE_OPENAI_API_VERSION=<version>
AGENT_MODE=rules
ENGINE_URL=http://localhost:8080
```

`.env` is gitignored and must never be committed.
