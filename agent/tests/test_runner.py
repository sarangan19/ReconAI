"""Unit tests for the runner — mocks EngineClient so no live engine needed."""
from unittest.mock import MagicMock, patch

from reconai.models import Action, DiscrepancyCode, Verdict
from reconai.runner import run_agent


def _mock_context(detected_type="MISSING_EXTERNAL"):
    return {
        "break": {"id": 42, "batchId": 1, "detectedType": detected_type,
                  "detectedConfidence": 0.85, "status": "OPEN"},
        "transactions": [{"id": 100, "side": "INTERNAL", "external_ref": "TXN001",
                          "amount": 500.0, "currency": "USD", "counterparty": "Acme",
                          "value_date": "2024-03-01", "status": "UNMATCHED"}],
        "nearMissCandidates": [],
        "counterpartyHistory": [],
        "duplicateScan": [],
        "auditTrail": [],
        "agentVerdicts": [],
    }


def test_run_agent_rules_no_post():
    # EngineClient in build_graph is used directly (not context manager)
    with patch("reconai.agent.EngineClient") as MockClient:
        instance = MockClient.return_value
        instance.get_break_context.return_value = _mock_context()

        verdict = run_agent(42, mode="rules", engine_url="http://fake", post_verdict=False)

    assert isinstance(verdict, Verdict)
    assert verdict.root_cause_code == DiscrepancyCode.MISSING_EXTERNAL


def test_run_agent_rules_posts_verdict():
    with patch("reconai.agent.EngineClient") as MockGraphClient, \
         patch("reconai.runner.EngineClient") as MockRunnerClient:

        MockGraphClient.return_value.get_break_context.return_value = _mock_context()
        runner_instance = MockRunnerClient.return_value.__enter__.return_value

        run_agent(42, mode="rules", engine_url="http://fake", post_verdict=True)

        runner_instance.post_verdict.assert_called_once()
        call_args = runner_instance.post_verdict.call_args
        assert call_args[0][0] == 42
        assert "root_cause_code" in call_args[0][1]


def test_run_agent_dup_external_classified():
    with patch("reconai.agent.EngineClient") as MockClient:
        MockClient.return_value.get_break_context.return_value = _mock_context("DUP_EXTERNAL")

        verdict = run_agent(42, mode="rules", engine_url="http://fake", post_verdict=False)

    assert verdict.root_cause_code == DiscrepancyCode.DUP_EXTERNAL
    assert verdict.suggested_action == Action.REVERSE_DUPLICATE


def test_run_agent_missing_internal():
    with patch("reconai.agent.EngineClient") as MockClient:
        ctx = _mock_context("MISSING_INTERNAL")
        ctx["transactions"][0]["side"] = "EXTERNAL"
        MockClient.return_value.get_break_context.return_value = ctx

        verdict = run_agent(42, mode="rules", engine_url="http://fake", post_verdict=False)

    assert verdict.root_cause_code == DiscrepancyCode.MISSING_INTERNAL
    assert verdict.suggested_action == Action.CREATE_MISSING_POSTING
