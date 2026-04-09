# Google Services

Google Services is a PC-hosted device tracking stack with three working parts:

- an Android app that enrolls once and keeps reporting lightweight reachability and requested device data
- a Python backend that stores device state in SQLite and serves the dashboard
- a browser dashboard on the PC, with public phone access routed through `app.anuditk.com.np`

This repository is meant to be understandable and runnable without prior chat context.

## Repository Layout

- `android-app/`: Android client source and Gradle build files
- `backend/`: Python HTTP server, dashboard assets, and SQLite runtime data
- `launch-google-services.sh`: convenience backend launcher on port `8091`
- `launch-cloudflare-tunnel.sh`: Cloudflare Tunnel launcher for `app.anuditk.com.np`
- `launch-google-services-stack.sh`: combined launcher for backend, tunnel, and dashboard
- `Google Services Server.desktop`: desktop launcher for the combined stack

## What The System Does

### Android client

The Android app:

- auto-enrolls after required Android permissions exist
- auto-starts tracking after setup
- stores a stable local device token
- stores an FCM push token for wake support
- exposes a public 16-digit device ID to the dashboard
- keeps a required foreground service notification, but leaves it static and silent
- sends a lightweight heartbeat for `live` / `attention`
- checks dashboard commands every 2 seconds as a baseline
- can also wake on FCM when the backend creates a command
- fetches a fresh location only when the dashboard sends `Request location` or `Hard Fetch`
- sends battery, network, Wi-Fi SSID, IP, ISP, and location data when the handset exposes those values
- keeps routine reachability separate from command-triggered location/detail work
- records hourly report entries locally and syncs them later when the backend becomes reachable again
- records hourly failure states too, such as `location disabled`, `no GPS/network fix`, or `permission missing`
- automatically re-enrolls if the device is deleted from the dashboard while the APK is still installed
- asks the background-run prompt only after setup and only once from the app side
- chooses a compatibility profile based on the device manufacturer for safer background behavior on Samsung, Xiaomi, Vivo, Oppo/Realme, Huawei/Honor, and standard Android devices
- reports battery-optimization exemption state and compatibility profile back to the backend for dashboard observability

### Backend dashboard

The backend:

- stores device state in SQLite
- is split into config, auth, database, FCM, and route modules instead of one large file
- serves the dashboard at `http://127.0.0.1:8091/`
- publishes the same backend publicly through Cloudflare Tunnel at `https://app.anuditk.com.np`
- shows live/attention state from recent real backend contact
- supports `Request location`
- supports `Refresh details`
- supports `Hard Fetch`
- sends FCM wake messages when commands are created
- supports removing a device from the backend
- supports per-device hourly CSV export
- requires separate admin and device API tokens

### Dashboard

The dashboard:

- uses a simple built-in login input for the admin token
- stores the admin token locally in the browser for later authenticated requests
- shows per-device reachability, network, location, and recent event history
- shows extra observability signals such as heartbeat age, location health, command flow health, background mode, compatibility profile, and reconnect count

## Requirements

### Backend

- Python 3
- `PyJWT` for FCM OAuth token generation
- `cloudflared` for the public route
- backend auth tokens for dashboard and device traffic

### Android build

- JDK compatible with the Gradle project
- Android SDK
- `adb` if you want direct install or debugging on a connected phone
- `google-services.json` for Firebase-enabled builds
- `TRACKER_API_TOKEN` set at build time to match the backend

## Run The Backend

### Recommended

```bash
bash /home/lazzy/Desktop/myware/launch-google-services-stack.sh
```

This combined launcher:

- starts the backend on `127.0.0.1:8091`
- waits for `GET /api/health` to succeed locally
- starts the Cloudflare Tunnel for the public APK URL
- opens the local dashboard on this PC
- stops both processes together when you close the terminal

It expects `CLOUDFLARED_TOKEN` either in the environment or in:

`~/.config/google-services/cloudflare.env`

Example file contents:

```bash
CLOUDFLARED_TOKEN='YOUR_TUNNEL_TOKEN'
```

The backend also expects `ADMIN_TOKEN` and `DEVICE_API_TOKEN` either in the environment or in:

`~/.config/google-services/backend.env`

Example file contents:

```bash
ADMIN_TOKEN='YOUR_DASHBOARD_ADMIN_TOKEN'
DEVICE_API_TOKEN='YOUR_ANDROID_DEVICE_API_TOKEN'
FCM_SERVICE_ACCOUNT_JSON='/absolute/path/to/firebase-service-account.json'
```

### Backend Only

```bash
cd /home/lazzy/Desktop/myware/backend
HOST=127.0.0.1 PORT=8091 python3 server.py
```

Dashboard URL:

`http://127.0.0.1:8091/`

The dashboard now requires the `ADMIN_TOKEN`.
Enter it once in the `Admin token` field in the toolbar and click `Login`.

For domain-backed deployment, run the backend locally on `127.0.0.1:8091` and publish it through your permanent Cloudflare Tunnel for:

`https://app.anuditk.com.np`

Example:

```bash
cd /home/lazzy/Desktop/myware
CLOUDFLARED_TOKEN='YOUR_TUNNEL_TOKEN' bash ./launch-cloudflare-tunnel.sh
```

## Build The APK

```bash
cd /home/lazzy/Desktop/myware/android-app
TRACKER_API_TOKEN='YOUR_ANDROID_DEVICE_API_TOKEN' ./gradlew assembleDebug
```

Build output:

`/home/lazzy/Desktop/myware/android-app/app/build/outputs/apk/debug/app-debug.apk`

For FCM-enabled builds, place:

`/home/lazzy/Desktop/myware/android-app/app/google-services.json`

before building.

## Install The APK With adb

```bash
/home/lazzy/Android/Sdk/platform-tools/adb install -r '/home/lazzy/Desktop/myware/android-app/app/build/outputs/apk/debug/app-debug.apk'
```

Check the connected device list:

```bash
/home/lazzy/Android/Sdk/platform-tools/adb devices -l
```

## Connectivity Model

Current default backend URL in the Android app:

`https://app.anuditk.com.np`

### Live updates

For live reporting and dashboard commands, the phone must have a real path to the backend. In the current final setup that path is:

- phone -> `https://app.anuditk.com.np` -> Cloudflare Tunnel -> local backend on the PC

### Command and hourly behavior

- The service keeps heartbeat/reachability separate from heavy work.
- `live` / `attention` should mean recent real backend contact only.
- `Request location` triggers a fresh location fetch only.
- `Refresh details` triggers a detail/status refresh only.
- `Hard Fetch` tries a more aggressive wakeful status + location path.
- The backend now also sends an FCM wake message when a command is created.
- The phone still records one hourly report row for CSV export, even when the backend is offline.
- Queued hourly rows upload when the backend becomes reachable again.
- Default Android command polling is now 2 seconds, with less aggressive persistent lock behavior for wider device compatibility.

### If the backend is down

If the backend is stopped:

- the APK is still installed and keeps its local state
- enrollment is not lost
- if the device was deleted from the backend, the APK can auto re-enroll when it next reaches the server
- already-synced data remains in SQLite
- the dashboard will show stale data until fresh updates arrive again
- hourly report entries can queue on the phone and sync later when the backend returns

## Dashboard Status Meaning

The dashboard uses a simple rule:

- recent real backend contact from the phone: `live`
- no recent real backend contact: `attention`

Command clicks do not count as proof of connectivity by themselves.

## Dashboard Observability

Each device card now includes:

- heartbeat age
- location health based on recent location pings or location-disabled events
- command flow state based on recent command requests and completions
- background mode as `Battery unrestricted` or `Battery optimized`
- the active Android compatibility profile reported by the device
- reconnect count from recent action history

## CSV Export

Each device card can download an hourly CSV report.

The CSV may include:

- successful hourly location captures
- failure rows for missing location or permission conditions
- last known latitude and longitude when a fresh fix was unavailable
- the original capture time for that hour, not only the later sync time

## Important Limits

- A normal Android app cannot silently turn the phone master Location toggle back on after the user disables it.
- Some values such as carrier name, SSID, public IP, and ISP depend on Android version, OEM behavior, permissions, and network conditions.
- Even with heartbeat and FCM wake, some Android devices can still defer background work in deeper idle states.
- FCM wake improves command pickup, but it does not guarantee perfect autonomous heartbeat on every OEM build.
- If the phone has no communication path to the backend at all, it cannot send live updates until a path returns.
- Android still requires the foreground service notification icon while the background service is running; the app keeps that notification as quiet and static as possible.
- If the backend auth tokens do not match the APK build token and dashboard admin token, devices and dashboard actions will be rejected.

## Main Files

- backend entry point: `backend/server.py`
- backend config: `backend/config.py`
- backend auth: `backend/auth.py`
- backend database helpers: `backend/db.py`
- backend FCM helper: `backend/fcm.py`
- backend routes: `backend/routes.py`
- dashboard HTML: `backend/static/index.html`
- dashboard JS: `backend/static/app.js`
- dashboard CSS: `backend/static/styles.css`
- backend tests: `backend/tests/test_backend.py`
- Android main activity: `android-app/app/src/main/java/com/lazzy/losttracker/MainActivity.kt`
- Android tracker service: `android-app/app/src/main/java/com/lazzy/losttracker/TrackerService.kt`
- Android restart receiver: `android-app/app/src/main/java/com/lazzy/losttracker/ServiceRestartReceiver.kt`
- Android FCM service: `android-app/app/src/main/java/com/lazzy/losttracker/TrackerFirebaseMessagingService.kt`
- Android API client: `android-app/app/src/main/java/com/lazzy/losttracker/ApiClient.kt`
- Android prefs: `android-app/app/src/main/java/com/lazzy/losttracker/TrackerPrefs.kt`
- Android device status collector: `android-app/app/src/main/java/com/lazzy/losttracker/DeviceStatus.kt`
- Android compatibility profiles: `android-app/app/src/main/java/com/lazzy/losttracker/DeviceCompatibility.kt`
- Android hourly queue: `android-app/app/src/main/java/com/lazzy/losttracker/HourlyReportStore.kt`

## Notes For Future Maintainers

- `backend/data/` is intentionally git-ignored because it contains machine-local runtime state.
- `android-app/local.properties` is intentionally git-ignored because it is machine-specific.
- The GitHub repo contains code and docs, not the live SQLite database from this machine.
- The Cloudflare token is intentionally not stored in the repo; keep it in the environment or `~/.config/google-services/cloudflare.env`.
- The Firebase Admin service-account JSON is used locally by the backend and does not need to be committed into this repo.
- If you move the project folder again, update the absolute paths in the launchers and desktop file.
