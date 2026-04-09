import logging
from http import HTTPStatus

from config import SETTINGS

logger = logging.getLogger("tracker.auth")


def _bearer_token(handler) -> str:
    header = handler.headers.get("Authorization", "").strip()
    if not header.lower().startswith("bearer "):
        return ""
    return header[7:].strip()


def _json_unauthorized(handler, message: str) -> None:
    logger.warning("Unauthorized %s request for %s: %s", handler.command, handler.path, message)
    body = f'{{"error":"{message}"}}'.encode("utf-8")
    handler.send_response(HTTPStatus.UNAUTHORIZED)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def is_open_route(method: str, path: str) -> bool:
    return path in {"/", "/app.js", "/styles.css", "/api/health"}


def is_device_route(method: str, path: str) -> bool:
    parts = path.strip("/").split("/")
    if method == "POST" and path == "/api/devices":
        return True
    if len(parts) >= 4 and parts[0] == "api" and parts[1] == "devices":
        if method == "POST" and parts[3] in {"location", "hourly-reports"}:
            return True
        if method == "GET" and parts[3] == "commands":
            return True
        if method == "POST" and len(parts) == 6 and parts[3] == "commands" and parts[5] == "complete":
            return True
    return False


def authorize_request(handler, method: str, path: str) -> bool:
    if is_open_route(method, path):
        return True
    if is_device_route(method, path):
        provided = handler.headers.get("X-Tracker-Device-Token", "").strip()
        if provided and provided == SETTINGS.device_api_token:
            return True
        _json_unauthorized(handler, "device authentication required")
        return False
    provided = _bearer_token(handler)
    if provided and provided == SETTINGS.admin_token:
        return True
    _json_unauthorized(handler, "admin authentication required")
    return False
