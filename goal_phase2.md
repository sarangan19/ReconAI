# ReconAI — Phase 2+3: Feed Simulator + Ingestion

## What this phase delivers
- A deterministic feed simulator: given N and SEED, produces byte-identical internal+external feeds every run
- 8 discrepancy types injected at configured rates (DUP_EXTERNAL, MISSING_EXTERNAL, MISSING_INTERNAL, AMT_FX_ROUNDING, AMT_FAT_FINGER, DATE_TIMING, REF_CORRUPTION, SPLIT_SETTLEMENT)
- Ground-truth manifest written to isolated `ground_truth` table (only eval harness may read it)
- `CanonicalTxn` schema — the normalized transaction unit that both feeds normalize into
- JDBC batch ingestion of NDJSON payloads (chunked at 10K rows/batch), idempotent re-ingest
- `make seed N=... SEED=42` Makefile target

## New schema (V2 migration)

```sql
batch(id, name, status, created_at)
canonical_txn(id, batch_id, side, external_ref, amount, currency, counterparty,
              trade_date, value_date, settlement_date, direction,
              raw_payload JSONB, match_id, status)
ground_truth(id, batch_id, external_ref, side, injected_code)  -- ISOLATED
```

## New packages

```
com.reconai.recon.domain      — Batch, CanonicalTxn, TxnSide, TxnStatus, DiscrepancyCode
com.reconai.recon.repository  — BatchRepository, CanonicalTxnRepository
com.reconai.recon.api         — BatchController
com.reconai.simulator         — SimulatorService, SimulatorResult, InjectionRates
com.reconai.ingest            — IngestionService (JDBC batch)
com.reconai.groundtruth       — GroundTruth entity+repo (isolated; engine never queries this)
```

## SimulatorService algorithm

```
Random rng = new Random(seed)
baseDate = 2024-01-01

For each chunk of 10,000 base transactions (stream, never hold all in memory):
  For i in [start, end):
    ref = "TXN" + zeroPad(i, 10)          // deterministic, unique
    amount = BigDecimal from rng.nextInt   // 1.0000 – 9999.0000
    currency = CURRENCIES[rng.nextInt(5)]
    counterparty = COUNTERPARTIES[rng.nextInt(20)]
    tradeDate = baseDate + rng.nextInt(365)
    valueDate = tradeDate + 1 business day
    settlementDate = valueDate + 1

    discrepancy = rollDiscrepancy(rng)     // single roll, cascaded rates

    if discrepancy != MISSING_INTERNAL:
      internalRows.add(...)

    switch discrepancy:
      MISSING_EXTERNAL  → no external; groundTruth += (ref, INTERNAL, MISSING_EXTERNAL)
      DUP_EXTERNAL      → external(ref) + external(ref+"_D"); groundTruth += (ref+"_D", ...)
      AMT_FX_ROUNDING   → external with amount *= (1 ± rng*0.005)
      AMT_FAT_FINGER    → external with amount digit transposed
      DATE_TIMING       → external with settlementDate + 1 or 2
      REF_CORRUPTION    → external with ref truncated or char-swapped
      SPLIT_SETTLEMENT  → 2-3 external partials summing to total
      MISSING_INTERNAL  → external(ref); no internal; groundTruth += (ref, EXTERNAL, ...)
      null (clean)      → external(ref) matching internal exactly

  JDBC batchUpdate(internalRows, 10K chunk)
  JDBC batchUpdate(externalRows, 10K chunk)
  JDBC batchUpdate(groundTruthRows, chunk)
```

## Injection rates (from plan.md)

| Code | Rate |
|------|------|
| DUP_EXTERNAL | 0.4% |
| MISSING_EXTERNAL | 0.5% |
| MISSING_INTERNAL | 0.3% |
| AMT_FX_ROUNDING | 0.6% |
| AMT_FAT_FINGER | 0.2% |
| DATE_TIMING | 1.0% |
| REF_CORRUPTION | 0.4% |
| SPLIT_SETTLEMENT | 0.3% |
| **Total discrepancies** | **~3.7%** |

## REST endpoints

```
POST /api/batches                                create batch
POST /api/batches/simulate?n=100000&seed=42      run simulator (creates+ingests)
POST /api/batches/{id}/ingest?side=INTERNAL      NDJSON ingest
GET  /api/batches/{id}/summary                   counts, rates
```

## Makefile

```makefile
seed:
    curl -s -X POST "http://localhost:8080/api/batches/simulate?n=$(N)&seed=$(SEED)" | python3 -m json.tool
N ?= 10000
SEED ?= 42
```

## Acceptance criteria

- [ ] `make seed SEED=42` twice → same batch counts (deterministic)
- [ ] Injection counts per type ±0.05% of configured rate across 1M txns
- [ ] 1M generation+ingest < 3 min on a laptop
- [ ] Re-calling simulate with same batch_id is idempotent (HTTP 409 if already seeded)
- [ ] Ground-truth table exists but no engine code path reads it at runtime

## Tests

- `SimulatorServiceTest`: determinism (same seed = same counts), injection rate accuracy (±0.2% tolerance on small N), MISSING_INTERNAL/EXTERNAL symmetry
- `IngestionServiceTest`: NDJSON round-trip, idempotence (second ingest = same row count)
- Integration test: simulate 1000 txns, assert canonical_txn count and ground_truth isolation
