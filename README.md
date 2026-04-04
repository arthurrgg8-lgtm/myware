# Google Services

Android tracking client plus a small Python backend dashboard for local or tunneled device reporting.

## Project Layout

- `android-app/`: Android application package (`com.lazzy.losttracker`)
- `backend/`: Python HTTP server, SQLite database, and browser dashboard
- `launch-google-services.sh`: convenience launcher for the backend on port `8091`

## Current Behavior

### Android app

- App name: `Google Services`
- Auto-enrolls and auto-starts tracking after the required Android permissions exist
- Uses a public 16-digit device ID instead of exposing the internal database row ID
- Sends device details such as location, battery, network state, Wi-Fi SSID, IP, and ISP when available on the handset
- Queues hourly report entries locally when the server is unreachable and syncs them later
- Records hourly failures too, including cases such as location disabled, no fix, or missing permission

### Backend dashboard

- Serves a dependency-free web UI at `http://127.0.0.1:8091/`
- Shows enrolled devices, last known coordinates, status, battery, network, and recent events
- Supports device removal and per-device hourly CSV export
- Preserves synced history in SQLite under `backend/data/tracker.db`

## Run the Backend

From the project root:

```bash
bash /home/lazzy/Desktop/myware/launch-google-services.sh
```

Or manually:

```bash
cd /home/lazzy/Desktop/myware/backend
HOST=0.0.0.0 PORT=8091 python3 server.py
```

Dashboard URL:

`http://127.0.0.1:8091/`

## Build the APK

```bash
cd /home/lazzy/Desktop/myware/android-app
./gradlew assembleDebug
```

Build artifact:

`/home/lazzy/Desktop/myware/android-app/app/build/outputs/apk/debug/app-debug.apk`

Desktop copy used in this workspace:

`/home/lazzy/Desktop/Google Services-debug.apk`

## Install with adb

```bash
/home/lazzy/Android/Sdk/platform-tools/adb install -r '/home/lazzy/Desktop/Google Services-debug.apk'
```

Check connection:

```bash
/home/lazzy/Android/Sdk/platform-tools/adb devices -l
```

## Connectivity Model

### Live updates

For real-time updates, the phone must be able to reach the backend through one of these paths:

- same LAN as the server
- public tunnel / public backend URL
- USB with `adb reverse` when testing locally

### Offline behavior

If the server is down or unreachable:

- the APK is not reset or unenrolled
- queued hourly records stay on the phone
- the dashboard may show stale warnings until fresh updates arrive again
- once the backend is reachable, queued hourly reports sync automatically

## Hourly CSV Reports

Each device can export an hourly CSV report from the dashboard.

The report includes rows for:

- successful hourly location captures
- hourly failures such as `location disabled`, `no GPS/network fix`, or `permission missing`
- last known coordinates when a fresh fix was unavailable

## Important Limits

- A normal Android app cannot silently re-enable the system Location toggle after the user turns it off.
- Some fields such as carrier name, Wi-Fi SSID, public IP, or ISP depend on Android version, OEM restrictions, permissions, and current network conditions.
- If the phone has no route to the backend at all, it cannot send live updates until connectivity returns.

## Main Files

- Backend server: `/home/lazzy/Desktop/myware/backend/server.py`
- Dashboard HTML: `/home/lazzy/Desktop/myware/backend/static/index.html`
- Dashboard JS: `/home/lazzy/Desktop/myware/backend/static/app.js`
- Android main activity: `/home/lazzy/Desktop/myware/android-app/app/src/main/java/com/lazzy/losttracker/MainActivity.kt`
- Android tracker service: `/home/lazzy/Desktop/myware/android-app/app/src/main/java/com/lazzy/losttracker/TrackerService.kt`
- Android API client: `/home/lazzy/Desktop/myware/android-app/app/src/main/java/com/lazzy/losttracker/ApiClient.kt`
- Android hourly queue: `/home/lazzy/Desktop/myware/android-app/app/src/main/java/com/lazzy/losttracker/HourlyReportStore.kt`
