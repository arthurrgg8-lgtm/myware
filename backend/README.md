# Backend

This folder contains the Python backend for Google Services.

## Files

- `server.py`: HTTP server and API routes
- `static/`: browser dashboard assets
- `data/`: SQLite database directory created at runtime

## Run

```bash
cd /home/lazzy/Desktop/myware/backend
HOST=0.0.0.0 PORT=8091 python3 server.py
```

Open:

`http://127.0.0.1:8091/`

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

## Storage

Runtime data is stored in:

`backend/data/tracker.db`

That directory is intentionally git-ignored.
