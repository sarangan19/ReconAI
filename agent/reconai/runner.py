"""Entry point: run the agent for a single break."""
import os

from .agent import build_graph
from .models import Verdict
from .tools import EngineClient


def run_agent(
    break_id: int,
    *,
    mode: str = "rules",
    engine_url: str | None = None,
    post_verdict: bool = True,
) -> Verdict:
    url   = engine_url or os.environ.get("ENGINE_URL", "http://localhost:8080")
    graph = build_graph(url, mode)
    final = graph.invoke({"break_id": break_id, "context": None, "verdict": None})
    verdict = Verdict.model_validate(final["verdict"])

    if post_verdict:
        with EngineClient(url) as client:
            client.post_verdict(break_id, verdict.model_dump(mode="json"))

    return verdict


if __name__ == "__main__":
    import argparse, json
    from dotenv import load_dotenv
    load_dotenv()

    p = argparse.ArgumentParser()
    p.add_argument("--break-id", type=int, required=True)
    p.add_argument("--mode",     default=os.environ.get("AGENT_MODE", "rules"))
    p.add_argument("--no-post",  action="store_true")
    args = p.parse_args()

    v = run_agent(args.break_id, mode=args.mode, post_verdict=not args.no_post)
    print(json.dumps(v.model_dump(mode="json"), indent=2))
