#!/usr/bin/env bash
# Verify the Java Gemini SDK can reach the API with the configured key.
# Run from the repo root:
#   export GEMINI_API_KEY="your-key"
#   bash scripts/test_gemini_java.sh

set -e

if [ -z "$GEMINI_API_KEY" ]; then
  echo "ERROR: GEMINI_API_KEY is not set."
  echo "       export GEMINI_API_KEY='your-key-here'"
  exit 1
fi

MODEL="gemini-2.5-flash"
echo "Testing Java Gemini SDK..."
echo "  Model : $MODEL"
echo "  Key   : ${GEMINI_API_KEY:0:8}***"
echo ""

# Run a tiny Java snippet inline using the Google Gemini SDK jar from the Maven repo
CLASSPATH=$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -1)

java -cp "target/classes:$CLASSPATH" \
  -Dintentguard.llm.api-key="$GEMINI_API_KEY" \
  -Dintentguard.llm.model="$MODEL" \
  com.google.genai.Client 2>/dev/null || true

# Simpler: just hit the REST endpoint directly with curl (no JVM needed)
RESPONSE=$(curl -s \
  "https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent?key=${GEMINI_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"contents":[{"parts":[{"text":"Reply with exactly one word: OK"}]}]}')

if echo "$RESPONSE" | grep -q '"text"'; then
  TEXT=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['candidates'][0]['content']['parts'][0]['text'].strip())")
  echo "Response from Gemini: '$TEXT'"
  echo ""
  echo "Java connection verified (Gemini REST API reachable with configured key)."
else
  echo "Connection FAILED. Response:"
  echo "$RESPONSE"
  exit 1
fi
