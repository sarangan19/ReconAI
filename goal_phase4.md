# ReconAI — Phase 4: Matching Engine

## What this phase delivers
Four-pass reconciliation engine that consumes canonical_txn rows and produces match groups,
pass-level statistics, and breaks with heuristic classification. All passes complete in < 5 min
on 1M txns (500K/side); results are deterministic for the same seed.

## New schema (V3 migration)

```sql
match_group(id, batch_id, pass_num, match_type, score)    -- one row per matched group (1:1 or 1:N)
pass_stat(batch_id, pass_num, matched_count, elapsed_ms)   -- stats per pass
recon_break(id, batch_id, detected_type, detected_confidence, status, created_at, resolved_at, resolution_code)
break_txn(break_id, txn_id)                                -- which txns belong to which break
```

## Four passes

### Pass 1 — Exact (SQL hash-join)
Key: `(external_ref, amount, currency, value_date)` matching INTERNAL vs EXTERNAL.
One-to-one deduplication: for ties, pick smallest-ID partner.
Result: match_type = `EXACT`.

### Pass 2 — Tolerance (SQL)
Among remaining UNMATCHED, same `external_ref + currency`; amount within `MAX(0.5%, 0.01)`;
`value_date` within ±3 calendar days. Best pair chosen by (amt_diff ASC, date_diff ASC).
Result: match_type = `TOLERANCE_AMT` or `TOLERANCE_DATE`.

### Pass 3 — Fuzzy (SQL block + Java Jaro-Winkler)
Block by `(counterparty, currency)` with `|value_date| ≤ 3d`. Within each block compute:
```
score = 0.6 × jaro_winkler(ref_i, ref_e) + 0.25 × amount_proximity + 0.15 × date_proximity
```
Accept ≥ 0.85; greedy one-to-one by descending score.
Result: match_type = `FUZZY`.

### Pass 4 — Split settlement (Java subset-sum within blocks)
Block by `(counterparty, currency, |value_date| ≤ 3d)`. For each INTERNAL, find ≤ 3 EXTERNAL
whose amounts sum to the internal amount (exact or within 0.01). Limit search to blocks of size ≤ 50.
Result: match_type = `SPLIT_SETTLEMENT`.

## Break creation and classification

After all passes, every remaining UNMATCHED txn becomes a break:
- EXTERNAL txns with duplicate (ref, amount, currency, value_date) tuples → `DUP_EXTERNAL`
- INTERNAL without any corresponding EXTERNAL in same batch → `MISSING_EXTERNAL`
- EXTERNAL without any corresponding INTERNAL → `MISSING_INTERNAL`
- Previously near-missed in pass 2 (amount drift) → `AMT_FX_ROUNDING` or `AMT_FAT_FINGER`
- Previously near-missed in pass 3 (fuzzy ref) → `REF_CORRUPTION`

## New packages

```
com.reconai.matching   — MatchingService, JaroWinkler, ReconcileResult, PassStatDto
com.reconai.breaks     — ReconBreak, BreakRepository, BreakController
```

## REST endpoints added

```
POST /api/batches/{id}/reconcile    run all 4 passes + break creation → ReconcileResult
GET  /api/breaks?batchId=&status=&type=&page=&size=
GET  /api/breaks/{id}
GET  /api/batches/{id}/summary      updated: now includes pass stats + break counts
```

## Algorithm for multi-step pass (Java side)

```
pairs = SQL query for candidates
dedup_pairs = one-to-one deduplication in Java
n = pairs.size()
ids = INSERT INTO match_group ... SELECT FROM generate_series(1, n) RETURNING id
updates = [(ids[i], pairs[i].intId), (ids[i], pairs[i].extId)]
JDBC batchUpdate canonical_txn SET match_id=?, status='MATCHED' WHERE id=?
```

Using `generate_series` in INSERT to get back n sequential IDs in one round-trip.

## Tests

- `MatchingServiceTest` (pure unit, no DB): tests JaroWinkler, amount proximity, date proximity, dedup logic
- `MatchingIntegrationTest` (real DB):
  - simulate 1000 txns with seed=42, reconcile, assert:
    - all clean txns matched (EXACT)
    - DATE_TIMING breaks flagged correctly
    - AMT_FX_ROUNDING breaks flagged correctly
    - REF_CORRUPTION breaks detected via fuzzy pass
    - SPLIT_SETTLEMENT detected in pass 4
    - No txn appears in two match groups (exclusivity property)
    - Determinism: reconcile two batches with same seed → same break type distribution

## Acceptance criteria (from plan.md)

- [ ] Passes 1–2 are set-based SQL (no Java row loops for matching)
- [ ] No txn ever in two matches (property test)
- [ ] Determinism: same seed → same break type distribution
- [ ] break_type classification precision/recall reported (whatever the real number is)
- [ ] < 5 min wall-clock on 1M txns (500K/side) on a laptop
