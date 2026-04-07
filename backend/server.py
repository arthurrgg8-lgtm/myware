#!/usr/bin/env python3
import csv
import io
import json
import os
import random
import sqlite3
import time
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, urlopen

import jwt


BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
DATA_DIR = BASE_DIR / "data"
DB_PATH = DATA_DIR / "tracker.db"
HOST = os.environ.get("HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8080"))
DEFAULT_FCM_SERVICE_ACCOUNT_PATH = "/home/lazzy/Desktop/myware-1f5c4-firebase-adminsdk-fbsvc-e1ebd93477.json"
FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
_FCM_ACCESS_TOKEN = None
_FCM_ACCESS_TOKEN_EXPIRES_AT = 0


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def get_fcm_access_token() -> tuple[str | None, str | None, str | None]:
    global _FCM_ACCESS_TOKEN, _FCM_ACCESS_TOKEN_EXPIRES_AT

    service_account_path = (os.environ.get("FCM_SERVICE_ACCOUNT_JSON") or DEFAULT_FCM_SERVICE_ACCOUNT_PATH).strip()
    if not service_account_path:
        return None, None, "FCM service account path is not configured."

    path = Path(service_account_path)
    if not path.exists():
        return None, None, f"FCM service account file not found: {service_account_path}"

    now_ts = int(time.time())
    if _FCM_ACCESS_TOKEN and now_ts < _FCM_ACCESS_TOKEN_EXPIRES_AT - 60:
        project_id = json.loads(path.read_text()).get("project_id")
        return _FCM_ACCESS_TOKEN, project_id, None

    info = json.loads(path.read_text())
    project_id = info.get("project_id")
    client_email = info.get("client_email")
    private_key = info.get("private_key")
    token_uri = info.get("token_uri") or "https://oauth2.googleapis.com/token"
    if not project_id or not client_email or not private_key:
        return None, None, "FCM service account file is missing required fields."

    issued_at = int(time.time())
    assertion = jwt.encode(
        {
            "iss": client_email,
            "scope": FCM_SCOPE,
            "aud": token_uri,
            "iat": issued_at,
            "exp": issued_at + 3600,
        },
        private_key,
        algorithm="RS256",
    )
    form_body = urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion,
        }
    ).encode("utf-8")
    request = Request(
        token_uri,
        data=form_body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=10) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        return None, None, error.read().decode("utf-8", errors="replace") or str(error)
    except URLError as error:
        return None, None, str(error)

    access_token = payload.get("access_token")
    expires_in = int(payload.get("expires_in") or 3600)
    if not access_token:
        return None, None, "OAuth token response did not include access_token."

    _FCM_ACCESS_TOKEN = access_token
    _FCM_ACCESS_TOKEN_EXPIRES_AT = issued_at + expires_in
    return access_token, project_id, None


def send_fcm_wakeup(push_token: str, device_id: str, command_type: str) -> tuple[bool, str | None]:
    if not push_token:
        return False, None
    access_token, project_id, auth_error = get_fcm_access_token()
    if not access_token or not project_id:
        return False, auth_error
    payload = {
        "message": {
            "token": push_token,
            "data": {
                "kind": "command_wakeup",
                "deviceId": device_id,
                "commandType": command_type,
            },
            "android": {
                "priority": "high",
            },
        }
    }
    request = Request(
        f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Authorization": f"Bearer {access_token}",
        },
        method="POST",
    )
    try:
        with urlopen(request, timeout=10) as response:
            body = response.read().decode("utf-8", errors="replace")
        return True, body
    except HTTPError as error:
        return False, error.read().decode("utf-8", errors="replace") or str(error)
    except URLError as error:
        return False, str(error)


def prefer_value(incoming, existing):
    return incoming if incoming not in (None, "") else existing


def generate_device_code() -> str:
    return "".join(str(random.randint(0, 9)) for _ in range(16))


def get_db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = get_db()
    try:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS devices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_code TEXT UNIQUE,
                name TEXT NOT NULL,
                owner_email TEXT NOT NULL,
                platform TEXT NOT NULL,
                device_token TEXT UNIQUE,
                push_token TEXT,
                status TEXT NOT NULL DEFAULT 'active',
                battery_level INTEGER NOT NULL DEFAULT 100,
                is_charging INTEGER NOT NULL DEFAULT 0,
                network_status TEXT NOT NULL DEFAULT 'online',
                wifi_ssid TEXT,
                carrier_name TEXT,
                local_ip TEXT,
                public_ip TEXT,
                isp_name TEXT,
                device_time_zone TEXT,
                device_timestamp_ms INTEGER,
                last_latitude REAL,
                last_longitude REAL,
                last_seen_at TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS location_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy_m REAL,
                battery_level INTEGER,
                network_status TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(device_id) REFERENCES devices(id)
            );

            CREATE TABLE IF NOT EXISTS device_actions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id INTEGER NOT NULL,
                action_type TEXT NOT NULL,
                notes TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(device_id) REFERENCES devices(id)
            );

            CREATE TABLE IF NOT EXISTS device_commands (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id INTEGER NOT NULL,
                command_type TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                payload TEXT,
                created_at TEXT NOT NULL,
                completed_at TEXT,
                FOREIGN KEY(device_id) REFERENCES devices(id)
            );

            CREATE TABLE IF NOT EXISTS hourly_reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id INTEGER NOT NULL,
                hour_key TEXT NOT NULL,
                status TEXT NOT NULL,
                failure_reason TEXT,
                latitude REAL,
                longitude REAL,
                last_known_latitude REAL,
                last_known_longitude REAL,
                accuracy_m REAL,
                battery_level INTEGER,
                network_status TEXT,
                device_time_zone TEXT,
                device_timestamp_ms INTEGER,
                captured_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                UNIQUE(device_id, hour_key),
                FOREIGN KEY(device_id) REFERENCES devices(id)
            );
            """
        )
    finally:
        conn.close()


def row_to_device(row: sqlite3.Row) -> dict:
    return {
        "id": row["device_code"],
        "name": row["name"],
        "ownerEmail": row["owner_email"],
        "platform": row["platform"],
        "deviceToken": row["device_token"],
        "status": row["status"],
        "batteryLevel": row["battery_level"],
        "isCharging": bool(row["is_charging"]),
        "networkStatus": row["network_status"],
        "wifiSsid": row["wifi_ssid"],
        "carrierName": row["carrier_name"],
        "localIp": row["local_ip"],
        "publicIp": row["public_ip"],
        "ispName": row["isp_name"],
        "deviceTimeZone": row["device_time_zone"],
        "deviceTimestampMs": row["device_timestamp_ms"],
        "locationServicesEnabled": bool(row["location_services_enabled"]),
        "lastLatitude": row["last_latitude"],
        "lastLongitude": row["last_longitude"],
        "lastSeenAt": row["last_seen_at"],
        "createdAt": row["created_at"],
        "updatedAt": row["updated_at"],
    }


def json_response(handler: BaseHTTPRequestHandler, payload: dict, status: int = 200) -> None:
    body = json.dumps(payload).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def csv_response(handler: BaseHTTPRequestHandler, filename: str, rows: list[list[str]]) -> None:
    stream = io.StringIO()
    writer = csv.writer(stream)
    writer.writerows(rows)
    body = stream.getvalue().encode("utf-8")
    handler.send_response(HTTPStatus.OK)
    handler.send_header("Content-Type", "text/csv; charset=utf-8")
    handler.send_header("Content-Disposition", f'attachment; filename="{filename}"')
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def parse_json(handler: BaseHTTPRequestHandler) -> dict:
    length = int(handler.headers.get("Content-Length", "0"))
    raw = handler.rfile.read(length) if length else b"{}"
    return json.loads(raw.decode("utf-8") or "{}")


def ensure_device_token_column(conn: sqlite3.Connection) -> None:
    columns = {row["name"] for row in conn.execute("PRAGMA table_info(devices)").fetchall()}
    if "device_code" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN device_code TEXT")
        conn.commit()
    if "device_token" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN device_token TEXT")
        conn.commit()
    if "push_token" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN push_token TEXT")
        conn.commit()
    if "location_services_enabled" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN location_services_enabled INTEGER NOT NULL DEFAULT 1")
        conn.commit()
    if "wifi_ssid" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN wifi_ssid TEXT")
        conn.commit()
    if "carrier_name" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN carrier_name TEXT")
        conn.commit()
    if "local_ip" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN local_ip TEXT")
        conn.commit()
    if "public_ip" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN public_ip TEXT")
        conn.commit()
    if "isp_name" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN isp_name TEXT")
        conn.commit()
    if "device_time_zone" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN device_time_zone TEXT")
        conn.commit()
    if "device_timestamp_ms" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN device_timestamp_ms INTEGER")
        conn.commit()
    hourly_columns = {row["name"] for row in conn.execute("PRAGMA table_info(hourly_reports)").fetchall()}
    if hourly_columns:
        if "device_time_zone" not in hourly_columns:
            conn.execute("ALTER TABLE hourly_reports ADD COLUMN device_time_zone TEXT")
            conn.commit()
        if "device_timestamp_ms" not in hourly_columns:
            conn.execute("ALTER TABLE hourly_reports ADD COLUMN device_timestamp_ms INTEGER")
            conn.commit()
    rows_missing_code = conn.execute(
        "SELECT id FROM devices WHERE device_code IS NULL OR device_code = ''"
    ).fetchall()
    for row in rows_missing_code:
        conn.execute(
            "UPDATE devices SET device_code = ? WHERE id = ?",
            (generate_unique_device_code(conn), row["id"]),
        )
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_device_token ON devices(device_token)")
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_device_code ON devices(device_code)")
    conn.commit()


def generate_unique_device_code(conn: sqlite3.Connection) -> str:
    while True:
        code = generate_device_code()
        exists = conn.execute(
            "SELECT 1 FROM devices WHERE device_code = ?",
            (code,),
        ).fetchone()
        if not exists:
            return code


def resolve_device_row(conn: sqlite3.Connection, public_id: str):
    row = conn.execute(
        "SELECT * FROM devices WHERE device_code = ?",
        (public_id,),
    ).fetchone()
    if row:
        return row
    if public_id.isdigit():
        return conn.execute("SELECT * FROM devices WHERE id = ?", (public_id,)).fetchone()
    return None


class TrackerHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/":
            return self.serve_file("index.html", "text/html; charset=utf-8")
        if parsed.path == "/app.js":
            return self.serve_file("app.js", "application/javascript; charset=utf-8")
        if parsed.path == "/styles.css":
            return self.serve_file("styles.css", "text/css; charset=utf-8")
        if parsed.path == "/api/devices":
            return self.list_devices(parse_qs(parsed.query))
        if parsed.path == "/api/health":
            return json_response(self, {"ok": True, "time": now_iso()})
        if parsed.path.startswith("/api/devices/"):
            return self.route_device_get(parsed.path)
        return self.send_error(HTTPStatus.NOT_FOUND, "Not found")

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/devices":
            return self.create_device()
        if parsed.path.startswith("/api/devices/"):
            return self.route_device_post(parsed.path)
        return self.send_error(HTTPStatus.NOT_FOUND, "Not found")

    def do_DELETE(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path.startswith("/api/devices/"):
            return self.route_device_delete(parsed.path)
        return self.send_error(HTTPStatus.NOT_FOUND, "Not found")

    def log_message(self, format: str, *args) -> None:
        return

    def serve_file(self, filename: str, content_type: str) -> None:
        path = STATIC_DIR / filename
        if not path.exists():
            return self.send_error(HTTPStatus.NOT_FOUND, "Missing static file")
        data = path.read_bytes()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def list_devices(self, query: dict) -> None:
        include_history = query.get("includeHistory", ["false"])[0].lower() == "true"
        conn = get_db()
        try:
            rows = conn.execute(
                "SELECT * FROM devices ORDER BY updated_at DESC, id DESC"
            ).fetchall()
            devices = [row_to_device(row) for row in rows]
            if include_history:
                for row, device in zip(rows, devices):
                    device["history"] = self.fetch_history(conn, row["id"])
                    device["actions"] = self.fetch_actions(conn, row["id"])
            json_response(self, {"devices": devices, "serverTime": now_iso()})
        finally:
            conn.close()

    def route_device_get(self, path: str) -> None:
        parts = path.strip("/").split("/")
        if len(parts) == 3 and parts[0] == "api" and parts[1] == "devices":
            return self.get_device(parts[2])
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "devices" and parts[3] == "history":
            return self.get_history(parts[2])
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "devices" and parts[3] == "commands":
            return self.get_commands(parts[2])
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "devices" and parts[3] == "hourly-report.csv":
            return self.download_hourly_report(parts[2])
        return self.send_error(HTTPStatus.NOT_FOUND, "Unknown device route")

    def route_device_post(self, path: str) -> None:
        parts = path.strip("/").split("/")
        if parts[0] != "api" or parts[1] != "devices":
            return self.send_error(HTTPStatus.NOT_FOUND, "Unknown device route")
        if len(parts) == 4:
            device_id = parts[2]
            action = parts[3]
            if action == "location":
                return self.record_location(device_id)
            if action == "hourly-reports":
                return self.record_hourly_report(device_id)
            if action == "actions":
                return self.record_action(device_id)
            if action == "commands":
                return self.create_command(device_id)
        if len(parts) == 6 and parts[3] == "commands" and parts[5] == "complete":
            return self.complete_command(parts[2], parts[4])
        return self.send_error(HTTPStatus.NOT_FOUND, "Unknown device action")

    def route_device_delete(self, path: str) -> None:
        parts = path.strip("/").split("/")
        if len(parts) == 3 and parts[0] == "api" and parts[1] == "devices":
            return self.delete_device(parts[2])
        return self.send_error(HTTPStatus.NOT_FOUND, "Unknown device route")

    def create_device(self) -> None:
        payload = parse_json(self)
        name = (payload.get("name") or "").strip()
        owner_email = (payload.get("ownerEmail") or "").strip()
        platform = (payload.get("platform") or "android").strip().lower()
        device_token = (payload.get("deviceToken") or "").strip() or None
        push_token = (payload.get("pushToken") or "").strip() or None
        if not name or not owner_email:
            return json_response(self, {"error": "name and ownerEmail are required"}, status=400)

        timestamp = now_iso()
        conn = get_db()
        try:
            ensure_device_token_column(conn)
            existing = None
            if device_token:
                existing = conn.execute(
                    "SELECT * FROM devices WHERE device_token = ?",
                    (device_token,),
                ).fetchone()

            if existing:
                device_pk = existing["id"]
                conn.execute(
                    """
                    UPDATE devices
                    SET name = ?, owner_email = ?, platform = ?, push_token = ?, updated_at = ?, last_seen_at = ?
                    WHERE id = ?
                    """,
                    (name, owner_email, platform, push_token, timestamp, timestamp, device_pk),
                )
                conn.execute(
                    """
                    INSERT INTO device_actions (device_id, action_type, notes, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    (device_pk, "reconnected", "Device reconnected with existing token.", timestamp),
                )
            else:
                device_code = generate_unique_device_code(conn)
                cursor = conn.execute(
                    """
                    INSERT INTO devices (
                        device_code, name, owner_email, platform, device_token, created_at, updated_at, last_seen_at
                        , push_token
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (device_code, name, owner_email, platform, device_token, timestamp, timestamp, timestamp, push_token),
                )
                device_pk = cursor.lastrowid
                conn.execute(
                    """
                    INSERT INTO device_actions (device_id, action_type, notes, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    (device_pk, "connected", "Device connected from the app.", timestamp),
                )
            conn.commit()
            row = conn.execute("SELECT * FROM devices WHERE id = ?", (device_pk,)).fetchone()
            json_response(self, {"device": row_to_device(row)}, status=200 if existing else 201)
        finally:
            conn.close()

    def get_device(self, device_id: str) -> None:
        conn = get_db()
        try:
            row = resolve_device_row(conn, device_id)
            if not row:
                return json_response(self, {"error": "device not found"}, status=404)
            device = row_to_device(row)
            device["history"] = self.fetch_history(conn, row["id"])
            device["actions"] = self.fetch_actions(conn, row["id"])
            json_response(self, {"device": device})
        finally:
            conn.close()

    def get_history(self, device_id: str) -> None:
        conn = get_db()
        try:
            row = resolve_device_row(conn, device_id)
            if not row:
                return json_response(self, {"error": "device not found"}, status=404)
            json_response(self, {"history": self.fetch_history(conn, row["id"])})
        finally:
            conn.close()

    def get_commands(self, device_id: str) -> None:
        conn = get_db()
        try:
            row = resolve_device_row(conn, device_id)
            if not row:
                return json_response(self, {"error": "device not found"}, status=404)
            rows = conn.execute(
                """
                SELECT id, command_type, status, payload, created_at, completed_at
                FROM device_commands
                WHERE device_id = ? AND status = 'pending'
                ORDER BY created_at ASC
                """,
                (row["id"],),
            ).fetchall()
            commands = [
                {
                    "id": row["id"],
                    "commandType": row["command_type"],
                    "status": row["status"],
                    "payload": json.loads(row["payload"]) if row["payload"] else None,
                    "createdAt": row["created_at"],
                    "completedAt": row["completed_at"],
                }
                for row in rows
            ]
            json_response(self, {"commands": commands})
        finally:
            conn.close()

    def fetch_history(self, conn: sqlite3.Connection, device_id: str) -> list[dict]:
        rows = conn.execute(
            """
            SELECT id, latitude, longitude, accuracy_m, battery_level, network_status, created_at
            FROM location_events
            WHERE device_id = ?
            ORDER BY created_at DESC
            LIMIT 20
            """,
            (device_id,),
        ).fetchall()
        return [
            {
                "id": row["id"],
                "latitude": row["latitude"],
                "longitude": row["longitude"],
                "accuracyM": row["accuracy_m"],
                "batteryLevel": row["battery_level"],
                "networkStatus": row["network_status"],
                "createdAt": row["created_at"],
            }
            for row in rows
        ]

    def fetch_actions(self, conn: sqlite3.Connection, device_id: str) -> list[dict]:
        rows = conn.execute(
            """
            SELECT id, action_type, notes, created_at
            FROM device_actions
            WHERE device_id = ?
            ORDER BY created_at DESC
            LIMIT 20
            """,
            (device_id,),
        ).fetchall()
        return [
            {
                "id": row["id"],
                "actionType": row["action_type"],
                "notes": row["notes"],
                "createdAt": row["created_at"],
            }
            for row in rows
        ]

    def record_location(self, device_id: str) -> None:
        payload = parse_json(self)
        accuracy_m = payload.get("accuracyM")
        battery_level = int(payload.get("batteryLevel", 0))
        is_charging = 1 if payload.get("isCharging") else 0
        network_status = (payload.get("networkStatus") or "online").strip()
        wifi_ssid = (payload.get("wifiSsid") or "").strip() or None
        carrier_name = (payload.get("carrierName") or "").strip() or None
        local_ip = (payload.get("localIp") or "").strip() or None
        public_ip = (payload.get("publicIp") or "").strip() or None
        isp_name = (payload.get("ispName") or "").strip() or None
        push_token = (payload.get("pushToken") or "").strip() or None
        device_time_zone = (payload.get("deviceTimeZone") or "").strip() or None
        device_timestamp_ms = payload.get("deviceTimestampMs")
        location_services_enabled = 0 if payload.get("locationServicesEnabled") is False else 1
        has_coordinates = payload.get("latitude") is not None and payload.get("longitude") is not None
        received_at = now_iso()
        captured_at = (payload.get("capturedAt") or "").strip()
        try:
            event_timestamp = datetime.fromisoformat(captured_at).astimezone(timezone.utc).isoformat() if captured_at else received_at
        except ValueError:
            event_timestamp = received_at

        conn = get_db()
        try:
            existing = resolve_device_row(conn, device_id)
            if not existing:
                return json_response(self, {"error": "device not found"}, status=404)
            device_pk = existing["id"]

            wifi_ssid = prefer_value(wifi_ssid, existing["wifi_ssid"])
            carrier_name = prefer_value(carrier_name, existing["carrier_name"])
            local_ip = prefer_value(local_ip, existing["local_ip"])
            public_ip = prefer_value(public_ip, existing["public_ip"])
            isp_name = prefer_value(isp_name, existing["isp_name"])
            push_token = prefer_value(push_token, existing["push_token"])
            device_time_zone = prefer_value(device_time_zone, existing["device_time_zone"])
            if device_timestamp_ms in (None, ""):
                device_timestamp_ms = existing["device_timestamp_ms"]
            else:
                try:
                    device_timestamp_ms = int(device_timestamp_ms)
                except (TypeError, ValueError):
                    device_timestamp_ms = existing["device_timestamp_ms"]

            if has_coordinates:
                try:
                    latitude = float(payload["latitude"])
                    longitude = float(payload["longitude"])
                except (KeyError, TypeError, ValueError):
                    return json_response(self, {"error": "latitude and longitude are required"}, status=400)

                conn.execute(
                    """
                    INSERT INTO location_events (
                        device_id, latitude, longitude, accuracy_m, battery_level, network_status, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (device_pk, latitude, longitude, accuracy_m, battery_level, network_status, event_timestamp),
                )
                conn.execute(
                    """
                    UPDATE devices
                    SET battery_level = ?, is_charging = ?, network_status = ?, wifi_ssid = ?, carrier_name = ?, local_ip = ?, public_ip = ?, isp_name = ?, location_services_enabled = ?,
                        push_token = ?, device_time_zone = ?, device_timestamp_ms = ?, last_latitude = ?, last_longitude = ?, last_seen_at = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        battery_level,
                        is_charging,
                        network_status,
                        wifi_ssid,
                        carrier_name,
                        local_ip,
                        public_ip,
                        isp_name,
                        location_services_enabled,
                        push_token,
                        device_time_zone,
                        device_timestamp_ms,
                        latitude,
                        longitude,
                        received_at,
                        received_at,
                        device_pk,
                    ),
                )
            else:
                conn.execute(
                    """
                    UPDATE devices
                    SET battery_level = ?, is_charging = ?, network_status = ?, wifi_ssid = ?, carrier_name = ?, local_ip = ?, public_ip = ?, isp_name = ?, location_services_enabled = ?,
                        push_token = ?, device_time_zone = ?, device_timestamp_ms = ?, last_seen_at = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        battery_level,
                        is_charging,
                        network_status,
                        wifi_ssid,
                        carrier_name,
                        local_ip,
                        public_ip,
                        isp_name,
                        location_services_enabled,
                        push_token,
                        device_time_zone,
                        device_timestamp_ms,
                        received_at,
                        received_at,
                        device_pk,
                    ),
                )
                if location_services_enabled == 0:
                    conn.execute(
                        """
                        INSERT INTO device_actions (device_id, action_type, notes, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                        (device_pk, "location_disabled", "Location services are disabled on the device.", received_at),
                    )
            conn.commit()
            json_response(self, {"ok": True, "recordedAt": received_at, "capturedAt": event_timestamp})
        finally:
            conn.close()

    def record_action(self, device_id: str) -> None:
        payload = parse_json(self)
        action_type = (payload.get("actionType") or "").strip().lower()
        notes = (payload.get("notes") or "").strip()
        allowed = {"mark_lost", "mark_found", "ring", "lock", "wipe"}
        if action_type not in allowed:
            return json_response(self, {"error": f"actionType must be one of {sorted(allowed)}"}, status=400)

        status = "lost" if action_type == "mark_lost" else "active" if action_type == "mark_found" else None
        timestamp = now_iso()
        conn = get_db()
        try:
            row = resolve_device_row(conn, device_id)
            if not row:
                return json_response(self, {"error": "device not found"}, status=404)
            device_pk = row["id"]

            conn.execute(
                """
                INSERT INTO device_actions (device_id, action_type, notes, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (device_pk, action_type, notes, timestamp),
            )
            if status:
                conn.execute(
                    "UPDATE devices SET status = ?, updated_at = ? WHERE id = ?",
                    (status, timestamp, device_pk),
                )
            else:
                conn.execute(
                    "UPDATE devices SET updated_at = ? WHERE id = ?",
                    (timestamp, device_pk),
                )
            conn.commit()
            json_response(self, {"ok": True, "recordedAt": timestamp})
        finally:
            conn.close()

    def create_command(self, device_id: str) -> None:
        payload = parse_json(self)
        command_type = (payload.get("commandType") or "").strip().lower()
        allowed = {"request_location", "request_details", "hard_fetch"}
        if command_type not in allowed:
            return json_response(self, {"error": f"commandType must be one of {sorted(allowed)}"}, status=400)

        timestamp = now_iso()
        conn = get_db()
        try:
            row = resolve_device_row(conn, device_id)
            if not row:
                return json_response(self, {"error": "device not found"}, status=404)
            device_pk = row["id"]

            cursor = conn.execute(
                """
                INSERT INTO device_commands (device_id, command_type, status, payload, created_at)
                VALUES (?, ?, 'pending', ?, ?)
                """,
                (device_pk, command_type, json.dumps(payload.get("payload") or {}), timestamp),
            )
            action_notes = {
                "request_location": "Dashboard requested a location refresh.",
                "request_details": "Dashboard requested a fresh device details update.",
                "hard_fetch": "Dashboard requested a hard fetch with immediate status and location refresh.",
            }
            conn.execute(
                """
                INSERT INTO device_actions (device_id, action_type, notes, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (device_pk, command_type, action_notes[command_type], timestamp),
            )
            conn.commit()
            push_attempted = False
            push_delivered = False
            push_error = None
            if row["push_token"]:
                push_attempted = True
                push_delivered, push_error = send_fcm_wakeup(
                    row["push_token"],
                    row["device_code"],
                    command_type,
                )
            json_response(
                self,
                {
                    "ok": True,
                    "commandId": cursor.lastrowid,
                    "createdAt": timestamp,
                    "pushWakeAttempted": push_attempted,
                    "pushWakeDelivered": push_delivered,
                    "pushWakeError": push_error,
                },
                status=201,
            )
        finally:
            conn.close()

    def complete_command(self, device_id: str, command_id: str) -> None:
        payload = parse_json(self)
        timestamp = now_iso()
        conn = get_db()
        try:
            row = resolve_device_row(conn, device_id)
            if not row:
                return json_response(self, {"error": "device not found"}, status=404)
            device_pk = row["id"]
            command = conn.execute(
                "SELECT id, command_type, status FROM device_commands WHERE id = ? AND device_id = ?",
                (command_id, device_pk),
            ).fetchone()
            if not command:
                return json_response(self, {"error": "command not found"}, status=404)
            if command["status"] == "completed":
                return json_response(self, {"ok": True, "completedAt": timestamp, "alreadyCompleted": True})

            conn.execute(
                "UPDATE device_commands SET status = 'completed', completed_at = ? WHERE id = ?",
                (timestamp, command_id),
            )
            conn.execute(
                """
                INSERT INTO device_actions (device_id, action_type, notes, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (
                    device_pk,
                    "command_completed",
                    f"{command['command_type']} completed. {payload.get('notes') or ''}".strip(),
                    timestamp,
                ),
            )
            conn.commit()
            json_response(self, {"ok": True, "completedAt": timestamp})
        finally:
            conn.close()

    def delete_device(self, device_id: str) -> None:
        conn = get_db()
        try:
            row = resolve_device_row(conn, device_id)
            if not row:
                return json_response(self, {"error": "device not found"}, status=404)
            device_pk = row["id"]
            conn.execute("DELETE FROM location_events WHERE device_id = ?", (device_pk,))
            conn.execute("DELETE FROM device_actions WHERE device_id = ?", (device_pk,))
            conn.execute("DELETE FROM device_commands WHERE device_id = ?", (device_pk,))
            conn.execute("DELETE FROM devices WHERE id = ?", (device_pk,))
            conn.commit()
            json_response(self, {"ok": True, "deletedDeviceId": row["device_code"]})
        finally:
            conn.close()

    def download_hourly_report(self, device_id: str) -> None:
        conn = get_db()
        try:
            row = resolve_device_row(conn, device_id)
            if not row:
                return json_response(self, {"error": "device not found"}, status=404)

            reports = conn.execute(
                """
                SELECT status, failure_reason, latitude, longitude, last_known_latitude, last_known_longitude,
                       accuracy_m, battery_level, network_status, captured_at
                FROM hourly_reports
                WHERE device_id = ?
                ORDER BY captured_at ASC
                """,
                (row["id"],),
            ).fetchall()

            csv_rows = [[
                "device_id",
                "device_name",
                "status",
                "reason",
                "date",
                "time",
                "latitude",
                "longitude",
                "last_known_latitude",
                "last_known_longitude",
                "accuracy_m",
                "battery_level",
                "network_status",
                "captured_at",
            ]]
            for report in reports:
                captured_at = datetime.fromisoformat(report["captured_at"])
                local_dt = captured_at.astimezone()
                csv_rows.append([
                    row["device_code"],
                    row["name"],
                    report["status"],
                    report["failure_reason"] or "",
                    local_dt.strftime("%Y-%m-%d"),
                    local_dt.strftime("%H:%M:%S"),
                    "" if report["latitude"] is None else f'{float(report["latitude"]):.8f}',
                    "" if report["longitude"] is None else f'{float(report["longitude"]):.8f}',
                    "" if report["last_known_latitude"] is None else f'{float(report["last_known_latitude"]):.8f}',
                    "" if report["last_known_longitude"] is None else f'{float(report["last_known_longitude"]):.8f}',
                    "" if report["accuracy_m"] is None else f'{float(report["accuracy_m"]):.2f}',
                    "" if report["battery_level"] is None else str(report["battery_level"]),
                    report["network_status"] or "",
                    captured_at.isoformat(),
                ])

            filename = f'{row["device_code"]}-hourly-report.csv'
            csv_response(self, filename, csv_rows)
        finally:
            conn.close()

    def record_hourly_report(self, device_id: str) -> None:
        payload = parse_json(self)
        hour_key = (payload.get("hourKey") or "").strip()
        status = (payload.get("status") or "").strip().lower()
        failure_reason = (payload.get("reason") or "").strip() or None
        battery_level = int(payload.get("batteryLevel", 0))
        network_status = (payload.get("networkStatus") or "online").strip()
        device_time_zone = (payload.get("deviceTimeZone") or "").strip() or None
        device_timestamp_ms = payload.get("deviceTimestampMs")
        captured_at = (payload.get("capturedAt") or "").strip() or now_iso()
        created_at = now_iso()

        if not hour_key or status not in {"success", "location_unavailable"}:
            return json_response(self, {"error": "hourKey and valid status are required"}, status=400)

        latitude = payload.get("latitude")
        longitude = payload.get("longitude")
        last_known_latitude = payload.get("lastKnownLatitude")
        last_known_longitude = payload.get("lastKnownLongitude")
        accuracy_m = payload.get("accuracyM")
        try:
            device_timestamp_ms = int(device_timestamp_ms) if device_timestamp_ms not in (None, "") else None
        except (TypeError, ValueError):
            device_timestamp_ms = None

        conn = get_db()
        try:
            row = resolve_device_row(conn, device_id)
            if not row:
                return json_response(self, {"error": "device not found"}, status=404)

            conn.execute(
                """
                INSERT INTO hourly_reports (
                    device_id, hour_key, status, failure_reason, latitude, longitude,
                    last_known_latitude, last_known_longitude, accuracy_m, battery_level,
                    network_status, device_time_zone, device_timestamp_ms, captured_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id, hour_key) DO UPDATE SET
                    status = excluded.status,
                    failure_reason = excluded.failure_reason,
                    latitude = excluded.latitude,
                    longitude = excluded.longitude,
                    last_known_latitude = excluded.last_known_latitude,
                    last_known_longitude = excluded.last_known_longitude,
                    accuracy_m = excluded.accuracy_m,
                    battery_level = excluded.battery_level,
                    network_status = excluded.network_status,
                    device_time_zone = excluded.device_time_zone,
                    device_timestamp_ms = excluded.device_timestamp_ms,
                    captured_at = excluded.captured_at,
                    created_at = excluded.created_at
                """,
                (
                    row["id"],
                    hour_key,
                    status,
                    failure_reason,
                    latitude,
                    longitude,
                    last_known_latitude,
                    last_known_longitude,
                    accuracy_m,
                    battery_level,
                    network_status,
                    device_time_zone,
                    device_timestamp_ms,
                    captured_at,
                    created_at,
                ),
            )
            conn.commit()
            json_response(self, {"ok": True, "hourKey": hour_key, "capturedAt": captured_at}, status=201)
        finally:
            conn.close()


def main() -> None:
    init_db()
    conn = get_db()
    try:
        ensure_device_token_column(conn)
    finally:
        conn.close()
    server = ThreadingHTTPServer((HOST, PORT), TrackerHandler)
    print(f"Lost phone tracker running at http://{HOST}:{PORT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
