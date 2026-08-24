"""Standalone Gemini connection test via the OpenAI-compatible endpoint.

Uses the standard ``openai`` SDK pointed at Google's OpenAI-compatible base URL,
which avoids the gRPC/SSL certificate issues of the native google-generativeai SDK.

Environment variables (all read from env, never hardcoded):
    OPENAI_API_KEY   : your Gemini API key (required)
    OPENAI_BASE_URL  : https://generativelanguage.googleapis.com/v1beta/openai/
    OPENAI_MODEL     : model name (optional, default gemini-2.0-flash)

Usage::

    export OPENAI_API_KEY="your-key-here"
    export OPENAI_BASE_URL="https://generativelanguage.googleapis.com/v1beta/openai/"
    export OPENAI_MODEL="gemini-2.0-flash"

    cd triage-sidecar
    .venv/bin/python scripts/test_gemini_connection.py
"""

import os
import sys


def main() -> int:
    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    base_url = os.environ.get(
        "OPENAI_BASE_URL",
        "https://generativelanguage.googleapis.com/v1beta/openai/",
    ).strip()
    model = os.environ.get("OPENAI_MODEL", "gemini-2.0-flash").strip() or "gemini-2.0-flash"

    if not api_key:
        print("ERROR: OPENAI_API_KEY environment variable is not set.")
        print("       export OPENAI_API_KEY='your-gemini-api-key'")
        return 1

    print("Testing Gemini via OpenAI-compatible endpoint...")
    print(f"  Base URL : {base_url}")
    print(f"  Model    : {model}")
    print(f"  Key      : {api_key[:8]}{'*' * max(0, len(api_key) - 8)}")
    print()

    try:
        from openai import OpenAI
    except ImportError:
        print("ERROR: openai package not installed.")
        print("       pip install openai==1.55.0")
        return 1

    client = OpenAI(api_key=api_key, base_url=base_url)

    try:
        response = client.chat.completions.create(
            model=model,
            messages=[{"role": "user", "content": "Reply with exactly one word: OK"}],
            max_tokens=10,
        )
        reply = response.choices[0].message.content.strip()
        print(f"Response from Gemini: {reply!r}")
        print()
        print("Connection successful.")
        return 0
    except Exception as exc:
        print(f"Connection FAILED: {exc}")
        print()
        print("Common causes:")
        print("  - Invalid or revoked API key")
        print("  - Wrong OPENAI_BASE_URL (should end with /openai/)")
        print("  - Model name not available on your account")
        print("  - Network / firewall / proxy issue")
        return 1


if __name__ == "__main__":
    sys.exit(main())
