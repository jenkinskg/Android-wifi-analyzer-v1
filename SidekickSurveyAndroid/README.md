# Sidekick Survey Android – JPG heatmaps (v0.9)

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

## v0.4 — ANR & selective editing improvements
- Separate clean install: Android application ID `com.jenkinskg.sidekicksurvey.v4`, so installing it will NOT delete v0.3 data. Old maps can be exported from the old app; they are not auto-imported.
- Heatmap interpolation runs on a dedicated background executor. Incoming survey points cancel obsolete heatmap requests; the latest heatmap swaps into view on the UI thread.
- JSON persistence and atomic disk writes run on a background executor, using a copy of survey and AP metadata. Atomic file writes prevent partially written files.
- Tap an AP pin: **Rename**, **Edit details**, **Move**, **Remove**. Removed AP identifiers remain hidden on future scans until restored using Selective Clear.
- Tap a numbered survey point: inspect time/reading count or remove that single point.
- Selective Clear supports remove last point, select point, select AP, clear points only, clear AP pins only, clear all measurements/pins, or restore hidden APs.
- Destructive actions are confirmed, and JPG floor plan is kept when clearing measurements.
- A short 250ms tap guard helps avoid duplicate measurements from accidental rapid taps.
- IMPORTANT: field test on your Android hardware remains necessary; passing CI compilation is not evidence of an instrument-quality survey or runtime validation. Android Wi-Fi scan throttling still applies.

## v0.5 — Per-point strongest BSSID, fresh scan capture
- Every tap on an empty map position requests a **new Android Wi-Fi scan** and records the survey point after a confirmed scan broadcast.
- If Android throttles or doesn't finish within 8.5 seconds, users must explicitly choose **Retry**, **Save cached**, or **Cancel**. Cached readings are marked, not treated as live measurements or used to place AP pins.
- Each numbered survey point shows the **strongest detected AP's SSID, full BSSID/MAC, and RSSI**, independent of the proximity threshold. The adjacent AP-location pin threshold remains adjustable.
- Tapping a numbered point shows its details and lets the user **choose any other BSSID observed at that point** rather than only the strongest.
- Point labels can be toggled on or off to reduce clutter and appear in exported JPGs when enabled.
- Survey CSV records the selected BSSID and fresh/cached status for each measurement.
- Android app ID v5 installs beside prior versions to preserve their data; surveys remain stored per app (not automatically transferred).
- Strongest RSSI is a **proxy** for nearby AP presence, not a measurement of physical proximity, especially where multiple AP radios, directional antennas, walls, or transmit power differences exist.

## v0.6 — connected AP roaming monitor (safe 500ms mode)
- Open **Roaming Monitor** on the home survey screen.
- Every **0.5 second**, read the **actually connected BSSID** and RSSI using Android `WifiManager.getConnectionInfo()`; log changes/roams on this session.
- Compare current connected BSSID with **strongest recently scanned AP on the SAME SSID**, and show strongest overall AP for context; flag potential roam candidates when at least **8 dBm** stronger. RSSI alone cannot determine best client association.
- Scan results update only when Android sends Wi-Fi scan results broadcasts. Optional manual/auto scan requests are spaced **40 seconds apart**; 500ms polling does NOT mean 500ms scanning.
- A scan older than 90 seconds is labeled stale; no current-roam recommendation from stale scan results.
- **Never** disconnect, force reconnect, toggle Wi-Fi, or claim to control system roaming; Android restricts those capabilities and forcing a handoff every 500ms is disruptive. Optional Wi-Fi Settings opens the standard system UI.
- Version v0.6 has its own package ID and does not overwrite or import the v0.5 private survey data. Field testing required.

## v0.7 — import Aruba AP inventory from TXT / CSV
- Open **Import / Match Aruba AP MAC + IP List** from the survey map.
- Supports ASCII/UTF-8 TXT or CSV with comma, semicolon, tab or whitespace-separated columns in flexible order, e.g. `AP-WEST,10.224.67.119,64:9e:f3:b3:7a:73`, `64:9e:f3:b3:7a:73 10.224.67.119 AP-WEST`, Cisco `649e.f3b3.7a73), and optional additional BSSID.
- Records AP **wired MAC, IP, name**. Import can merge to preserve existing BSSID mappings or replace the inventory.
- Exact wired-MAC/BSSID matching occurs automatically. Otherwise, tap an imported AP to **manually link a scanned or typed BSSID**. Never infer Aruba radio BSSID from wired MAC numeric offsets.
- Matched AP details show IP/name on annotated map pins, AP details, AP Inventory, and the 500ms Roaming Monitor.
- Import limit 2 MB, optimized inventory list via Android ListView; all data stays local.
- To produce an exact BSSID-to-AP-name/IP mapping from an Aruba controller, consider capturing `show ap bss-table` and converting it to a CSV of `name,IP,wiredMAC,BSSID`, or manually link scanned BSSIDs from this app. The imported file alone does not change the tablet roaming behavior.
- v0.7 has its own package ID and stores inventory and maps separately from v0.6, preserving earlier installs.

## v0.8 — Clear imported device/IP/MAC list
- **Clear ALL Imported APs (Device / IP / MAC)** on the Aruba AP Inventory screen. Confirms the exact number of imported APs and BSSID links before saving an empty `aruba_ap_inventory.json` file and refreshing the list. The control is disabled when the list is empty.
- Tap an imported AP, then **Remove this device from imported list** to selectively delete just that entry and its BSSID mappings. Asks for confirmation.
- No changes to the current survey JPG, survey points, AP map pins, manually verified map positions, or editable pin notes.
- Import TXT/CSV again to reload a list. Import **Replace** is also available.
- v0.8 has its own package ID and installs alongside v0.7. The earlier install's imported list is retained separately; file data is not migrated.
- Successful GitHub Action builds publish the v0.8 APK to `apks/Sidekick-Survey-Android-v0.8.apk`.

## v0.9 Stop & Go Wi-Fi Survey
- On the survey home screen use **Stop & Go: ON** (default), select **Dwell: 3/5/8/10 sec** (default 5) and tap a NEW position on the imported JPG floor plan.
- A blue countdown ring and live status show the dwell. Remain still while sampling the **actually connected tablet BSSID and RSSI every 500ms**. Uses WifiInfo, not repeated disconnect/reconnect.
- Attempts **one Android full Wi-Fi scan per stop**; when a fresh scan broadcast is received during the dwell, records the strongest SSID, BSSID, RSSI, frequency and channel of every visible AP. If scan is throttled, it stores the connected AP's **live sampled average/min/max RSSI only**, explicitly marked CONNECTED ONLY; does not mislabel an old cached scan as fresh.
- One completed stop creates **one** map point; AP proximity pins only update from fresh full scans. No points are created from auto-scan/connection polling alone.
- Tap survey point for average, minimum, maximum RSSI of the most frequently observed connected BSSID, polling count, frequency, link speed and connection BSSID changes during the stop. Survey CSV gains dwell columns and scan origin details.
- **Cancel Stop** discards unfinished measurement. Stop & Go OFF preserves previous quick-tap and fresh-scan/cached confirmation workflow.
- Stop & Go settings are persisted with survey. Heatmap stays background-generated and never recalculates per 500ms tick.
- **Android Wi-Fi scan throttling** means a 5-second wait does not guarantee a new scan or repeated scans of all nearby APs; connected signal readings are the high-frequency component.
- No captive roaming, no forced Wi-Fi disconnect and reconnect, and no proprietary Ekahau Sidekick measurements.
- Installed separately as `com.jenkinskg.sidekicksurvey.v9`. Previous app data remains in v0.8, not automatically migrated.
