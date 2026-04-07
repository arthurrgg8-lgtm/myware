#!/usr/bin/env bash
set -euo pipefail

BACKEND_URL="${BACKEND_URL:-http://127.0.0.1:8091}"
CONFIG_ENV_FILE="${CONFIG_ENV_FILE:-$HOME/.config/google-services/cloudflare.env}"

if [[ -f "$CONFIG_ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$CONFIG_ENV_FILE"
fi

if [[ -z "${CLOUDFLARED_TOKEN:-}" ]]; then
  echo "CLOUDFLARED_TOKEN is required. Set it in the environment or in $CONFIG_ENV_FILE" >&2
  exit 1
fi

exec cloudflared tunnel --url "$BACKEND_URL" run --token "$CLOUDFLARED_TOKEN"
