"""
Eval harness — runs the agent over every break in a batch and scores against ground truth.

IMPORTANT: This module is the ONLY place that reads the ground_truth table (via the
/api/eval/ground-truth endpoint). runner.py and agent.py must never call get_ground_truth().
"""
import json
import os
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

from .runner import run_agent
from .tools import EngineClient


def eval_batch(
    batch_id: int,
    *,
    mode: str = "rules",
    engine_url: str | None = None,
    out_dir: str | None = None,
    verbose: bool = False,
) -> dict[str, Any]:
    url = engine_url or os.environ.get("ENGINE_URL", "http://localhost:8080")

    with EngineClient(url) as client:
        # Fetch ground truth FIRST (eval harness privilege)
        ground_truth: dict[int, str] = client.get_ground_truth(batch_id)

        # Fetch all breaks for this batch
        all_breaks: list[dict] = []
        page = 0
        while True:
            resp = client.get_breaks(batch_id, page=page, size=200)
            all_breaks.extend(resp.get("content", []))
            if resp.get("last", True):
                break
            page += 1

    if not ground_truth:
        print("No ground-truth labels for this batch — nothing to evaluate.", file=sys.stderr)
        return {}

    results: list[dict] = []
    errors  = 0

    for brk in all_breaks:
        bid = brk["id"]
        true_code = ground_truth.get(bid)
        if true_code is None:
            continue  # clean txn, not labelled

        try:
            verdict = run_agent(bid, mode=mode, engine_url=url, post_verdict=False)
            predicted = verdict.root_cause_code.value
            correct   = predicted == true_code
            results.append({
                "break_id":  bid,
                "predicted": predicted,
                "actual":    true_code,
                "correct":   correct,
                "confidence": verdict.confidence,
            })
            if verbose:
                mark = "OK" if correct else "XX"
                print(f"  [{mark}] break={bid}  pred={predicted}  true={true_code}")
        except Exception as e:
            errors += 1
            if verbose:
                print(f"  ERROR break={bid}: {e}", file=sys.stderr)

    if not results:
        print("No labelled breaks found to evaluate.", file=sys.stderr)
        return {}

    total   = len(results)
    correct = sum(1 for r in results if r["correct"])
    accuracy = correct / total

    # Per-type precision / recall
    per_type_tp: dict[str, int] = defaultdict(int)
    per_type_pred: dict[str, int] = defaultdict(int)
    per_type_actual: dict[str, int] = defaultdict(int)

    for r in results:
        per_type_actual[r["actual"]] += 1
        per_type_pred[r["predicted"]] += 1
        if r["correct"]:
            per_type_tp[r["actual"]] += 1

    codes = sorted(set(per_type_actual) | set(per_type_pred))
    per_type: dict[str, dict] = {}
    for code in codes:
        tp  = per_type_tp.get(code, 0)
        fp  = per_type_pred.get(code, 0) - tp
        fn  = per_type_actual.get(code, 0) - tp
        prec = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        rec  = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1   = 2 * prec * rec / (prec + rec) if (prec + rec) > 0 else 0.0
        per_type[code] = {"support": per_type_actual.get(code, 0),
                          "precision": round(prec, 3), "recall": round(rec, 3),
                          "f1": round(f1, 3)}

    # Confusion matrix rows=actual, cols=predicted
    all_codes = sorted(set(r["actual"] for r in results) | set(r["predicted"] for r in results))
    matrix: dict[str, dict[str, int]] = {c: {c2: 0 for c2 in all_codes} for c in all_codes}
    for r in results:
        matrix[r["actual"]][r["predicted"]] += 1

    metrics = {
        "batch_id": batch_id,
        "mode":     mode,
        "total_evaluated": total,
        "correct":  correct,
        "errors":   errors,
        "accuracy": round(accuracy, 4),
        "per_type": per_type,
        "confusion_matrix": matrix,
        "results": results,
    }

    # Print summary
    print(f"\n{'='*60}")
    print(f"Eval  batch={batch_id}  mode={mode}")
    print(f"Accuracy: {accuracy*100:.1f}%  ({correct}/{total})")
    if errors:
        print(f"Errors:   {errors}")
    print(f"\nPer-type breakdown:")
    header = f"  {'Type':<22}  {'Supp':>5}  {'Prec':>6}  {'Rec':>6}  {'F1':>6}"
    print(header)
    print("  " + "-" * (len(header) - 2))
    for code, m in sorted(per_type.items()):
        print(f"  {code:<22}  {m['support']:>5}  {m['precision']:>6.3f}  {m['recall']:>6.3f}  {m['f1']:>6.3f}")
    print(f"{'='*60}\n")

    # Save JSON
    if out_dir:
        Path(out_dir).mkdir(parents=True, exist_ok=True)
        out_path = Path(out_dir) / f"agent_eval_{mode}.json"
        out_path.write_text(json.dumps(metrics, indent=2, default=str))
        print(f"Saved -> {out_path}")

    return metrics


if __name__ == "__main__":
    import argparse
    from dotenv import load_dotenv
    load_dotenv()

    p = argparse.ArgumentParser()
    p.add_argument("--batch-id", type=int, required=True)
    p.add_argument("--mode",     default=os.environ.get("AGENT_MODE", "rules"))
    p.add_argument("--out-dir",  default="benchmarks")
    p.add_argument("--verbose",  action="store_true")
    args = p.parse_args()

    eval_batch(args.batch_id, mode=args.mode, out_dir=args.out_dir, verbose=args.verbose)
