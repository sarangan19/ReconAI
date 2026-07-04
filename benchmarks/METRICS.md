# ReconAI — Benchmark Metrics

Generated from batch=105, N=2000, seed=42

## Reconciliation Performance

| Metric | Value |
|--------|-------|
| Batch ID | 105 |
| Total transactions | 2,000 |
| Match rate | ~97.1% |
| Breaks created | 29 labelled |

## Agent Eval — Rules Mode

| Metric | Value |
|--------|-------|
| Accuracy | 72.4% (21/29) |
| Errors | 0 |

### Per-type breakdown (rules)

| Type | Support | Precision | Recall | F1 |
|------|---------|-----------|--------|----|
| AMT_FAT_FINGER | 2 | 1.000 | 1.000 | 1.000 |
| DUP_EXTERNAL | 8 | 0.500 | 1.000 | 0.667 |
| MISSING_EXTERNAL | 9 | 1.000 | 0.556 | 0.714 |
| MISSING_INTERNAL | 10 | 1.000 | 0.600 | 0.750 |

### Confusion matrix (rules)

| Actual \ Pred | AMT_FAT_FINGER | DUP_EXTERNAL | MISSING_EXTERNAL | MISSING_INTERNAL |
|---|---|---|---|---|
| AMT_FAT_FINGER | 2 | 0 | 0 | 0 |
| DUP_EXTERNAL | 0 | 8 | 0 | 0 |
| MISSING_EXTERNAL | 0 | 4 | 5 | 0 |
| MISSING_INTERNAL | 0 | 4 | 0 | 6 |

## Agent Eval — LLM Mode (Azure Responses API)

| Metric | Value |
|--------|-------|
| Accuracy | 72.4% (21/29) |
| Errors | 0 |

### Per-type breakdown (llm)

| Type | Support | Precision | Recall | F1 |
|------|---------|-----------|--------|----|
| AMT_FAT_FINGER | 2 | 1.000 | 1.000 | 1.000 |
| DUP_EXTERNAL | 8 | 0.000 | 0.000 | 0.000 |
| MISSING_EXTERNAL | 9 | 1.000 | 1.000 | 1.000 |
| MISSING_INTERNAL | 10 | 0.909 | 1.000 | 0.952 |
| REF_CORRUPTION | 0 | 0.000 | 0.000 | 0.000 |

### Confusion matrix (llm)

| Actual \ Pred | AMT_FAT_FINGER | DUP_EXTERNAL | MISSING_EXTERNAL | MISSING_INTERNAL | REF_CORRUPTION |
|---|---|---|---|---|---|
| AMT_FAT_FINGER | 2 | 0 | 0 | 0 | 0 |
| DUP_EXTERNAL | 0 | 0 | 0 | 1 | 7 |
| MISSING_EXTERNAL | 0 | 0 | 9 | 0 | 0 |
| MISSING_INTERNAL | 0 | 0 | 0 | 10 | 0 |
| REF_CORRUPTION | 0 | 0 | 0 | 0 | 0 |

## Notes

- Both modes meet >=70% accuracy acceptance criterion
- Rules mode: DUP_EXTERNAL 100% recall with zero API calls
- LLM mode: MISSING_EXTERNAL + MISSING_INTERNAL 100% recall
- Ground truth isolation: eval.py is the only code path reading ground_truth; agent runtime never calls /api/eval/ground-truth
