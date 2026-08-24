"""Gemini adapters using the OpenAI-compatible endpoint.

Uses the standard ``openai`` SDK pointed at Google's OpenAI-compatible base URL:
    https://generativelanguage.googleapis.com/v1beta/openai/

This avoids the gRPC/SSL certificate issues of the native google-generativeai SDK
on macOS and works identically to the OpenAI client but calls Gemini models.

Environment variables (never hardcoded):
    OPENAI_API_KEY   : your Gemini API key (required)
    OPENAI_BASE_URL  : base URL (default: https://generativelanguage.googleapis.com/v1beta/openai/)
    OPENAI_MODEL     : model name override (default: gemini-2.0-flash)
"""

from __future__ import annotations

import json
import os
import re
from typing import Any, Mapping, Optional, Sequence

from sidecar.triage.nodes.form_hypotheses import HypothesisModel, HypothesisRequest
from sidecar.triage.nodes.synthesize_verdict import VerdictModel, VerdictRequest

_DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
_DEFAULT_MODEL = "models/gemini-2.5-flash"


def _require_api_key() -> str:
    key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not key:
        raise EnvironmentError(
            "OPENAI_API_KEY environment variable is not set.\n"
            "Export it before constructing a Gemini model adapter:\n"
            "    export OPENAI_API_KEY='your-gemini-key-here'"
        )
    return key


def _base_url() -> str:
    return (
        os.environ.get("OPENAI_BASE_URL", _DEFAULT_BASE_URL).strip()
        or _DEFAULT_BASE_URL
    )


def _model_name() -> str:
    return (
        os.environ.get("OPENAI_MODEL", _DEFAULT_MODEL).strip()
        or _DEFAULT_MODEL
    )


def _make_client():
    from openai import OpenAI  # imported lazily so tests don't require it
    return OpenAI(api_key=_require_api_key(), base_url=_base_url())


def _extract_json(text: str) -> Any:
    """Extract the first JSON object or array from a model response string."""
    clean = re.sub(r"```(?:json)?\s*", "", text).strip().rstrip("`").strip()
    try:
        return json.loads(clean)
    except json.JSONDecodeError:
        pass
    for pattern in (r"\{.*\}", r"\[.*\]"):
        match = re.search(pattern, clean, re.DOTALL)
        if match:
            try:
                return json.loads(match.group())
            except json.JSONDecodeError:
                pass
    return None


# ---------------------------------------------------------------------------
# Hypothesis model
# ---------------------------------------------------------------------------

_HYPOTHESIS_SYSTEM = """You are a security-analyst assistant.
Analyse the alert context and return a JSON array of 1-10 threat hypotheses.
Each hypothesis must follow this exact schema:

[
  {
    "id": "<short unique id, e.g. h1>",
    "statement": "<one sentence describing the threat>",
    "threatCategory": "<exactly one of: prompt-injection | session-hijack | off-intent-agent | benign-anomaly | false-positive>",
    "supportingEvidence": [
      {
        "kind": "<context | correlation | probe>",
        "summary": "<one sentence fact from the data>",
        "auditRecordId": "<the auditRecordId of the source item>"
      }
    ]
  }
]

Rules:
- Return ONLY the JSON array. No markdown, no explanation.
- Each hypothesis must have exactly one threatCategory from the list above.
- Each hypothesis must have at least one supportingEvidence item with a non-empty auditRecordId.
- If data is insufficient, return one hypothesis with threatCategory "benign-anomaly"."""


class GeminiHypothesisModel:
    """Gemini-backed HypothesisModel via the OpenAI-compatible endpoint."""

    def __init__(self, model_name: Optional[str] = None) -> None:
        self._client = _make_client()
        self._model = model_name or _model_name()

    def propose_hypotheses(
        self, request: HypothesisRequest
    ) -> Sequence[Mapping[str, Any]]:
        response = self._client.chat.completions.create(
            model=self._model,
            messages=[
                {"role": "system", "content": _HYPOTHESIS_SYSTEM},
                {"role": "user", "content": self._build_prompt(request)},
            ],
            max_tokens=2048,
        )
        text = response.choices[0].message.content or ""
        parsed = _extract_json(text)
        if isinstance(parsed, list):
            return parsed
        if isinstance(parsed, dict):
            return [parsed]
        return []

    @staticmethod
    def _build_prompt(request: HypothesisRequest) -> str:
        lines = [f"Alert ID: {request.alertId}"]
        if request.triggerType:
            lines.append(f"Trigger type: {request.triggerType}")
        if request.context:
            lines.append("\nSession / actor context:")
            for item in request.context:
                lines.append(
                    f"  - [auditRecordId={item.auditRecordId}] ({item.kind}) {item.summary}"
                )
        if request.correlations:
            lines.append("\nCorrelated signals:")
            for item in request.correlations:
                lines.append(
                    f"  - [auditRecordId={item.auditRecordId}] ({item.kind}) {item.summary}"
                )
        return "\n".join(lines)


# ---------------------------------------------------------------------------
# Verdict model
# ---------------------------------------------------------------------------

_VERDICT_SYSTEM = """You are a security-analyst assistant performing final verdict synthesis.
Return a single JSON object:

{
  "value": "<exactly one of: confirmed_threat | benign | false_positive | uncertain>",
  "confidence": <float 0.0-1.0>,
  "threatCategory": "<exactly one of: prompt-injection | session-hijack | off-intent-agent | benign-anomaly | false-positive>"
}

Rules:
- Return ONLY the JSON object. No markdown, no explanation.
- value must be exactly one of the four permitted values.
- confidence must be a float between 0.0 and 1.0.
- threatCategory must be exactly one of the five permitted labels.
- If uncertain, use value "uncertain" and confidence 0.0."""


class GeminiVerdictModel:
    """Gemini-backed VerdictModel via the OpenAI-compatible endpoint."""

    def __init__(self, model_name: Optional[str] = None) -> None:
        self._client = _make_client()
        self._model = model_name or _model_name()

    def propose_verdict(self, request: VerdictRequest) -> Mapping[str, Any]:
        response = self._client.chat.completions.create(
            model=self._model,
            messages=[
                {"role": "system", "content": _VERDICT_SYSTEM},
                {"role": "user", "content": self._build_prompt(request)},
            ],
            max_tokens=256,
        )
        text = response.choices[0].message.content or ""
        parsed = _extract_json(text)
        if isinstance(parsed, dict):
            return parsed
        return {}

    @staticmethod
    def _build_prompt(request: VerdictRequest) -> str:
        lines = [f"Alert ID: {request.alertId}"]
        if request.triggerType:
            lines.append(f"Trigger type: {request.triggerType}")
        if request.hypothesisStatements:
            lines.append("\nFormed hypotheses:")
            for i, stmt in enumerate(request.hypothesisStatements, 1):
                lines.append(f"  {i}. {stmt}")
        if request.evidence:
            lines.append("\nSupporting evidence:")
            for item in request.evidence:
                lines.append(
                    f"  - [auditRecordId={item.auditRecordId}] ({item.kind}) {item.summary}"
                )
        return "\n".join(lines)


# ---------------------------------------------------------------------------
# Convenience factory
# ---------------------------------------------------------------------------

def build_gemini_models(
    model_name: Optional[str] = None,
) -> tuple[GeminiHypothesisModel, GeminiVerdictModel]:
    """Build both adapters from environment config.

    Reads OPENAI_API_KEY (required), OPENAI_BASE_URL, OPENAI_MODEL from env.

    Example::

        hypothesis_model, verdict_model = build_gemini_models()
        sidecar = Sidecar(hypothesis_model=hypothesis_model,
                          verdict_model=verdict_model, ...)
    """
    return GeminiHypothesisModel(model_name), GeminiVerdictModel(model_name)


__all__ = ["GeminiHypothesisModel", "GeminiVerdictModel", "build_gemini_models"]
