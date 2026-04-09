import json
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import jwt

from config import SETTINGS


FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
_FCM_ACCESS_TOKEN = None
_FCM_ACCESS_TOKEN_EXPIRES_AT = 0


def get_fcm_access_token() -> tuple[str | None, str | None, str | None]:
    global _FCM_ACCESS_TOKEN, _FCM_ACCESS_TOKEN_EXPIRES_AT

    service_account_path = SETTINGS.fcm_service_account_path
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
