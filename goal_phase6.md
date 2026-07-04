# ReconAI — Phase 6: AI Agent + Eval

## What this phase delivers
- LangGraph break-investigation agent with two modes
- `rules`: deterministic decision tree, no API key, CI baseline (≥70% accuracy target)
- `llm`: Azure OpenAI via AzureChatOpenAI, structured output enforced (Verdict pydantic schema)
- Eval harness: runs agent over all breaks in a batch, joins with ground truth via dedicated
  eval-only REST endpoint, outputs accuracy + per-type confusion matrix
- Agent never sees ground_truth table at inference time (enforced by API design)

## Graph (LangGraph)
```
START → fetch_context → analyze → END
```
- `fetch_context`: GET /api/breaks/{id}/context
- `analyze`: rules classifier OR LLM with structured output

## Verdict schema (pydantic, strict)
```python
class Verdict(BaseModel):
    root_cause_code: DiscrepancyCode   # 8-value enum
    confidence: float                   # 0–1
    explanation: str                    # 2–4 sentences
    suggested_action: Action            # enum
```

## Rules classifier logic
1. DUP_EXTERNAL break OR non-empty dup_scan → DUP_EXTERNAL / REVERSE_DUPLICATE
2. MISSING_EXTERNAL + near-miss:
   - amt_diff_pct ≤ 0.5% AND same date → AMT_FX_ROUNDING / APPROVE_TOLERANCE
   - same amount AND date_diff ≤ 2 days → DATE_TIMING / WAIT_SELF_CLEAR
   - refs differ → REF_CORRUPTION / REPROCESS_WITH_CORRECT_REF
3. MISSING_EXTERNAL + no near-miss → MISSING_EXTERNAL / CREATE_MISSING_POSTING
4. MISSING_INTERNAL → MISSING_INTERNAL / CREATE_MISSING_POSTING

## New engine endpoint (eval-only)
GET /api/eval/ground-truth?batchId=  — returns {break_id: injected_code}
via JOIN recon_break → break_txn → canonical_txn → ground_truth

## File structure
```
agent/
├── reconai/
│   ├── models.py      # Verdict, DiscrepancyCode, Action (pydantic)
│   ├── tools.py       # EngineClient (httpx)
│   ├── rules.py       # deterministic classifier
│   ├── agent.py       # LangGraph graph (both modes)
│   ├── runner.py      # run_agent(break_id, mode) → Verdict
│   └── eval.py        # eval_batch(batch_id, mode) → metrics JSON
├── tests/
│   ├── test_models.py
│   ├── test_rules.py
│   └── test_runner.py
└── pyproject.toml     (updated with langchain-openai, langgraph, httpx, python-dotenv)
```

## Acceptance criteria
- [ ] rules mode ≥70% accuracy on any seeded batch
- [ ] llm mode produces valid Verdict JSON (structured output enforced)
- [ ] agent never reads ground_truth at inference time
- [ ] eval harness outputs agent_eval.json + prints confusion matrix
- [ ] all Python tests pass without API key (rules mode only in tests)
