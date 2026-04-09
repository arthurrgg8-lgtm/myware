import random
import sqlite3

from config import SETTINGS


def generate_device_code() -> str:
    return "".join(str(random.randint(0, 9)) for _ in range(16))


def get_db() -> sqlite3.Connection:
    conn = sqlite3.connect(SETTINGS.db_path)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    SETTINGS.data_dir.mkdir(parents=True, exist_ok=True)
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
                battery_optimization_exempt INTEGER NOT NULL DEFAULT 0,
                compatibility_profile TEXT,
                network_status TEXT NOT NULL DEFAULT 'online',
                wifi_ssid TEXT,
                carrier_name TEXT,
                local_ip TEXT,
                local_ipv6 TEXT,
                public_ip TEXT,
                isp_name TEXT,
                device_time_zone TEXT,
                device_timestamp_ms INTEGER,
                location_services_enabled INTEGER NOT NULL DEFAULT 1,
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
    if "battery_optimization_exempt" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN battery_optimization_exempt INTEGER NOT NULL DEFAULT 0")
        conn.commit()
    if "compatibility_profile" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN compatibility_profile TEXT")
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
    if "local_ipv6" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN local_ipv6 TEXT")
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
        "batteryOptimizationExempt": bool(row["battery_optimization_exempt"]),
        "compatibilityProfile": row["compatibility_profile"],
        "networkStatus": row["network_status"],
        "wifiSsid": row["wifi_ssid"],
        "carrierName": row["carrier_name"],
        "localIp": row["local_ip"],
        "localIpv6": row["local_ipv6"],
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
