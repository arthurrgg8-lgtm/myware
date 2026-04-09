# Handoff Guide

This file is the operational handoff for a new human maintainer or another AI agent.

Use this document when you need to:

- understand what this repository does without prior chat context
- move the live stack to another PC
- rebuild the APK
- bring the backend/dashboard back up safely
- know which files are code vs machine-local state

## Current Recommended Setup

At the moment, the safest practical setup is:

- main laptop for development, APK builds, and local-only dashboard use
- secondary laptop for the always-on live backend, if continuous remote sync is required

Important current policy:

- Firebase JSON credentials must stay local only
- runtime secret files must stay local only
- live SQLite runtime data must stay local only
- do not commit them to git again

## System Summary

This repository is a phone-tracking stack with three parts:

- Android app in `android-app/`
- Python backend in `backend/`
- browser dashboard in `backend/static/`

The current intended runtime shape is:

- backend runs locally on `127.0.0.1:8091`
- Cloudflare Tunnel publishes it at `https://app.anuditk.com.np`
- Android phones talk to that public URL
- operators use the dashboard with an admin token

Current state on the main laptop:

- local backend use is preferred here
- public/live tunnel is currently stopped unless intentionally started again
- newest Firebase service-account JSON path in local config is:
  - `/home/lazzy/Desktop/myware-1f5c4-cc1f227edf40.json`
- latest local desktop APK copy is:
  - `/home/lazzy/Desktop/googleservice.apk`

## Core Runtime Files

Important source files:

- `backend/server.py`
- `backend/config.py`
- `backend/auth.py`
- `backend/db.py`
- `backend/fcm.py`
- `backend/routes.py`
- `backend/static/index.html`
- `backend/static/app.js`
- `backend/static/styles.css`
- `android-app/app/src/main/java/com/lazzy/losttracker/MainActivity.kt`
- `android-app/app/src/main/java/com/lazzy/losttracker/TrackerService.kt`
- `android-app/app/src/main/java/com/lazzy/losttracker/ApiClient.kt`
- `android-app/app/src/main/java/com/lazzy/losttracker/DeviceStatus.kt`
- `android-app/app/src/main/java/com/lazzy/losttracker/DeviceCompatibility.kt`
- `android-app/app/src/main/java/com/lazzy/losttracker/TrackerPrefs.kt`
- `android-app/app/src/main/java/com/lazzy/losttracker/HourlyReportStore.kt`

Important runtime files that are not committed as portable repo state:

- `backend/data/tracker.db`
- `~/.config/google-services/backend.env`
- `~/.config/google-services/cloudflare.env`
- Firebase Admin service-account JSON file
- `android-app/local.properties`

These files are intentionally local only and should be copied manually or through a private migration bundle when moving machines.

## Authentication Model

There are two separate secrets:

- `ADMIN_TOKEN`
  Used by the dashboard/operator side.
- `DEVICE_API_TOKEN`
  Used by the Android app for enroll, heartbeat, location upload, hourly-report upload, command polling, and command completion.

The dashboard stores the admin token in browser local storage after login.

The APK must be built with a `TRACKER_API_TOKEN` that matches backend `DEVICE_API_TOKEN`.

## Local Config Files

### Backend env

Expected path:

`~/.config/google-services/backend.env`

Example:

```bash
ADMIN_TOKEN='YOUR_DASHBOARD_ADMIN_TOKEN'
DEVICE_API_TOKEN='YOUR_ANDROID_DEVICE_API_TOKEN'
FCM_SERVICE_ACCOUNT_JSON='/absolute/path/to/firebase-service-account.json'
```

### Cloudflare env

Expected path:

`~/.config/google-services/cloudflare.env`

Example:

```bash
CLOUDFLARED_TOKEN='YOUR_TUNNEL_TOKEN'
```

## Bring-Up Steps On This PC

### Backend and tunnel

Recommended:

```bash
bash /home/lazzy/Desktop/myware/launch-google-services-stack.sh
```

This starts:

- backend on `127.0.0.1:8091`
- Cloudflare tunnel to `app.anuditk.com.np`

Use this only on the machine that should be the active live/public host.

### Backend only

```bash
cd /home/lazzy/Desktop/myware/backend
HOST=127.0.0.1 PORT=8091 python3 server.py
```

Convenience command:

```bash
bash /home/lazzy/Desktop/myware/launch-google-services.sh
```

Use this on the development/main laptop when you want local-only operation.

### Dashboard login

Open:

`http://127.0.0.1:8091/`

Then enter `ADMIN_TOKEN` in the dashboard login field and click `Login`.

## APK Build And Install

### Build

```bash
cd /home/lazzy/Desktop/myware/android-app
TRACKER_API_TOKEN='YOUR_ANDROID_DEVICE_API_TOKEN' ./gradlew assembleDebug
```

Output:

`/home/lazzy/Desktop/myware/android-app/app/build/outputs/apk/debug/app-debug.apk`

### Install with adb

```bash
/home/lazzy/Android/Sdk/platform-tools/adb install -r '/home/lazzy/Desktop/myware/android-app/app/build/outputs/apk/debug/app-debug.apk'
```

Desktop copy currently used for convenience:

`/home/lazzy/Desktop/googleservice.apk`

## Data Model And Persistence

Backend runtime data is stored in SQLite:

`backend/data/tracker.db`

Main logical records:

- devices
- location events
- device actions
- device commands
- hourly reports

If you do not copy `tracker.db` to a new machine, the new backend starts empty.

## Migration To Another PC

To reproduce the same live system on another PC, copy all of the following:

- repository code
- `~/.config/google-services/backend.env`
- `~/.config/google-services/cloudflare.env`
- Firebase service-account JSON file
- `backend/data/tracker.db`

Minimum clone command:

```bash
git clone https://github.com/arthurrgg8-lgtm/myware.git /path/to/myware
```

After cloning on the new PC:

1. Create `~/.config/google-services/`
2. Copy `backend.env`
3. Copy `cloudflare.env`
4. Copy Firebase service-account JSON and update `FCM_SERVICE_ACCOUNT_JSON` if the path changed
5. Copy `backend/data/tracker.db` if you want the same live history/devices
6. Install Python 3, PyJWT, cloudflared, JDK, Android SDK as needed
7. Build the APK with the same `DEVICE_API_TOKEN` value
8. Start `launch-google-services-stack.sh`

Best target machine for the live role:

- a secondary laptop or always-on machine
- not the main development laptop if that machine is often turned off

## Important Safety Rules

- Only one backend should be the active live backend behind the public tunnel/domain at a time unless you intentionally redesign deployment.
- Do not rotate `DEVICE_API_TOKEN` unless you also rebuild and reinstall APKs that must keep talking to the backend.
- Do not assume the Git repo contains the live database or secrets.
- Do not commit Firebase JSON files, env files, or the live database to git.
- If you move the repository to a different path, update any absolute-path launcher or desktop files.

## Validation Checklist

After setup or migration, verify:

1. `GET /api/health` works locally
2. the public route answers `https://app.anuditk.com.np/api/health`
3. dashboard login accepts `ADMIN_TOKEN`
4. at least one device appears in `/api/devices`
5. `Request location` works
6. `Refresh details` works
7. CSV export works
8. offline hourly rows later sync back to the backend

## Observability Notes

The dashboard currently shows:

- live vs attention state
- heartbeat age
- location health
- command flow health
- background mode reported by device
- compatibility profile reported by device
- reconnect count

This is the fastest way to spot OEM-specific background issues after installing the APK on a phone.

## Tests

Backend integration tests:

```bash
cd /home/lazzy/Desktop/myware
python3 -m unittest backend.tests.test_backend
```

Android compile check:

```bash
cd /home/lazzy/Desktop/myware/android-app
./gradlew :app:compileDebugKotlin
```

## If Another AI Agent Takes Over

Read these files first:

- `README.md`
- `HANDOFF.md`
- `backend/README.md`
- `backend/routes.py`
- `android-app/app/src/main/java/com/lazzy/losttracker/TrackerService.kt`
- `android-app/app/src/main/java/com/lazzy/losttracker/DeviceCompatibility.kt`

Then verify:

- current git branch
- uncommitted changes
- backend env and cloudflare env presence
- whether `backend/data/tracker.db` is the intended live database
