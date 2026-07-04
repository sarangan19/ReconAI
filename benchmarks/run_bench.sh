#!/usr/bin/env bash
# Runs the full pipeline benchmark and generates METRICS.md.
# Usage: ./benchmarks/run_bench.sh [N] [SEED]
#   N    = transaction count (default 10000)
#   SEED = random seed (default 42)
# Requires: engine running on $ENGINE_URL, agent deps installed.

set -euo pipefail

N="${1:-10000}"
SEED="${2:-42}"
ENGINE_URL="${ENGINE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
OUT="$SCRIPT_DIR/METRICS.md"

echo "=== ReconAI benchmark: N=$N seed=$SEED ==="

# --- 1. Seed ---
echo "[1/4] Seeding $N transactions (seed=$SEED)..."
SEED_OUT=$(curl -s -X POST "$ENGINE_URL/api/batches/simulate?n=$N&seed=$SEED" \
  -H "Content-Type: application/json")
BATCH_ID=$(echo "$SEED_OUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['id'])")
echo "      batch_id=$BATCH_ID"

# --- 2. Reconcile ---
echo "[2/4] Running reconciliation..."
T_RECON_START=$(date +%s%3N)
RECON_OUT=$(curl -s -X POST "$ENGINE_URL/api/batches/$BATCH_ID/reconcile" \
  -H "Content-Type: application/json")
T_RECON_END=$(date +%s%3N)
RECON_MS=$(( T_RECON_END - T_RECON_START ))
MATCHED=$(echo "$RECON_OUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('matchedCount',0))")
BREAKS=$(echo "$RECON_OUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('breakCount',0))")
echo "      matched=$MATCHED breaks=$BREAKS elapsed=${RECON_MS}ms"

# --- 3. Agent eval (rules) ---
echo "[3/4] Running agent eval (rules mode)..."
cd "$REPO_ROOT/agent"
T_EVAL_START=$(date +%s%3N)
python3 -m reconai.eval \
  --batch-id "$BATCH_ID" \
  --mode rules \
  --out-dir "$SCRIPT_DIR" \
  --verbose 2>&1 | tail -20
T_EVAL_END=$(date +%s%3N)
EVAL_MS=$(( T_EVAL_END - T_EVAL_START ))

# Parse accuracy from JSON
RULES_ACCURACY=$(python3 -c "
import json, sys
with open('$SCRIPT_DIR/agent_eval_rules.json') as f:
    d = json.load(f)
print(f\"{d['accuracy']*100:.1f}%  ({d['correct']}/{d['total_evaluated']})\")
")

# --- 4. Capture system info ---
echo "[4/4] Capturing system info..."
if command -v nproc &>/dev/null; then CPUS=$(nproc); else CPUS="N/A"; fi
MEM=$(python3 -c "
import os
try:
    import psutil
    print(f'{psutil.virtual_memory().total // (1024**3)} GB')
except ImportError:
    print('N/A')
")

MATCH_RATE=$(python3 -c "print(f'{int($MATCHED)/int($N)*100:.1f}%')")

# --- Write METRICS.md ---
cat > "$OUT" <<EOF
# ReconAI — Benchmark Metrics

Generated: $(date -u '+%Y-%m-%d %H:%M UTC')
Commit: $(cd "$REPO_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo "N/A")

## Environment

| Item | Value |
|------|-------|
| CPUs | $CPUS |
| Memory | $MEM |
| Engine URL | $ENGINE_URL |
| Java | $(java -version 2>&1 | head -1 | awk -F '"' '{print $2}') |
| Python | $(python3 --version 2>&1 | awk '{print $2}') |

## Test Parameters

| Parameter | Value |
|-----------|-------|
| Transactions (N) | $N |
| Seed | $SEED |
| Batch ID | $BATCH_ID |

## Reconciliation Performance

| Metric | Value |
|--------|-------|
| Matched | $MATCHED / $N |
| Match rate | $MATCH_RATE |
| Breaks created | $BREAKS |
| Recon elapsed | ${RECON_MS} ms |
| Throughput | $(python3 -c "print(f'{int($N) / (int($RECON_MS)/1000):.0f} txn/s')") |

## Agent Eval — Rules Mode

| Metric | Value |
|--------|-------|
| Accuracy | $RULES_ACCURACY |
| Eval elapsed | ${EVAL_MS} ms |

### Per-type breakdown (rules)

$(python3 -c "
import json
with open('$SCRIPT_DIR/agent_eval_rules.json') as f:
    d = json.load(f)
print('| Type | Support | Precision | Recall | F1 |')
print('|------|---------|-----------|--------|----|')
for code, m in sorted(d['per_type'].items()):
    print(f\"| {code} | {m['support']} | {m['precision']:.3f} | {m['recall']:.3f} | {m['f1']:.3f} |\")
")

## Reproducibility

\`\`\`bash
make seed N=$N SEED=$SEED
make recon BATCH_ID=$BATCH_ID
make agent-eval BATCH_ID=$BATCH_ID MODE=rules
\`\`\`
EOF

echo ""
echo "=== METRICS.md written to $OUT ==="
cat "$OUT"
