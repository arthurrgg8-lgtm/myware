#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/home/lazzy/Desktop/myware"
BACKEND_DIR="$ROOT_DIR/backend"
HOST_VALUE="${HOST:-127.0.0.1}"
PORT_VALUE="${PORT:-8091}"

cd "$BACKEND_DIR"
echo "Starting Google Services backend at http://${HOST_VALUE}:${PORT_VALUE}"
exec env HOST="$HOST_VALUE" PORT="$PORT_VALUE" python3 server.py
