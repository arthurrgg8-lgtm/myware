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

## Storage

Runtime data is stored in:

`backend/data/tracker.db`

That directory is intentionally git-ignored.
