"""Unit tests for the deterministic rules classifier. No network, no API key."""
from reconai.models import Action, DiscrepancyCode
from reconai.rules import classify


def _ctx(detected_type, confidence=0.85, txns=None, near=None, dups=None):
    return {
        "break": {"detectedType": detected_type, "detectedConfidence": confidence},
        "transactions": txns or [],
        "nearMissCandidates": near or [],
        "duplicateScan": dups or [],
        "counterpartyHistory": [],
        "auditTrail": [],
        "agentVerdicts": [],
    }


def _txn(ref="TXN001", amount=1000.0, currency="USD",
         counterparty="Acme", value_date="2024-03-01", side="INTERNAL"):
    return {"id": 1, "side": side, "external_ref": ref,
            "amount": amount, "currency": currency,
            "counterparty": counterparty, "value_date": value_date}


def _nm(ref="TXN001", amount=1000.0, currency="USD",
        counterparty="Acme", value_date="2024-03-01",
        side="EXTERNAL", status="UNMATCHED"):
    return {"id": 2, "side": side, "external_ref": ref,
            "amount": amount, "currency": currency,
            "counterparty": counterparty, "value_date": value_date,
            "status": status}


# ── DUP_EXTERNAL ─────────────────────────────────────────────────────────

def test_dup_external_detected_type():
    v = classify(_ctx("DUP_EXTERNAL"))
    assert v.root_cause_code == DiscrepancyCode.DUP_EXTERNAL
    assert v.suggested_action == Action.REVERSE_DUPLICATE
    assert v.confidence >= 0.90


def test_dup_external_via_dup_scan():
    ctx = _ctx("MISSING_INTERNAL", dups=[{"id": 99, "side": "EXTERNAL"}])
    v = classify(ctx)
    assert v.root_cause_code == DiscrepancyCode.DUP_EXTERNAL
    assert v.suggested_action == Action.REVERSE_DUPLICATE


def test_dup_external_via_near_miss_matched():
    # Simulator appends _D to duplicate ref; near-miss has original ref, status=MATCHED
    brk_txn = _txn(ref="TXN0000000226_D", amount=4880.13, side="EXTERNAL")
    nm_txn  = _nm(ref="TXN0000000226",    amount=4880.13, side="INTERNAL", status="MATCHED")
    v = classify(_ctx("MISSING_INTERNAL", txns=[brk_txn], near=[nm_txn]))
    assert v.root_cause_code == DiscrepancyCode.DUP_EXTERNAL
    assert v.suggested_action == Action.REVERSE_DUPLICATE


# ── AMT_FAT_FINGER ────────────────────────────────────────────────────────

def test_fat_finger_same_ref_large_diff():
    brk_txn = _txn(ref="TXN001", amount=1234.0, value_date="2024-03-01")
    nm_txn  = _nm(ref="TXN001", amount=2134.0, value_date="2024-03-01", status="UNMATCHED")
    v = classify(_ctx("MISSING_EXTERNAL", txns=[brk_txn], near=[nm_txn]))
    assert v.root_cause_code == DiscrepancyCode.AMT_FAT_FINGER
    assert v.suggested_action == Action.MANUAL_REVIEW


def test_fat_finger_order_of_magnitude():
    brk_txn = _txn(ref="TXN001", amount=100.0, value_date="2024-03-01")
    nm_txn  = _nm(ref="TXN001", amount=1000.0, value_date="2024-03-01", status="UNMATCHED")
    v = classify(_ctx("MISSING_EXTERNAL", txns=[brk_txn], near=[nm_txn]))
    assert v.root_cause_code == DiscrepancyCode.AMT_FAT_FINGER


# ── AMT_FX_ROUNDING ──────────────────────────────────────────────────────

def test_fx_rounding_same_date_tiny_diff():
    brk_txn = _txn(amount=1000.0, value_date="2024-03-01")
    nm_txn  = _nm(amount=1003.0, value_date="2024-03-01")  # 0.3% diff, same ref
    v = classify(_ctx("MISSING_EXTERNAL", txns=[brk_txn], near=[nm_txn]))
    assert v.root_cause_code == DiscrepancyCode.AMT_FX_ROUNDING
    assert v.suggested_action == Action.APPROVE_TOLERANCE


def test_fx_rounding_boundary():
    brk_txn = _txn(amount=1000.0, value_date="2024-03-01")
    nm_txn  = _nm(amount=1005.0, value_date="2024-03-01")  # exactly 0.5%
    v = classify(_ctx("MISSING_EXTERNAL", txns=[brk_txn], near=[nm_txn]))
    assert v.root_cause_code == DiscrepancyCode.AMT_FX_ROUNDING


def test_fx_rounding_not_triggered_large_diff():
    brk_txn = _txn(amount=1000.0, value_date="2024-03-01")
    nm_txn  = _nm(amount=1100.0, value_date="2024-03-01")  # 10% diff
    v = classify(_ctx("MISSING_EXTERNAL", txns=[brk_txn], near=[nm_txn]))
    assert v.root_cause_code != DiscrepancyCode.AMT_FX_ROUNDING


# ── DATE_TIMING ───────────────────────────────────────────────────────────

def test_date_timing_one_day_shift():
    brk_txn = _txn(amount=500.0, value_date="2024-03-01")
    nm_txn  = _nm(amount=500.0, value_date="2024-03-02")  # +1 day, same amount/ref
    v = classify(_ctx("MISSING_EXTERNAL", txns=[brk_txn], near=[nm_txn]))
    assert v.root_cause_code == DiscrepancyCode.DATE_TIMING
    assert v.suggested_action == Action.WAIT_SELF_CLEAR


def test_date_timing_two_day_shift():
    brk_txn = _txn(amount=750.0, value_date="2024-03-01")
    nm_txn  = _nm(amount=750.0, value_date="2024-03-03")  # +2 days
    v = classify(_ctx("MISSING_EXTERNAL", txns=[brk_txn], near=[nm_txn]))
    assert v.root_cause_code == DiscrepancyCode.DATE_TIMING


# ── REF_CORRUPTION ────────────────────────────────────────────────────────

def test_ref_corruption_truncated_ref():
    brk_txn = _txn(ref="TXN0000001234", amount=500.0, value_date="2024-03-01")
    # Near-miss is UNMATCHED (not yet settled) with a truncated ref
    nm_txn  = _nm(ref="TXN000000123", amount=500.0, value_date="2024-03-01", status="UNMATCHED")
    v = classify(_ctx("MISSING_EXTERNAL", txns=[brk_txn], near=[nm_txn]))
    assert v.root_cause_code == DiscrepancyCode.REF_CORRUPTION
    assert v.suggested_action == Action.REPROCESS_WITH_CORRECT_REF


def test_ref_corruption_unmatched_near_miss_only():
    # Same ref scenario: ensure MATCHED near-miss with same ref doesn't cause REF_CORRUPTION
    brk_txn = _txn(ref="TXN001", amount=500.0)
    nm_txn  = _nm(ref="TXN002", amount=500.0, status="UNMATCHED")  # refs differ + unmatched
    v = classify(_ctx("MISSING_EXTERNAL", txns=[brk_txn], near=[nm_txn]))
    assert v.root_cause_code == DiscrepancyCode.REF_CORRUPTION


# ── MISSING_EXTERNAL / MISSING_INTERNAL ──────────────────────────────────

def test_missing_external_no_near_miss():
    v = classify(_ctx("MISSING_EXTERNAL", txns=[_txn()]))
    assert v.root_cause_code == DiscrepancyCode.MISSING_EXTERNAL
    assert v.suggested_action == Action.CREATE_MISSING_POSTING
    assert v.confidence == 0.85


def test_missing_internal():
    v = classify(_ctx("MISSING_INTERNAL", txns=[_txn(side="EXTERNAL")]))
    assert v.root_cause_code == DiscrepancyCode.MISSING_INTERNAL
    assert v.suggested_action == Action.CREATE_MISSING_POSTING


# ── Edge cases ────────────────────────────────────────────────────────────

def test_unknown_type_returns_fallback():
    v = classify(_ctx("UNKNOWN_TYPE"))
    assert v.confidence <= 0.50
    assert v.suggested_action == Action.MANUAL_REVIEW


def test_verdict_is_always_valid():
    """All classify outputs must pass Verdict validation."""
    from reconai.models import Verdict
    contexts = [
        _ctx("DUP_EXTERNAL"),
        _ctx("MISSING_EXTERNAL"),
        _ctx("MISSING_INTERNAL"),
        _ctx("MISSING_EXTERNAL", txns=[_txn()], near=[_nm(amount=1002.0)]),
        _ctx("MISSING_INTERNAL",
             txns=[_txn(ref="TXN_D", side="EXTERNAL")],
             near=[_nm(ref="TXN", status="MATCHED")]),
    ]
    for ctx in contexts:
        v = classify(ctx)
        assert isinstance(v, Verdict)
        assert 0.0 <= v.confidence <= 1.0
        assert len(v.explanation) >= 10
