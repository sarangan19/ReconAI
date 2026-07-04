# ReconAI — End-to-End Build Plan

> **Purpose of this document**: Complete implementation spec for Claude Code to build ReconAI —
> a transaction reconciliation & break-resolution platform with an AI investigator agent.
> Work through phases in order. Do not skip acceptance criteria. Keep tests green at every commit.

---

## 1. Product summary

Banks reconcile their **internal ledger** against **external statements** (custodians, payment
processors). Mismatches are called **breaks**; ops teams must find, classify, root-cause, and
resolve them. ReconAI implements the full loop:

1. A **double-entry ledger service** (Java/Spring Boot) that produces the internal feed.
2. A **feed simulator** that produces the external feed with *injected, labeled discrepancies*
   (ground truth), so all accuracy claims are measurable.
3. A **reconciliation engine**: multi-pass matching (exact → tolerance → fuzzy → one-to-many),
   break detection, classification, resolution workflow, append-only audit trail.
4. An **AI break investigator** (Python/LangGraph) that queries the system through its REST API,
   produces root-cause hypotheses with confidence + suggested actions, and is **scored against
   ground truth**.
5. A **dashboard** (React) showing the break queue, match rates, aging, and agent verdicts.
6. **Benchmarks and metrics reports** generated from real runs — never hand-written numbers.

### Headline metrics the finished project must produce (measured, not invented)
- Auto-match rate on 1M+ synthetic transactions
- Break detection recall & classification precision/recall vs injected ground truth
- Agent root-cause accuracy vs ground truth
- Ingest + matching throughput (txns/sec, wall-clock for 1M)

---

## 2. Tech stack (fixed — do not substitute without strong reason)

| Layer | Choice |
|---|---|
| Core engine | Java 21, Spring Boot 3.3+, Maven |
| Persistence | PostgreSQL 16 (Docker), Flyway migrations, Spring Data JPA + JDBC batch for bulk paths |
| Testing (Java) | JUnit 5, Testcontainers (Postgres), jqwik (property-based), Spring Boot Test |
| Agent | Python 3.11+, LangGraph, httpx, pydantic |
| LLM | Provider-agnostic via env (`ANTHROPIC_API_KEY` or `OPENAI_API_KEY`); **must also ship a deterministic rule-based fallback mode** (`AGENT_MODE=rules`) so the demo and CI run without any API key |
| Dashboard | React 18 + Vite + TypeScript, recharts, plain fetch to REST API |
| Infra | docker-compose (postgres + engine + dashboard), GitHub Actions CI, Makefile |

### Repo layout (monorepo)
```
reconai/
├── engine/           # Spring Boot app: ledger, simulator, ingestion, matching, workflow, REST
│   └── src/main/java/com/reconai/...
├── agent/            # Python LangGraph investigator + eval harness
├── dashboard/        # React + Vite
├── benchmarks/       # scripts + generated METRICS.md
├── docs/             # architecture.md (mermaid diagrams), demo-script.md
├── docker-compose.yml
├── Makefile          # make up / seed / recon / agent-eval / bench / test
└── README.md
```

---

## 3. Domain model

### 3.1 Ledger (double-entry)
- `Account(id, name, currency, type[ASSET|LIABILITY|INCOME|EXPENSE|SUSPENSE])`
- `JournalEntry(id, idempotency_key UNIQUE, description, created_at)`
- `Posting(id, journal_entry_id, account_id, amount NUMERIC(19,4), direction[DEBIT|CREDIT], currency, value_date, settlement_date, counterparty, external_ref)`
- **Invariant**: postings of a journal entry sum to zero per currency. Enforce in service layer + DB constraint via trigger or deferred check; property-test it.
- Transfer API is **idempotent**: same `idempotency_key` → same result, no double-posting. Concurrency-safe balance reads (repeatable-read or explicit locking; document the choice).

### 3.2 Canonical transaction (unit of reconciliation)
Both feeds normalize into:
```
CanonicalTxn(id, side[INTERNAL|EXTERNAL], batch_id, external_ref, amount, currency,
             counterparty, trade_date, value_date, settlement_date, direction, raw_payload JSONB,
             match_id NULLABLE, status[UNMATCHED|MATCHED|PARTIALLY_MATCHED])
```

### 3.3 Discrepancy taxonomy (injected by simulator; the classifier must predict these labels)
| Code | Meaning | Injection rate (default) |
|---|---|---|
| `DUP_EXTERNAL` | duplicated external record | 0.4% |
| `MISSING_EXTERNAL` | internal txn never settled externally | 0.5% |
| `MISSING_INTERNAL` | external record with no internal txn | 0.3% |
| `AMT_FX_ROUNDING` | small amount drift (≤0.5%, FX-style rounding) | 0.6% |
| `AMT_FAT_FINGER` | digit transposition / order-of-magnitude error | 0.2% |
| `DATE_TIMING` | settlement shifted T+1/T+2 (self-clearing) | 1.0% |
| `REF_CORRUPTION` | mangled/truncated external_ref | 0.4% |
| `SPLIT_SETTLEMENT` | one internal txn settled as 2–3 external partials | 0.3% |

Simulator writes a **ground-truth manifest** (`ground_truth(txn_id, injected_code)`) to a separate
table/file that the matching engine and agent are **forbidden to read** at runtime; only the eval
harness may read it.

### 3.4 Break & workflow
```
Break(id, batch_id, txn_ids[], detected_type, detected_confidence, status, age_days,
      created_at, resolved_at, resolution_code)
Status machine: OPEN → INVESTIGATING → RESOLUTION_PROPOSED → RESOLVED | ESCALATED
AuditEvent(id, break_id, actor[SYSTEM|AGENT|USER], action, payload JSONB, created_at)  # append-only, no UPDATE/DELETE
AgentVerdict(id, break_id, root_cause_code, confidence, explanation TEXT, suggested_action, created_at)
```

---

## 4. Matching engine spec

Run as ordered passes over a batch; each pass only sees txns still UNMATCHED.

1. **Pass 1 — exact**: key = `(external_ref, amount, currency, direction, value_date)`. Hash-join in SQL.
2. **Pass 2 — tolerance**: same ref + currency + direction; amount within `max(0.5%, 0.01)`;
   value_date within ±2 business days. Classify candidate cause (`AMT_FX_ROUNDING` / `DATE_TIMING`).
3. **Pass 3 — fuzzy**: block by `(counterparty, currency, value_date ± 3d)`; score =
   weighted(Jaro-Winkler(ref), amount proximity, date proximity); accept ≥ threshold (default 0.85),
   greedy one-to-one by descending score. Flags `REF_CORRUPTION` candidates.
4. **Pass 4 — one-to-many**: within a block, find external subsets (size ≤ 3) summing to an internal
   amount (exact or within tolerance) → `SPLIT_SETTLEMENT`.
5. **Break creation**: every remaining UNMATCHED txn → Break. Classifier assigns `detected_type`
   heuristically (duplicate detection via identical-tuple scan → `DUP_EXTERNAL`; side-based
   missing → `MISSING_EXTERNAL`/`MISSING_INTERNAL`; near-miss evidence from passes 2–3 recorded
   on the break as classification features).

**Performance requirement**: full pipeline on 1M canonical txns (500K/side) completes in
< 5 minutes on a laptop; passes 1–2 must be set-based SQL, not row-by-row Java loops.
**Determinism requirement**: same seed → identical results (fixed RNG seed everywhere).

---

## 5. REST API (engine)

```
POST /api/ledger/transfers                 (idempotent; Idempotency-Key header)
GET  /api/ledger/accounts/{id}/balance
POST /api/batches                          create recon batch
POST /api/batches/{id}/ingest?side=...     bulk ingest (NDJSON or CSV multipart)
POST /api/batches/{id}/reconcile           run matching passes
GET  /api/batches/{id}/summary             match-rate, pass stats, break counts by type
GET  /api/breaks?status=&type=&batchId=    paged
GET  /api/breaks/{id}                      full detail incl. candidate near-matches + features
GET  /api/breaks/{id}/context              purpose-built for the agent: txn(s), near-miss candidates,
                                           counterparty history, duplicate scan results
POST /api/breaks/{id}/verdict              agent posts AgentVerdict (writes audit event)
POST /api/breaks/{id}/transition           status transitions (validates state machine)
GET  /api/audit?breakId=
```
OpenAPI via springdoc; the agent consumes this spec.

---

## 6. AI agent spec (`agent/`)

LangGraph graph: `fetch_context → analyze → (optional deep-dive tool calls) → verdict → post`.

**Tools** (HTTP against engine API): `get_break_context`, `search_similar_txns`,
`get_counterparty_history`, `scan_duplicates`, `get_matching_features`.

**Output schema (pydantic, strict)**:
```python
class Verdict(BaseModel):
    root_cause_code: DiscrepancyCode      # must be one of the taxonomy codes
    confidence: float                      # 0–1
    explanation: str                       # 2–4 sentences, references concrete evidence
    suggested_action: Action               # e.g. WAIT_SELF_CLEAR, REVERSE_DUPLICATE,
                                           # CREATE_MISSING_POSTING, MANUAL_REVIEW
```

**Two modes** (env `AGENT_MODE`):
- `llm`: LLM reasons over tool results (temperature 0; structured output enforced).
- `rules`: deterministic decision tree over the same features — no API key needed. This is the CI
  baseline and also the "explainable fallback" talking point.

**Eval harness** (`agent/eval.py`): runs the agent over all breaks in a batch, joins with the
ground-truth manifest, outputs accuracy overall + per-type confusion matrix → `benchmarks/agent_eval.json`
and a markdown table. LLM mode and rules mode are both reported.

---

## 7. Dashboard spec (`dashboard/`)

Single-page app, 3 views (keep it tight, quality over quantity):
1. **Batch summary**: match-rate funnel by pass, break counts by type (bar), break aging (line).
2. **Break queue**: filterable table (status, type, confidence); row → detail drawer with txns,
   near-miss candidates, agent verdict + explanation, audit timeline; buttons to transition status.
3. **Agent report**: accuracy vs ground truth, confusion matrix heatmap (from eval output).

No auth (demo scope). Must run via `npm run dev` proxying to engine, and in docker-compose.

---

## 8. Build phases — tasks & acceptance criteria

Work strictly in order. Each phase = one or more commits; **all tests green before moving on**.

### Phase 0 — Scaffold
- Monorepo layout above; Spring Boot app boots; Vite app boots; Python package with pyproject.
- docker-compose: postgres16 + engine; Makefile targets (`up`, `test`, `seed`, `recon`, `agent-eval`, `bench`); GitHub Actions running Java tests (Testcontainers), Python tests, dashboard build.
- ✅ AC: `make up && make test` passes clean on fresh clone.

### Phase 1 — Ledger core
- Flyway V1 schema (ledger tables), entities, transfer service + API, balance query.
- Idempotency (unique key + safe retry semantics), zero-sum invariant, concurrency test
  (parallel transfers on same account, final balance exact).
- Property tests (jqwik): random posting sets always balance; idempotent replay is a no-op.
- ✅ AC: invariant unbreakable via API; concurrency test with 50 threads exact to the cent.

### Phase 2 — Feed simulator + ground truth
- Deterministic (seeded) generator: N base transactions across configurable accounts/counterparties/currencies →
  internal feed (via real ledger postings, batched) + external feed with taxonomy injections at configured rates.
- Ground-truth manifest persisted separately; CLI: `make seed N=1000000 SEED=42`.
- ✅ AC: seed=42 twice → byte-identical feeds; injection counts match configured rates ±0.05%;
  1M generation < 3 min.

### Phase 3 — Ingestion & normalization
- Bulk NDJSON/CSV ingest → CanonicalTxn (JDBC batch, chunked, ~10K/insert); idempotent re-ingest
  (batch+side re-run doesn't duplicate).
- ✅ AC: 1M txns ingest < 2 min; re-ingest idempotence test.

### Phase 4 — Matching engine
- Passes 1–4 per spec; pass stats recorded per batch; break creation + heuristic classifier.
- Unit tests per pass with handcrafted fixtures for every taxonomy code; property test:
  **no txn ever in two matches**; determinism test (same seed → same match_ids).
- ✅ AC: on seeded 1M batch — auto-match rate ≥ 97%; every injected discrepancy ends in a break or
  correct tolerant match; classification precision/recall reported per type (target ≥ 80% macro,
  report honestly whatever it is); < 5 min wall-clock.

### Phase 5 — Break workflow + audit
- Status machine with validation; append-only audit (DB rule blocking UPDATE/DELETE);
  every mutation writes an AuditEvent; `GET /breaks/{id}/context` endpoint for the agent.
- ✅ AC: illegal transitions rejected with 409; audit immutability test; context endpoint returns
  everything the agent needs in one call.

### Phase 6 — AI agent + eval
- LangGraph graph, tools, strict output schema, both modes; retries/timeouts on HTTP;
  posts verdicts (audit-logged).
- Eval harness producing `agent_eval.json` + markdown confusion matrix for both modes.
- ✅ AC: rules mode ≥ 70% accuracy in CI without any key; llm mode evaluated locally and reported;
  agent never sees ground truth at inference time (enforce by API design, assert in tests).

### Phase 7 — Dashboard, benchmarks, docs
- Three dashboard views; `benchmarks/run_bench.sh` producing `METRICS.md` (machine specs, timings,
  match rates, eval accuracies) — **generated from real runs only**.
- `docs/architecture.md` with mermaid diagrams (component + matching-pipeline + agent graph);
  `docs/demo-script.md` (5-minute walkthrough: seed → reconcile → open break → agent verdict → resolve);
  README with quickstart, screenshots, metrics table, honest limitations section.
- ✅ AC: fresh-clone quickstart works end-to-end with one seeded 100K demo batch; METRICS.md
  regenerates via `make bench`.

---

## 9. Conventions & guardrails for Claude Code

- **Never fabricate metrics.** Every number in README/METRICS.md comes from an actual run; if a
  target is missed, report the real number and note it in limitations.
- Money is `BigDecimal`/`NUMERIC(19,4)` everywhere. No floats. Property-test rounding behavior.
- All randomness seeded and logged. `SEED=42` is the canonical demo seed.
- Conventional commits (`feat:`, `test:`, `perf:`...), one logical change per commit.
- Ground-truth isolation is a hard rule: engine and agent code paths must have no read access to
  the manifest; only `agent/eval.py` and benchmark scripts may read it.
- Keep the dashboard modest; the engine and agent are the stars.
- If a dependency/setup step fails, fix forward within the chosen stack rather than swapping stacks.

## 10. Definition of done (project level)
1. `make up && make seed N=100000 && make recon && make agent-eval && make bench` succeeds on a clean machine.
2. CI green: Java tests (incl. Testcontainers), Python tests, dashboard build, rules-mode eval.
3. METRICS.md with real numbers; README with quickstart + screenshots + limitations.
4. A 5-minute demo path documented and rehearsable.

---

## Appendix A — Resume bullets this project earns (fill X/Y from METRICS.md)
- Built **ReconAI**, a transaction-reconciliation platform (Java 21/Spring Boot/PostgreSQL) that
  reconciles 1M+ transactions across internal-ledger and external-statement feeds using multi-pass
  matching (exact, tolerance, fuzzy, split-settlement), achieving **X% auto-match** in **Y min**.
- Implemented a **double-entry ledger** with idempotent transfer APIs, zero-sum invariants, and
  property-based tests guaranteeing correctness under concurrent access.
- Designed a **LangGraph break-investigation agent** that root-causes reconciliation breaks via
  tool calls against the platform API, reaching **X% accuracy** against injected ground truth, with
  a deterministic rules fallback and full audit trail.

## Appendix B — Interview talking points to be able to defend
Double-entry accounting & why zero-sum; idempotency keys vs retries; isolation levels chosen and why;
why BigDecimal; blocking strategy that makes fuzzy matching O(n) not O(n²); subset-sum bounding for
split settlements; why the agent gets tools instead of raw DB access; how ground-truth isolation
keeps the eval honest; what you'd change for production (Kafka ingest, exactly-once, maker-checker
approvals, entitlements).