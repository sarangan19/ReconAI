import pytest
from pydantic import ValidationError
from reconai.models import Action, DiscrepancyCode, Verdict


def test_verdict_valid():
    v = Verdict(
        root_cause_code=DiscrepancyCode.MISSING_EXTERNAL,
        confidence=0.85,
        explanation="Internal txn has no corresponding external record.",
        suggested_action=Action.CREATE_MISSING_POSTING,
    )
    assert v.root_cause_code == DiscrepancyCode.MISSING_EXTERNAL
    assert v.confidence == 0.85


def test_verdict_confidence_bounds():
    with pytest.raises(ValidationError):
        Verdict(
            root_cause_code=DiscrepancyCode.DUP_EXTERNAL,
            confidence=1.5,
            explanation="Too high.",
            suggested_action=Action.REVERSE_DUPLICATE,
        )
    with pytest.raises(ValidationError):
        Verdict(
            root_cause_code=DiscrepancyCode.DUP_EXTERNAL,
            confidence=-0.1,
            explanation="Negative.",
            suggested_action=Action.REVERSE_DUPLICATE,
        )


def test_verdict_explanation_required():
    with pytest.raises(ValidationError):
        Verdict(
            root_cause_code=DiscrepancyCode.DATE_TIMING,
            confidence=0.7,
            explanation="",   # too short
            suggested_action=Action.WAIT_SELF_CLEAR,
        )


def test_verdict_invalid_code():
    with pytest.raises(ValidationError):
        Verdict(
            root_cause_code="NOT_A_REAL_CODE",  # type: ignore
            confidence=0.5,
            explanation="Bad code.",
            suggested_action=Action.MANUAL_REVIEW,
        )


def test_verdict_invalid_action():
    with pytest.raises(ValidationError):
        Verdict(
            root_cause_code=DiscrepancyCode.DUP_EXTERNAL,
            confidence=0.9,
            explanation="Valid explanation here.",
            suggested_action="DO_NOTHING",  # type: ignore
        )


def test_verdict_round_trip_json():
    v = Verdict(
        root_cause_code=DiscrepancyCode.AMT_FX_ROUNDING,
        confidence=0.80,
        explanation="FX rounding difference of 0.2% detected on same value date.",
        suggested_action=Action.APPROVE_TOLERANCE,
    )
    serialized = v.model_dump(mode="json")
    restored   = Verdict.model_validate(serialized)
    assert restored == v


def test_all_discrepancy_codes_valid():
    codes = [
        "DUP_EXTERNAL", "MISSING_EXTERNAL", "MISSING_INTERNAL",
        "AMT_FX_ROUNDING", "AMT_FAT_FINGER", "DATE_TIMING",
        "REF_CORRUPTION", "SPLIT_SETTLEMENT",
    ]
    for code in codes:
        v = Verdict(
            root_cause_code=code,  # type: ignore
            confidence=0.75,
            explanation="Test explanation for this code.",
            suggested_action=Action.MANUAL_REVIEW,
        )
        assert v.root_cause_code.value == code
