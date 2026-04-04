# Lost Phone Tracker MVP

Small local prototype for a lost phone tracking system. It includes:

- device enrollment
- location ping ingestion
- last seen location and battery status
- owner actions for lost mode, ring, lock, and found
- SQLite persistence

## Run

```bash
cd /home/lazzy/Desktop/lost-phone-tracker-suite/backend
HOST=0.0.0.0 PORT=8091 python3 server.py
```

Open `http://127.0.0.1:8091` on the computer, or use `http://YOUR-LAN-IP:8091` from the Android phone.

## API

- `GET /api/devices?includeHistory=true`
- `GET /api/health`
- `POST /api/devices`
- `POST /api/devices/:id/location`
- `POST /api/devices/:id/actions`

## Notes

- The frontend is intentionally dependency-free and uses a simulated location ping form in place of a real mobile app.
- The Android app can send a stable `deviceToken` so reinstalling the APK reuses the same device record.
- A real production system would need authenticated users, signed device tokens, encrypted transport, push notifications, and anti-stalking controls.
