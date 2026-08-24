#!/usr/bin/env bash
#
# run.sh — bring the IntentGuard Semantic Firewall up locally.
#
# Builds the Spring Boot application (via the Maven wrapper) and runs it.
# MongoDB and Gemini are BOTH optional: without them the engine still starts
# and degrades gracefully (persistence is skipped / Semantic_Inconsistency is
# excluded), so this script runs out of the box.
#
# Usage:
#   ./run.sh                 build (skipping tests) and start the app
#   ./run.sh --tests         run the full test suite before starting
#   ./run.sh --skip-build    reuse the existing jar, just start
#   ./run.sh --with-mongo    start a local MongoDB via Docker first (if available)
#   ./run.sh --help          show this help
#
# Environment:
#   GEMINI_API_KEY               Google Gemini key. Unset => semantic scoring is
#                                excluded (the rest of the pipeline still works).
#   SERVER_PORT                  HTTP port for the Control_Tower (default 8080).
#   INTENTGUARD_SOCKET_PATH      Shell_Hook Unix domain socket path. Defaults to a
#                                user-writable path so no sudo is needed.
#   INTENTGUARD_MONGO_CONNECTIONSTRING  Override the Mongo URI (default
#                                mongodb://localhost:27017).
#
set -euo pipefail

# --- Resolve project root (directory of this script) -------------------------
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# --- Defaults ----------------------------------------------------------------
RUN_TESTS=false
SKIP_BUILD=false
WITH_MONGO=false

SERVER_PORT="${SERVER_PORT:-8080}"
# A short, user-writable socket path (Unix socket paths are capped ~104 bytes,
# and /var/run is not writable without sudo on macOS/Linux dev machines).
INTENTGUARD_SOCKET_PATH="${INTENTGUARD_SOCKET_PATH:-/tmp/intentguard/intentguard.sock}"
INTENTGUARD_MONGO_CONNECTIONSTRING="${INTENTGUARD_MONGO_CONNECTIONSTRING:-mongodb://localhost:27017}"

# --- Parse args --------------------------------------------------------------
for arg in "$@"; do
  case "$arg" in
    --tests)      RUN_TESTS=true ;;
    --skip-build) SKIP_BUILD=true ;;
    --with-mongo) WITH_MONGO=true ;;
    -h|--help)
      sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown option: $arg (try --help)" >&2
      exit 2
      ;;
  esac
done

log() { printf '\033[1;34m[intentguard]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[intentguard]\033[0m %s\n' "$*" >&2; }

# --- Preflight: Java 17+ -----------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: java not found on PATH. Java 17+ is required." >&2
  exit 1
fi
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | awk -F'.' '{print ($1=="1")?$2:$1}')"
if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -lt 17 ] 2>/dev/null; then
  echo "ERROR: Java 17+ is required (found major version $JAVA_MAJOR)." >&2
  exit 1
fi

# --- Optional: start MongoDB via Docker --------------------------------------
if [ "$WITH_MONGO" = true ]; then
  if command -v docker >/dev/null 2>&1; then
    if [ -n "$(docker ps -q -f name=intentguard-mongo)" ]; then
      log "MongoDB container 'intentguard-mongo' already running."
    elif [ -n "$(docker ps -aq -f name=intentguard-mongo)" ]; then
      log "Starting existing MongoDB container 'intentguard-mongo'..."
      docker start intentguard-mongo >/dev/null
    else
      log "Launching MongoDB (mongo:7) as 'intentguard-mongo' on :27017..."
      docker run -d --name intentguard-mongo -p 27017:27017 mongo:7 >/dev/null
    fi
  else
    warn "--with-mongo requested but Docker is not installed. Continuing without Mongo (persistence degrades gracefully)."
  fi
fi

# --- Preflight notices -------------------------------------------------------
if [ -z "${GEMINI_API_KEY:-}" ]; then
  warn "GEMINI_API_KEY is not set — Semantic_Inconsistency scoring will be excluded (the rest of the pipeline still runs)."
fi

# Ensure the socket directory exists and is writable.
SOCKET_DIR="$(dirname "$INTENTGUARD_SOCKET_PATH")"
mkdir -p "$SOCKET_DIR" 2>/dev/null || warn "Could not create socket dir $SOCKET_DIR; the blocking-gate socket may be unavailable."

# --- Build -------------------------------------------------------------------
if [ "$RUN_TESTS" = true ]; then
  log "Running full test suite..."
  ./mvnw -q clean verify
elif [ "$SKIP_BUILD" = true ]; then
  log "Skipping build (reusing existing jar)."
else
  log "Building application (tests skipped; use --tests to include them)..."
  ./mvnw -q -DskipTests clean package
fi

# --- Locate the runnable jar -------------------------------------------------
JAR="$(ls -1 target/intentguard-*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -n1 || true)"
if [ -z "$JAR" ]; then
  echo "ERROR: no runnable jar found in target/. Build may have failed; run without --skip-build." >&2
  exit 1
fi

# --- Run ---------------------------------------------------------------------
log "Starting IntentGuard Enforcement_Engine..."
log "  Control_Tower : http://localhost:${SERVER_PORT}/"
log "  Live stream   : http://localhost:${SERVER_PORT}/api/stream"
log "  Shell socket  : ${INTENTGUARD_SOCKET_PATH}"
log "  MongoDB       : ${INTENTGUARD_MONGO_CONNECTIONSTRING}"
log "  Gemini        : $([ -n "${GEMINI_API_KEY:-}" ] && echo 'configured' || echo 'not set (semantic scoring excluded)')"
echo

exec java -jar "$JAR" \
  --server.port="${SERVER_PORT}" \
  --intentguard.socket.path="${INTENTGUARD_SOCKET_PATH}" \
  --intentguard.mongo.connection-string="${INTENTGUARD_MONGO_CONNECTIONSTRING}"
