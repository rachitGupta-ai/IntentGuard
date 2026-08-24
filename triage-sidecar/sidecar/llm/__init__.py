"""Language-model adapters for the Alert Triage Sidecar.

Production LLM clients that implement the injectable ``HypothesisModel`` and
``VerdictModel`` protocols used by ``form_hypotheses`` and ``synthesize_verdict``.

Currently supported:
  * Google Gemini via ``google-generativeai`` — :class:`GeminiHypothesisModel`
    and :class:`GeminiVerdictModel`.

Configuration is read **exclusively from environment variables** so credentials
are never hardcoded:
  - ``GOOGLE_API_KEY``   : your Gemini API key (required).
  - ``GEMINI_MODEL``     : model name (optional, default ``gemini-1.5-flash``).

Usage::

    from sidecar.llm import GeminiHypothesisModel, GeminiVerdictModel, build_gemini_models

    hypothesis_model, verdict_model = build_gemini_models()
"""

from sidecar.llm.gemini import (
    GeminiHypothesisModel,
    GeminiVerdictModel,
    build_gemini_models,
)

__all__ = [
    "GeminiHypothesisModel",
    "GeminiVerdictModel",
    "build_gemini_models",
]
