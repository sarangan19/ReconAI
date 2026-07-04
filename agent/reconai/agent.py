"""LangGraph agent graph for both 'rules' and 'llm' modes."""
import os
from typing import Any, Optional, TypedDict

from langgraph.graph import END, START, StateGraph

from .models import Verdict
from .rules import classify
from .tools import EngineClient


class AgentState(TypedDict):
    break_id: int
    context: Optional[dict[str, Any]]
    verdict: Optional[dict[str, Any]]


def _build_prompt(context: dict[str, Any]) -> str:
    brk  = context.get("break", {})
    txns = context.get("transactions", [])
    near = context.get("nearMissCandidates", [])
    hist = context.get("counterpartyHistory", [])
    dups = context.get("duplicateScan", [])
    return f"""You are a financial reconciliation expert. Analyse this break and return ONLY valid JSON.

## Break
Type: {brk.get('detectedType')}  Confidence: {brk.get('detectedConfidence')}  Status: {brk.get('status')}

## Transaction(s) in this break
{txns}

## Near-miss candidates (transactions that almost matched)
{near if near else 'None'}

## Counterparty transaction history (recent, same batch)
{hist[:5] if hist else 'None'}

## Duplicate scan results
{dups if dups else 'None'}

## Required JSON output (return ONLY this JSON object, no other text)
{{
  "root_cause_code": "<one of: DUP_EXTERNAL, MISSING_EXTERNAL, MISSING_INTERNAL, AMT_FX_ROUNDING, AMT_FAT_FINGER, DATE_TIMING, REF_CORRUPTION, SPLIT_SETTLEMENT>",
  "confidence": <float 0.0-1.0>,
  "explanation": "<2-4 sentences referencing concrete evidence>",
  "suggested_action": "<one of: WAIT_SELF_CLEAR, REVERSE_DUPLICATE, CREATE_MISSING_POSTING, APPROVE_TOLERANCE, REPROCESS_WITH_CORRECT_REF, MANUAL_REVIEW>"
}}"""


def _llm_analyze(context: dict[str, Any]) -> Verdict:
    import json as _json
    import httpx as _httpx

    endpoint   = os.environ["AZURE_OPENAI_ENDPOINT"]
    api_key    = os.environ["AZURE_OPENAI_API_KEY"]
    deployment = os.environ["AZURE_OPENAI_DEPLOYMENT"]
    api_version = os.environ.get("AZURE_OPENAI_API_VERSION", "2025-04-01-preview")
    prompt     = _build_prompt(context)

    # Detect which API to use based on endpoint URL
    if "/responses" in endpoint:
        # Azure Responses API (newer endpoint format)
        url = endpoint if "?" in endpoint else f"{endpoint}?api-version={api_version}"
        payload: dict[str, Any] = {
            "model": deployment,
            "input": prompt,
            "text": {"format": {"type": "json_object"}},
        }
        r = _httpx.post(url, headers={"api-key": api_key, "Content-Type": "application/json"},
                        json=payload, timeout=60)
        r.raise_for_status()
        data = r.json()
        # Responses API shape: output[0].content[0].text
        raw = data["output"][0]["content"][0]["text"]
    else:
        # Standard Chat Completions API
        import re as _re
        base = _re.match(r"(https://[^/]+/?)", endpoint)
        base_url = base.group(1).rstrip("/") if base else endpoint
        url = f"{base_url}/openai/deployments/{deployment}/chat/completions?api-version={api_version}"
        payload = {
            "messages": [{"role": "user", "content": prompt}],
            "response_format": {"type": "json_object"},
            "temperature": 0,
        }
        r = _httpx.post(url, headers={"api-key": api_key, "Content-Type": "application/json"},
                        json=payload, timeout=60)
        r.raise_for_status()
        raw = r.json()["choices"][0]["message"]["content"]

    return Verdict.model_validate(_json.loads(raw))


def build_graph(engine_url: str, mode: str) -> Any:
    client = EngineClient(engine_url)

    def fetch_context(state: AgentState) -> AgentState:
        state["context"] = client.get_break_context(state["break_id"])
        return state

    def analyze(state: AgentState) -> AgentState:
        ctx = state["context"]
        if mode == "rules":
            verdict = classify(ctx)
        else:
            verdict = _llm_analyze(ctx)
        state["verdict"] = verdict.model_dump(mode="json")
        return state

    g = StateGraph(AgentState)
    g.add_node("fetch_context", fetch_context)
    g.add_node("analyze", analyze)
    g.add_edge(START, "fetch_context")
    g.add_edge("fetch_context", "analyze")
    g.add_edge("analyze", END)
    return g.compile()
