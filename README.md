# Google Services

Google Services is a two-part project:

- an Android app that enrolls a device and reports status and location data
- a lightweight Python backend that stores device state and serves a browser dashboard

This repository is intended to be understandable and runnable by a new human or AI agent without prior chat context.

## Repository Layout

- `android-app/`: Android client source and Gradle build files
- `backend/`: Python HTTP server, dashboard assets, and SQLite runtime data
- `launch-google-services.sh`: convenience backend launcher on port `8091`
- `Google Services Server.desktop`: desktop launcher for the backend

## What The System Does

### Android client

The Android app:

- auto-enrolls after required Android permissions exist
- auto-starts tracking after setup
- stores a stable local device token
- exposes a public 16-digit device ID to the dashboard
- sends battery, network, Wi-Fi SSID, IP, ISP, and location data when the handset exposes those values
- records hourly report entries locally and syncs them later when the backend becomes reachable again
- records hourly failure states too, such as `location disabled`, `no GPS/network fix`, or `permission missing`

### Backend dashboard

The backend:

- stores device state in SQLite
- serves the dashboard at `http://127.0.0.1:8091/`
- shows compact per-device cards
- supports `Request location`
- supports removing a device from the backend
- supports per-device hourly CSV export

## Requirements

### Backend

- Python 3
- no external Python packages are required for the current server

### Android build

- JDK compatible with the Gradle project
- Android SDK
- `adb` if you want direct install or debugging on a connected phone

## Run The Backend

### Recommended

```bash
bash /home/lazzy/Desktop/myware/launch-google-services.sh
```

### Manual

```bash
cd /home/lazzy/Desktop/myware/backend
HOST=0.0.0.0 PORT=8091 python3 server.py
```

Dashboard URL:

`http://127.0.0.1:8091/`

## Build The APK

```bash
cd /home/lazzy/Desktop/myware/android-app
./gradlew assembleDebug
```

Build output:

`/home/lazzy/Desktop/myware/android-app/app/build/outputs/apk/debug/app-debug.apk`

Desktop convenience copy used in this workspace:

`/home/lazzy/Desktop/Google Services-debug.apk`

## Install The APK With adb

```bash
/home/lazzy/Android/Sdk/platform-tools/adb install -r '/home/lazzy/Desktop/Google Services-debug.apk'
```

Check the connected device list:

```bash
/home/lazzy/Android/Sdk/platform-tools/adb devices -l
```

## Connectivity Model

### Live updates

For live reporting, the phone must have a route to the backend through one of these:

- same LAN as the server
- a public tunnel or hosted backend URL
- USB with `adb reverse` during local testing

### If the backend is down

If the backend is stopped:

- the APK is still installed and keeps its local state
- enrollment is not lost
- already-synced data remains in SQLite
- the dashboard will show stale data until fresh updates arrive again
- hourly report entries can queue on the phone and sync later when the backend returns

## Dashboard Status Meaning

The dashboard marks a device as stale when fresh updates have not arrived recently.

Important distinction:

- `Location disabled on device`: the phone reported that system Location is off
- `Network is not connected on device`: the last reported network state was offline and updates are stale
- `No recent updates received from device`: Location may still be enabled, but the backend has not received fresh updates recently enough

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
- If the phone has no communication path to the backend at all, it cannot send live updates until a path returns.

## Main Files

- backend server: `backend/server.py`
- dashboard HTML: `backend/static/index.html`
- dashboard JS: `backend/static/app.js`
- dashboard CSS: `backend/static/styles.css`
- Android main activity: `android-app/app/src/main/java/com/lazzy/losttracker/MainActivity.kt`
- Android tracker service: `android-app/app/src/main/java/com/lazzy/losttracker/TrackerService.kt`
- Android API client: `android-app/app/src/main/java/com/lazzy/losttracker/ApiClient.kt`
- Android prefs: `android-app/app/src/main/java/com/lazzy/losttracker/TrackerPrefs.kt`
- Android device status collector: `android-app/app/src/main/java/com/lazzy/losttracker/DeviceStatus.kt`
- Android hourly queue: `android-app/app/src/main/java/com/lazzy/losttracker/HourlyReportStore.kt`

## Notes For Future Maintainers

- `backend/data/` is intentionally git-ignored because it contains machine-local runtime state.
- `android-app/local.properties` is intentionally git-ignored because it is machine-specific.
- The GitHub repo contains code and docs, not the live SQLite database from this machine.
- If you move the project folder again, update the absolute paths in `launch-google-services.sh` and `Google Services Server.desktop`.
