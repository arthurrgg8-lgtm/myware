#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
HOST_VALUE="${HOST:-127.0.0.1}"
PORT_VALUE="${PORT:-8091}"
CONFIG_ENV_FILE="${CONFIG_ENV_FILE:-$HOME/.config/google-services/backend.env}"

if [[ -f "$CONFIG_ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$CONFIG_ENV_FILE"
fi

cd "$BACKEND_DIR"
echo "Starting Google Services backend at http://${HOST_VALUE}:${PORT_VALUE}"
exec env HOST="$HOST_VALUE" PORT="$PORT_VALUE" python3 server.py
