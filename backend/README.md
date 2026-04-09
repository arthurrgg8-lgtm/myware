# Backend

This folder contains the Python backend for Google Services.

## Files

- `server.py`: HTTP server and API routes
- `static/`: browser dashboard assets
- `data/`: SQLite database directory created at runtime

## Run

```bash
cd /home/lazzy/Desktop/myware/backend
HOST=127.0.0.1 PORT=8091 python3 server.py
```

Before running, set:

- `ADMIN_TOKEN`: required for dashboard/API operator access
- `DEVICE_API_TOKEN`: required for Android device enroll/sync traffic
- `FCM_SERVICE_ACCOUNT_JSON`: optional override for the Firebase service account path

You can place them in:

`~/.config/google-services/backend.env`

Example:

```bash
ADMIN_TOKEN='YOUR_DASHBOARD_ADMIN_TOKEN'
DEVICE_API_TOKEN='YOUR_ANDROID_DEVICE_API_TOKEN'
FCM_SERVICE_ACCOUNT_JSON='/absolute/path/to/firebase-service-account.json'
```

Open:

`http://127.0.0.1:8091/`

## Domain Deployment

Current intended public domain:

`https://app.anuditk.com.np`

Recommended production shape:

- run `server.py` on `127.0.0.1:8091`
- run `cloudflared` locally and publish that backend through the permanent tunnel
- point `app.anuditk.com.np` at the Cloudflare Tunnel route

Local backend:

```bash
cd /home/lazzy/Desktop/myware/backend
HOST=127.0.0.1 PORT=8091 python3 server.py
```

Cloudflare Tunnel:

```bash
cd /home/lazzy/Desktop/myware
CLOUDFLARED_TOKEN='YOUR_TUNNEL_TOKEN' bash ./launch-cloudflare-tunnel.sh
```

The helper script:

`launch-cloudflare-tunnel.sh`

publishes `http://127.0.0.1:8091` through `cloudflared tunnel --url ... --token ...`.

## API Overview

- `GET /`: dashboard HTML
- `GET /api/health`: backend health check
- `GET /api/devices?includeHistory=true`: list devices with history and actions
- `GET /api/devices/<id>`: single device payload
- `GET /api/devices/<id>/hourly-report.csv`: CSV export for one device
- `POST /api/devices`: enroll or reconnect a device
- `POST /api/devices/<id>/location`: record a location or status update
- `POST /api/devices/<id>/hourly-reports`: record an hourly report row
- `POST /api/devices/<id>/commands`: create a device command such as `request_location`
- `GET /api/devices/<id>/commands`: fetch pending device commands
- `DELETE /api/devices/<id>`: remove a device from the backend

## Current Device Behavior

- Installed phones poll `GET /api/devices/<id>/commands` every 2 seconds for fast dashboard-triggered actions.
- `POST /api/devices/<id>/location` is used both for dashboard-requested fresh locations and for fast status-only updates such as Wi-Fi, carrier, IP, and network-state changes.
- `POST /api/devices/<id>/hourly-reports` stores the queued hourly CSV/report rows that the phone syncs when the backend becomes reachable.
- If a device was deleted from the dashboard but the APK is still installed, the phone can automatically enroll again and reappear as a new backend device record.

## Dependencies

- Python 3 standard library
- `PyJWT` for FCM OAuth token generation

## Storage

Runtime data is stored in:

`backend/data/tracker.db`

That directory is intentionally git-ignored.
