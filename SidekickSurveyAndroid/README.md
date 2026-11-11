# Sidekick Survey Android – JPG heatmaps (v0.3)

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

## v0.3 Analyzer screen
From the JPG survey screen tap **Open Wi-Fi Analyzer**.
- **Access points**: sortable strongest-first list with detailed BSSID, SSID, channel,
  2.4/5/6-GHz band, RSSI, Wi-Fi generation, security capabilities and 20–320 MHz advertised width.
- **Channels**: per-band bar chart of APs seen on each *primary channel*.
  This is an AP-count proxy, NOT measured channel utilization, RF noise or spectrum analysis.
- **Signal history**: pick a particular AP; graph RSSI from confirmed fresh scans only.
- **Health checks**: weak-signal observations, potentially open/WEP APs, 2.4 GHz
  1/6/11 least-overlap estimate, 2.4 GHz wide-channel flags.
- **Auto scans**: optional scan attempts every 40 seconds while foregrounded. Android can throttle scans.
- **CSV scan export**: save the current scan's detailed AP data.
- **Connected device**: shows the phone's Wi-Fi SSID/IP/RSSI when Android permits.

The new Analyzer does not touch existing saved JPG floor plans/survey data. This is
still an Android-phone-radio analyzer, *not* direct Sidekick 2 RF access. The dedicated
spectrum analyzer, calibration, noise floor, packet capture, Wi-Fi 6 GHz channel coverage,
and interference readings require a compatible Sidekick SDK/interface and external hardware.

## Install v0.3 without removing v0.1
GitHub Actions debug builds use different signing certificates. v0.3 uses a distinct
Android application ID (`com.jenkinskg.sidekicksurvey.v2`) so it installs **beside**
v0.1 rather than failing as an update or deleting v0.1's local survey data.
Saved maps and survey points in v0.1 do not automatically move to v0.3. Export the
JPG from v0.1 and import it in v0.3 if needed. Keep v0.1 installed until migrated.

## v0.3: walking AP mapping
- Import a JPG floor plan, turn on **Auto Scan** (optional; 45-second attempts),
  and tap your position as you walk.
- Each tap stores a reading; visible APs at or above the adjustable RSSI
  threshold (-45 / -55 / -65 / -75 dBm) are automatically assigned an **estimated**
  location at your tap, with a persistent unique pin for each BSSID/MAC. Up to 4
  previously unseen AP pins are created per tap; all existing pins can be updated.
- If a stronger RSSI reading is later recorded, an *unfixed* marker moves
  toward the strongest recorded measurement location. This is **not**
  triangulation or a verified physical AP location.
- AP markers are orange for estimated, blue for manually moved,
  green for explicitly verified. Pins show AP name/SSID and BSSID.
- Touch a pin or open **AP Inventory** to edit AP name, building/room/closet,
  switch/port, notes, and the physically verified flag.
- **Move pin** in the editor allows tapping the correct floor-plan position.
  Manually moved positions are protected from automated repositioning.
- Save annotated JPG, original survey CSV, and new AP inventory CSV
  with editable fields, RSSI peak and map coordinates.
- Clear now explicitly warns that AP inventory and notes are also removed.
- v0.3 uses its own Android package ID and installs alongside v0.1/v0.2,
  preserving older local data in those apps (not auto-importing their surveys).
- Scan results can be cached or throttled on Android. Do not mistake the
  AP's position at the strongest recorded walking measurement for its true
  physical mounting location. Use visual confirmation to verify it.
