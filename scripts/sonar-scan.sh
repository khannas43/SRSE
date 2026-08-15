#!/usr/bin/env bash
# Run a full SonarQube scan for SRSE (backend Java + frontend TypeScript).
#
# Prerequisites:
#   - SonarQube running (default http://localhost:9012)
#   - Project "srse" created in SonarQube UI
#   - SONAR_HOST_URL and SONAR_TOKEN exported (see .env.example)
#   - Docker (preferred) OR sonar-scanner on PATH
#   - JDK 17 for backend tests

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Load .env if present (export SONAR_* without overriding existing env)
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

SONAR_HOST_URL="${SONAR_HOST_URL:-http://localhost:9012}"
SONAR_TOKEN="${SONAR_TOKEN:-}"

if [[ -z "$SONAR_TOKEN" ]]; then
  echo "ERROR: SONAR_TOKEN is not set." >&2
  echo "  1. Create project key 'SRSE' at ${SONAR_HOST_URL}/projects/create" >&2
  echo "  2. Generate a token: My Account → Security → Generate Token" >&2
  echo "  3. Add to .env: SONAR_TOKEN=... and SONAR_HOST_URL=${SONAR_HOST_URL}" >&2
  exit 1
fi

export SONAR_HOST_URL SONAR_TOKEN

echo "[sonar] Running backend tests with JaCoCo coverage..."
if [[ "$(uname -s)" == "Darwin" ]] && /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
  export JAVA_HOME
  JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi

(
  cd "$ROOT/backend"
  mvn -B clean test jacoco:report
)

echo "[sonar] Uploading analysis to ${SONAR_HOST_URL} (project: SRSE)..."

run_scanner() {
  local scanner_host_url="$SONAR_HOST_URL"
  # Docker Desktop on macOS/Windows: localhost inside the container is not the host.
  if command -v docker >/dev/null 2>&1; then
    case "$(uname -s)" in
      Darwin|MINGW*|MSYS*|CYGWIN*)
        scanner_host_url="${SONAR_HOST_URL//localhost/host.docker.internal}"
        scanner_host_url="${scanner_host_url//127.0.0.1/host.docker.internal}"
        ;;
    esac
    docker run --rm \
      -e SONAR_HOST_URL="$scanner_host_url" \
      -e SONAR_TOKEN \
      -v "$ROOT:/usr/src" \
      -w /usr/src \
      sonarsource/sonar-scanner-cli
    return
  fi

  if command -v sonar-scanner >/dev/null 2>&1; then
    sonar-scanner \
      -Dsonar.host.url="$SONAR_HOST_URL" \
      -Dsonar.token="$SONAR_TOKEN"
    return
  fi

  echo "ERROR: Neither Docker nor sonar-scanner found." >&2
  echo "  Install Docker, or: brew install sonar-scanner" >&2
  exit 1
}

run_scanner

echo "[sonar] Done. Dashboard: ${SONAR_HOST_URL}/dashboard?id=SRSE"
