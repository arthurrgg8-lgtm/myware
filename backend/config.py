import os
from dataclasses import dataclass
from pathlib import Path


BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
DEFAULT_DATA_DIR = BASE_DIR / "data"
DATA_DIR = Path(os.environ.get("DATA_DIR", DEFAULT_DATA_DIR))
DB_PATH = Path(os.environ.get("DB_PATH", DATA_DIR / "tracker.db"))
DEFAULT_FCM_SERVICE_ACCOUNT_PATH = "/home/lazzy/Desktop/myware-1f5c4-firebase-adminsdk-fbsvc-e1ebd93477.json"
DEFAULT_CONFIG_ENV_FILE = Path.home() / ".config" / "google-services" / "backend.env"


def _load_env_file(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("'").strip('"'))


_load_env_file(Path(os.environ.get("CONFIG_ENV_FILE", DEFAULT_CONFIG_ENV_FILE)))


@dataclass(frozen=True)
class Settings:
    host: str
    port: int
    base_dir: Path
    static_dir: Path
    data_dir: Path
    db_path: Path
    admin_token: str
    device_api_token: str
    fcm_service_account_path: str


SETTINGS = Settings(
    host=os.environ.get("HOST", "127.0.0.1"),
    port=int(os.environ.get("PORT", "8080")),
    base_dir=BASE_DIR,
    static_dir=STATIC_DIR,
    data_dir=DATA_DIR,
    db_path=DB_PATH,
    admin_token=(os.environ.get("ADMIN_TOKEN") or "").strip(),
    device_api_token=(os.environ.get("DEVICE_API_TOKEN") or "").strip(),
    fcm_service_account_path=(
        os.environ.get("FCM_SERVICE_ACCOUNT_JSON") or DEFAULT_FCM_SERVICE_ACCOUNT_PATH
    ).strip(),
)


def validate_settings() -> None:
    missing = []
    if not SETTINGS.admin_token:
        missing.append("ADMIN_TOKEN")
    if not SETTINGS.device_api_token:
        missing.append("DEVICE_API_TOKEN")
    if missing:
        names = ", ".join(missing)
        raise RuntimeError(
            f"Missing required backend configuration: {names}. "
            f"Set them in the environment or in {DEFAULT_CONFIG_ENV_FILE}."
        )
