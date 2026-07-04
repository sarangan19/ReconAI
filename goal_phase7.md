# ReconAI — Phase 7: Dashboard + Benchmarks + Docs

## Dashboard (React + Vite + TypeScript + recharts)
3 views, dark theme, all data from live engine API.

### View 1 — Batch Summary
- Batch ID selector
- Stats cards: total txns, matched, breaks, match rate %
- Bar chart: matched count per pass (EXACT/TOLERANCE/FUZZY/SPLIT)
- Horizontal bar: break counts by type

### View 2 — Break Queue
- Filter row: type, status dropdowns
- Paged table: id, type, confidence, status, created_at
- Click row → detail panel: txns, near-miss, audit trail, agent verdict, transition buttons

### View 3 — Agent Report
- Calls GET /api/eval/summary?batchId= (new engine endpoint)
- Accuracy card, per-type P/R/F1 table, confusion matrix (styled table with intensity colors)

## New engine endpoint
GET /api/eval/summary?batchId=  
Joins recon_break + agent_verdict + ground_truth → accuracy + confusion matrix from DB

## Benchmarks
- benchmarks/run_bench.sh  — seeds 100K batch, reconciles, runs rules eval, generates METRICS.md
- benchmarks/METRICS.md    — generated from real run data (never hand-written)

## Docs
- docs/architecture.md  — mermaid diagrams (component view, matching pipeline, agent graph)
- docs/demo-script.md   — 5-minute walkthrough

## Makefile
Complete: recon, agent-eval, bench targets

## Acceptance criteria
- [ ] Dashboard builds (npm run build) and renders 3 working views
- [ ] Break transitions work from the UI (POST /api/breaks/{id}/transition)
- [ ] Agent report shows real eval data when batch has verdicts
- [ ] make bench produces METRICS.md with real numbers
- [ ] README quickstart: make up → make seed → make recon → make agent-eval works end-to-end
