"""Deterministic rule-based break classifier. No LLM, no API key required."""
from datetime import date
from typing import Any

from .models import Action, DiscrepancyCode, Verdict

_AMT_TOL     = 0.005   # 0.5% FX-rounding threshold
_DATE_TOL    = 2       # calendar days for timing breaks
_FAT_THRESH  = 0.05    # >5% amount diff with same ref → fat-finger


def _parse_date(v: Any) -> date | None:
    if v is None:
        return None
    try:
        return date.fromisoformat(str(v)[:10])
    except ValueError:
        return None


def _amt_diff_pct(a: float, b: float) -> float:
    denom = max(abs(a), abs(b), 1e-9)
    return abs(a - b) / denom


def classify(context: dict[str, Any]) -> Verdict:
    brk      = context["break"]
    txns     = context.get("transactions", [])
    near     = context.get("nearMissCandidates", [])
    dup_scan = context.get("duplicateScan", [])

    detected  = brk.get("detectedType", "")
    base_conf = float(brk.get("detectedConfidence") or 0.75)

    # ── Rule 1: explicit duplicate scan hit (dup_scan is non-empty) ───────
    if detected == "DUP_EXTERNAL" or dup_scan:
        conf = 0.92 if detected == "DUP_EXTERNAL" else 0.75
        return Verdict(
            root_cause_code=DiscrepancyCode.DUP_EXTERNAL,
            confidence=conf,
            explanation=(
                "The external record is a duplicate. "
                "An identical transaction (same reference, amount, and currency) "
                "already exists in the external feed. "
                "The duplicate should be reversed to restore balance."
            ),
            suggested_action=Action.REVERSE_DUPLICATE,
        )

    # ── Rule 2: near-miss analysis ────────────────────────────────────────
    if txns and near:
        brk_txn   = txns[0]
        nm        = near[0]   # closest candidate

        brk_amt   = float(brk_txn.get("amount") or 0)
        nm_amt    = float(nm.get("amount") or 0)
        diff_pct  = _amt_diff_pct(brk_amt, nm_amt)

        brk_date  = _parse_date(brk_txn.get("value_date"))
        nm_date   = _parse_date(nm.get("value_date"))
        date_diff = abs((brk_date - nm_date).days) if brk_date and nm_date else 999

        brk_ref   = str(brk_txn.get("external_ref") or "")
        nm_ref    = str(nm.get("external_ref") or "")
        refs_match = brk_ref == nm_ref

        # Status of near-miss distinguishes DUP from REF_CORRUPTION
        nm_status = str(nm.get("status") or "").upper()
        nm_matched = nm_status == "MATCHED"

        # Sub-rule A: refs differ + near-miss already MATCHED → duplicate external
        # (original txn was matched; this unmatched external is the duplicate)
        if not refs_match and brk_ref and nm_ref and nm_matched:
            return Verdict(
                root_cause_code=DiscrepancyCode.DUP_EXTERNAL,
                confidence=0.85,
                explanation=(
                    f"A near-match exists with a similar amount and same counterparty, "
                    f"but the external reference differs ('{brk_ref}' vs '{nm_ref}') "
                    f"and the counterpart transaction is already matched. "
                    "This is consistent with a duplicate external record where the original "
                    "was correctly settled."
                ),
                suggested_action=Action.REVERSE_DUPLICATE,
            )

        # Sub-rule B: same ref, large amount diff → fat-finger / transposition
        if refs_match and diff_pct > _FAT_THRESH:
            return Verdict(
                root_cause_code=DiscrepancyCode.AMT_FAT_FINGER,
                confidence=0.72,
                explanation=(
                    f"A near-match exists with the same reference but a large amount "
                    f"difference of {diff_pct * 100:.1f}%. "
                    "This is consistent with a digit transposition or order-of-magnitude "
                    "entry error. Manual review is required to determine the correct amount."
                ),
                suggested_action=Action.MANUAL_REVIEW,
            )

        # Sub-rule C: same ref, tiny amount diff, same date → FX rounding
        if refs_match and diff_pct <= _AMT_TOL and date_diff == 0:
            return Verdict(
                root_cause_code=DiscrepancyCode.AMT_FX_ROUNDING,
                confidence=0.84,
                explanation=(
                    f"A near-match exists with an amount difference of "
                    f"{diff_pct * 100:.3f}% on the same value date. "
                    "This is consistent with FX conversion rounding. "
                    "The difference is within the 0.5% tolerance threshold."
                ),
                suggested_action=Action.APPROVE_TOLERANCE,
            )

        # Sub-rule D: same ref, same amount, date shifted → timing
        if refs_match and diff_pct < 1e-6 and date_diff <= _DATE_TOL:
            return Verdict(
                root_cause_code=DiscrepancyCode.DATE_TIMING,
                confidence=0.80,
                explanation=(
                    f"A near-match exists with identical amount but value date "
                    f"shifted by {date_diff} day(s). "
                    "This is consistent with a T+1 or T+2 settlement timing difference. "
                    "The break should self-clear once the counterparty settles."
                ),
                suggested_action=Action.WAIT_SELF_CLEAR,
            )

        # Sub-rule E: refs differ, near-miss UNMATCHED → ref corruption
        if not refs_match and brk_ref and nm_ref and not nm_matched:
            return Verdict(
                root_cause_code=DiscrepancyCode.REF_CORRUPTION,
                confidence=0.76,
                explanation=(
                    f"A near-match exists (same counterparty, currency, amount) "
                    f"but the external reference differs: '{brk_ref}' vs '{nm_ref}'. "
                    "This is consistent with reference corruption or truncation in the external feed. "
                    "Reprocess the external record with the corrected reference."
                ),
                suggested_action=Action.REPROCESS_WITH_CORRECT_REF,
            )

    # ── Rule 3: default by detected type ─────────────────────────────────
    if detected == "MISSING_EXTERNAL":
        return Verdict(
            root_cause_code=DiscrepancyCode.MISSING_EXTERNAL,
            confidence=base_conf,
            explanation=(
                "The internal transaction has no corresponding external record. "
                "No near-match candidates were found in the external feed. "
                "A missing external posting should be created to balance the ledger."
            ),
            suggested_action=Action.CREATE_MISSING_POSTING,
        )

    if detected == "MISSING_INTERNAL":
        return Verdict(
            root_cause_code=DiscrepancyCode.MISSING_INTERNAL,
            confidence=base_conf,
            explanation=(
                "The external record has no corresponding internal transaction. "
                "The internal ledger is missing a posting for this settlement. "
                "A missing internal entry should be created."
            ),
            suggested_action=Action.CREATE_MISSING_POSTING,
        )

    # ── Rule 4: unknown ───────────────────────────────────────────────────
    return Verdict(
        root_cause_code=DiscrepancyCode.MISSING_EXTERNAL,
        confidence=0.40,
        explanation=(
            "Unable to determine the root cause from available features. "
            "Manual review is required."
        ),
        suggested_action=Action.MANUAL_REVIEW,
    )
