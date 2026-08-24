"""HTTP entrypoint for the Alert Triage Sidecar.

Exposes the Integration_Contract endpoints over FastAPI/uvicorn and wires
the real Gemini LLM adapters from environment variables.

Endpoints
---------
POST /triage/v1/alerts
    Inbound alert delivery from IntentGuard.

POST /triage/v1/investigations/{alert_id}/decision
    Control_Tower approve/reject decision (HITL resume channel).

GET  /health
    Liveness check.

Environment variables required
-------------------------------
    OPENAI_API_KEY   : Gemini API key (same key used for Java under GEMINI_API_KEY)
    OPENAI_BASE_URL  : https://generativelanguage.googleapis.com/v1beta/openai/
    OPENAI_MODEL     : models/gemini-2.5-flash

Start with::

    export OPENAI_API_KEY="your-key"
    export OPENAI_BASE_URL="https://generativelanguage.googleapis.com/v1beta/openai/"
    export OPENAI_MODEL="models/gemini-2.5-flash"

    cd triage-sidecar
    .venv/bin/python -m sidecar.server
    # or:
    .venv/bin/uvicorn sidecar.server:app --host 0.0.0.0 --port 8081
"""

from __future__ import annotations

import os
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

# ---------------------------------------------------------------------------
# Build the production Sidecar with real Gemini models
# ---------------------------------------------------------------------------

def _build_sidecar():
    """Build the Sidecar wired with real Gemini LLM adapters from env vars."""
    from sidecar.app import Sidecar
    from sidecar.llm import build_gemini_models

    # Validate required env vars at startup so failures are immediate and clear.
    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise EnvironmentError(
            "OPENAI_API_KEY is not set. "
            "Export it before starting the sidecar:\n"
            "    export OPENAI_API_KEY='your-gemini-key'"
        )

    hypothesis_model, verdict_model = build_gemini_models()
    return Sidecar(
        hypothesis_model=hypothesis_model,
        verdict_model=verdict_model,
    )


# Sidecar singleton — built once at startup.
_sidecar = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _sidecar
    print("Starting Alert Triage Sidecar...")
    _sidecar = _build_sidecar()
    print(f"  LLM model : {os.environ.get('OPENAI_MODEL', 'models/gemini-2.5-flash')}")
    print(f"  LLM key   : {os.environ.get('OPENAI_API_KEY', '')[:8]}***")
    print("  Sidecar ready.")
    yield
    print("Shutting down Alert Triage Sidecar.")


app = FastAPI(
    title="Alert Triage Sidecar",
    description="Advisory, read-only LangGraph alert-triage sidecar for IntentGuard.",
    version="0.1.0",
    lifespan=lifespan,
)


def _get_sidecar():
    if _sidecar is None:
        raise HTTPException(status_code=503, detail="Sidecar not initialised")
    return _sidecar


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get("/health")
def health():
    """Liveness check — returns 200 when the sidecar is running."""
    return {"status": "ok", "service": "alert-triage-sidecar"}


@app.post("/triage/v1/alerts")
async def handle_alert(request: Request):
    """Inbound alert delivery from IntentGuard (Integration_Contract, R14.5)."""
    sidecar = _get_sidecar()
    try:
        body: Any = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Request body must be valid JSON")

    response = sidecar.handle_alert(body)
    return JSONResponse(status_code=response.status_code, content=dict(response.body))


@app.post("/triage/v1/investigations/{alert_id}/decision")
async def handle_decision(alert_id: str, request: Request):
    """Control_Tower approve/reject decision channel (R11.3-R11.5)."""
    sidecar = _get_sidecar()
    try:
        body: Any = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Request body must be valid JSON")

    try:
        result = sidecar.handle_decision(alert_id, body)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    return {
        "alertId": result.ack.alertId,
        "accepted": result.ack.accepted,
        "mayProceed": result.mayProceed,
        "resumed": result.resumed,
        "detail": result.ack.detail,
    }


# ---------------------------------------------------------------------------
# Run directly
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("SIDECAR_PORT", "8081"))
    uvicorn.run("sidecar.server:app", host="0.0.0.0", port=port, reload=False)
