import httpx
from typing import Any


class EngineClient:
    """HTTP client for the ReconAI engine REST API."""

    def __init__(self, base_url: str, timeout: float = 30.0):
        self.base = base_url.rstrip("/")
        self._http = httpx.Client(timeout=timeout)

    # ── Agent-facing (safe for inference) ────────────────────────────────

    def get_break_context(self, break_id: int) -> dict[str, Any]:
        r = self._http.get(f"{self.base}/api/breaks/{break_id}/context")
        r.raise_for_status()
        return r.json()

    def get_breaks(self, batch_id: int, page: int = 0, size: int = 200) -> dict[str, Any]:
        r = self._http.get(f"{self.base}/api/breaks",
                           params={"batchId": batch_id, "page": page, "size": size})
        r.raise_for_status()
        return r.json()

    def post_verdict(self, break_id: int, verdict: dict[str, Any]) -> None:
        r = self._http.post(
            f"{self.base}/api/breaks/{break_id}/verdict",
            json=verdict,
        )
        r.raise_for_status()

    # ── Eval-harness-only (NEVER call from runner.py) ─────────────────────

    def get_ground_truth(self, batch_id: int) -> dict[int, str]:
        """Returns {break_id: injected_code}. Eval harness only."""
        r = self._http.get(f"{self.base}/api/eval/ground-truth",
                           params={"batchId": batch_id})
        r.raise_for_status()
        return {int(k): v for k, v in r.json().items()}

    def close(self) -> None:
        self._http.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
