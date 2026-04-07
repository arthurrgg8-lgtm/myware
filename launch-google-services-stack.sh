#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/home/lazzy/Desktop/myware"
BACKEND_SCRIPT="$ROOT_DIR/launch-google-services.sh"
TUNNEL_SCRIPT="$ROOT_DIR/launch-cloudflare-tunnel.sh"
HOST_VALUE="${HOST:-127.0.0.1}"
PORT_VALUE="${PORT:-8091}"
BACKEND_BASE_URL="http://${HOST_VALUE}:${PORT_VALUE}"
BACKEND_HEALTH_URL="${BACKEND_BASE_URL}/api/health"
PUBLIC_URL="${PUBLIC_URL:-https://app.anuditk.com.np}"
OPEN_DASHBOARD="${OPEN_DASHBOARD:-1}"
BACKEND_PID=""
TUNNEL_PID=""
USE_EXISTING_BACKEND=0

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  if [[ -n "$TUNNEL_PID" ]] && kill -0 "$TUNNEL_PID" 2>/dev/null; then
    echo
    echo "Stopping Cloudflare tunnel..."
    kill "$TUNNEL_PID" 2>/dev/null || true
    wait "$TUNNEL_PID" 2>/dev/null || true
  fi
  if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "Stopping backend..."
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  exit "$exit_code"
}

require_command() {
  local command_name=$1
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required command: $command_name" >&2
    exit 1
  fi
}

wait_for_local_backend() {
  local attempts=30
  local delay_seconds=1
  local attempt
  for ((attempt = 1; attempt <= attempts; attempt += 1)); do
    if curl --silent --fail "$BACKEND_HEALTH_URL" >/dev/null 2>&1; then
      return 0
    fi
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      echo "Backend exited before becoming healthy." >&2
      return 1
    fi
    sleep "$delay_seconds"
  done
  echo "Backend did not become healthy at $BACKEND_HEALTH_URL within ${attempts}s." >&2
  return 1
}

local_backend_is_healthy() {
  curl --silent --fail "$BACKEND_HEALTH_URL" >/dev/null 2>&1
}

verify_public_route() {
  local public_health_url="${PUBLIC_URL%/}/api/health"
  local attempts=20
  local delay_seconds=2
  local attempt
  for ((attempt = 1; attempt <= attempts; attempt += 1)); do
    if curl --silent --fail --max-time 5 "$public_health_url" >/dev/null 2>&1; then
      echo "Public route is reachable at $PUBLIC_URL"
      return 0
    fi
    if ! kill -0 "$TUNNEL_PID" 2>/dev/null; then
      echo "Cloudflare tunnel exited before the public route became reachable." >&2
      return 1
    fi
    sleep "$delay_seconds"
  done
  echo "Warning: $PUBLIC_URL did not answer its health check within $((attempts * delay_seconds))s." >&2
  echo "The backend is running locally, but phones may still be unable to reach it yet." >&2
  return 0
}

open_dashboard() {
  if [[ "$OPEN_DASHBOARD" != "1" ]]; then
    return 0
  fi
  if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "${BACKEND_BASE_URL}/" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT INT TERM

require_command python3
require_command curl
require_command cloudflared

echo "Starting backend..."
if local_backend_is_healthy; then
  USE_EXISTING_BACKEND=1
  echo "A healthy backend is already running at $BACKEND_BASE_URL. Reusing it."
else
  bash "$BACKEND_SCRIPT" &
  BACKEND_PID=$!
  echo "Waiting for backend health at $BACKEND_HEALTH_URL ..."
  wait_for_local_backend
  echo "Backend is healthy."
fi

echo "Starting Cloudflare tunnel for $PUBLIC_URL ..."
bash "$TUNNEL_SCRIPT" &
TUNNEL_PID=$!

verify_public_route
open_dashboard

echo
echo "Google Services is running."
echo "Local dashboard: ${BACKEND_BASE_URL}/"
echo "Public endpoint: ${PUBLIC_URL}/"
echo "Press Ctrl+C in this window to stop both backend and tunnel."

if [[ "$USE_EXISTING_BACKEND" == "1" ]]; then
  wait "$TUNNEL_PID"
else
  wait "$BACKEND_PID" "$TUNNEL_PID"
fi
