# ReconAI

Transaction reconciliation platform with an AI break-investigation agent.

## Quickstart

**Prerequisites:** Docker, Java 21, Maven, Node 20, Python 3.11+

```bash
# Start postgres + engine
make up

# Run all tests (Java + Python + dashboard build)
make test

# Open API docs
open http://localhost:8080/swagger-ui.html

# Run dashboard in dev mode
cd dashboard && npm install && npm run dev
```

## Architecture

```
engine/      Java 21 / Spring Boot 3.3 — ledger, reconciliation, REST API
agent/       Python 3.11 / LangGraph — AI break investigator
dashboard/   React 18 / Vite / TypeScript — ops dashboard
benchmarks/  Generated metrics (not hand-written)
```

## Build Status

| Phase | Status |
|-------|--------|
| 0+1: Scaffold + Ledger | ✅ |
| 2+3: Simulator + Ingestion | 🔲 |
| 4: Matching Engine | 🔲 |
| 5: Break Workflow | 🔲 |
| 6: AI Agent | 🔲 |
| 7: Dashboard + Benchmarks | 🔲 |

## Metrics

*Generated from real runs — see `benchmarks/METRICS.md` (available after Phase 7)*
