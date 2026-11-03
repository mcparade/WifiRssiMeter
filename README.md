# WiFi RSSI Meter

An Android app (Kotlin + Jetpack Compose) that measures the RSSI of the Wi-Fi access point the
device is currently connected to.

## Features
- Live RSSI in dBm, quality %, rating (Excellent → Very weak) and signal bars
- Scrolling chart of the last 120 samples (-100 to -30 dBm)
- Session min / avg / max, sample count, and BSSID roam count
- AP details: SSID, BSSID, frequency, band, channel, link speed, Tx/Rx speed, Wi-Fi standard
- Sample interval 0.5 s / 1 s / 2 s / 5 s, pause/resume, reset
- Export the session log as CSV through the share sheet

## Build & run
1. Open this folder in Android Studio (Ladybug or newer). It downloads Gradle 8.11.1 and SDK 35
   on first sync.
2. Connect a phone (Android 8.0+, USB debugging on) and press **Run**.
3. Allow the location permission when asked.

To build from the command line, generate the wrapper once (`gradle wrapper`) and then run
`./gradlew assembleDebug`.

## Notes
- **Location permission and location services** are required by Android to reveal the SSID and
  BSSID. RSSI is measured even without them.
- Android refreshes the connected AP's RSSI on its own schedule (about every 3 s with the screen
  on), so sampling faster than that repeats values.
- Emulators report a fixed, simulated RSSI; use a real device.
