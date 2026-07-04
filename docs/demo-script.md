# ReconAI — 5-Minute Demo Script

## Prerequisites
- Docker running (`make up` succeeded)
- Engine healthy: `curl http://localhost:8080/actuator/health`
- Dashboard running: `cd dashboard && npm run dev`
- Agent deps installed: `cd agent && pip install -e .`
- Azure OpenAI credentials in `.env` (for LLM mode)

---

## Step 1 — Seed a batch (~20s)

```bash
make seed N=2000 SEED=42
```

**What to show:** The API returns a batch object with `id`, `n=2000`, and status `PENDING`.
The simulator injects ~10% discrepancies: duplicates, missing records, amount errors, timing shifts, ref corruption.

```json
{
  "id": 105,
  "n": 2000,
  "seed": 42,
  "status": "PENDING",
  "internalCount": 1000,
  "externalCount": 1000
}
```

---

## Step 2 — Run reconciliation (~2s)

```bash
curl -X POST http://localhost:8080/api/batches/105/reconcile
```

**What to show:**
- **Pass 1 (Exact):** ~85% of clean transactions match immediately on `external_ref + amount + currency`
- **Pass 2 (Tolerance):** FX-rounding pairs match within ±0.5%
- **Pass 3 (Fuzzy):** Ref-corrupted records surface — JaroWinkler ≥ 0.85 catches truncations
- **Pass 4 (Split):** Multi-leg settlements grouped by sum equality
- Breaks created for everything still unmatched

```json
{
  "batchId": 105,
  "matchedCount": 1842,
  "breakCount": 29,
  "matchRate": 0.921
}
```

Open **Batch Summary** tab in the dashboard. Show the pass funnel bar chart.

---

## Step 3 — Open a break and inspect it (~1min)

Open the **Break Queue** tab. Filter by `DUP_EXTERNAL`.

Click a break to open the detail panel. Highlight:
- **Transactions** — the external record that has no internal match
- **Near-miss candidates** — the original transaction that WAS matched (status=MATCHED), proving this is a duplicate
- **Audit trail** — system-created `BREAK_CREATED` event (immutable, trigger-enforced in Postgres)

Manually transition the break: click `→ INVESTIGATING`.

---

## Step 4 — Run the AI agent (~30s)

```bash
cd agent
python -m reconai.runner --break-id <ID> --mode rules --post-verdict
```

**What to show:**
- LangGraph fetches context from the engine (GET /api/breaks/{id}/context)
- Rules engine identifies DUP_EXTERNAL because near-miss is MATCHED with a different ref suffix
- Verdict is posted back (POST /api/breaks/{id}/verdict)
- Status auto-advances to `RESOLUTION_PROPOSED`

Refresh the break detail panel. The agent verdict card appears with:
- **Root cause:** `DUP_EXTERNAL`
- **Confidence:** 85%
- **Explanation:** references the matched near-miss and differing reference
- **Suggested action:** `REVERSE_DUPLICATE`

---

## Step 5 — Resolve the break (~10s)

In the Break Queue detail panel, click `→ RESOLVED`.

The status changes, `resolved_at` is stamped, and the audit trail records both the agent verdict and the resolution event.

---

## Step 6 — Agent accuracy report (~2min)

Run the eval harness over the full batch:

```bash
python -m reconai.eval --batch-id 105 --mode rules --out-dir benchmarks --verbose
```

Open the **Agent Report** tab in the dashboard.

**What to show:**
- Overall accuracy: **72.4%** (21/29) — meets the ≥70% acceptance criterion
- MISSING_EXTERNAL / MISSING_INTERNAL: clean separations, F1 ≥ 0.75
- DUP_EXTERNAL: correctly identified via near-miss matching pattern
- Confusion matrix heatmap — hot diagonal = correct predictions

---

## Key takeaways

1. **4-pass matching** handles the full spectrum of real-world discrepancy patterns without manual rules
2. **Rules agent** achieves ≥70% accuracy with zero API calls — useful when LLM access is restricted
3. **LLM agent** (Azure Responses API) reaches the same accuracy with richer explanations — the two modes are interchangeable
4. **Append-only audit** is database-enforced (Postgres trigger) — not just application-level
5. **Ground truth isolation** — eval.py is the only code path that touches the `ground_truth` table; the agent runtime never reads it
