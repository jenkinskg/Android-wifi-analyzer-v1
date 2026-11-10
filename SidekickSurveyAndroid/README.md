# Sidekick Survey Android – JPG heatmaps (v0.1)

A working **Android Wi-Fi survey prototype** that records the phone's Wi-Fi scan results at manually selected positions on an imported JPG/PNG floor plan. This is a separate project from the older ZIP-based Wi-Fi Analyzer in the repository.

## Features
- Import a JPG/JPEG/PNG building floor plan and tap positions to capture survey samples.
- Refresh Android Wi-Fi scan results; see SSID, BSSID, channel, and RSSI.
- Filter heatmap by strongest detected network, by SSID, or by a particular BSSID.
- Zoom and pan the floor plan; recorded survey points appear as numbered dots.
- Automatically saves the map and survey points locally and restores them when reopened.
- Export the annotated floor plan as **JPG** and all raw sample readings as **CSV**.
- Shows connected USB device identifiers for future Sidekick integration. Does **not** claim to read Sidekick 2 RF data.

## Build / APK
1. Open this folder in Android Studio (JDK 17).
2. Allow Gradle 8.9+ to sync; choose Build > Build APK(s).
3. Alternatively push a commit to the `sidekick-jpg-survey` branch and use the **Sidekick Android APK** GitHub Actions workflow. The workflow publishes a debug APK artifact on successful builds.

## Use
1. Install APK, grant **precise location** permission, and enable Location in Android Settings (Android Wi-Fi scanning requires both).
2. Import floor plan (JPG).
3. Tap **Scan Wi-Fi**, then tap your position on the map. Repeat around the site.
4. Change **Coverage target** to SSID or a specific BSSID.
5. Tap **Save JPG** for the map and **Save CSV** for detailed records.

## Measurement limitations / Sidekick 2
- The measurement source is **Android WifiManager**, **not** the Ekahau Sidekick 2. Sidekick USB identification alone is not data integration. The proprietary Sidekick data protocol or vendor SDK would be needed for genuine external-radio and spectrum measurements.
- Android throttles Wi-Fi scans (often 4 scans per 2 minutes while foregrounded), and may return cached readings. The status line indicates when a new scan is rejected/old results may be shown.
- Coverage is a **distance-weighted interpolation** between samples, not RF propagation prediction and not an Ekahau-calibrated survey.
- Wi-Fi capabilities, 6 GHz visibility, and exact scan channels depend on the Android device and permissions. No unsupported noise-floor, SNR, spectrum, or packet-capture claims.
- User-selected map points are manually placed; this app does not infer indoor positioning.
- Imported floor plans are downsampled to ~2400 px maximum side to limit memory use. Exported JPG contains the overlaid map at this resolution.
