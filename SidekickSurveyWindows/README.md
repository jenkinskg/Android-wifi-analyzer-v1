# Sidekick Survey Windows v1.0

Windows 10/11 desktop survey built with Python tkinter/Pillow and Microsoft's Native Wi-Fi API.

Features:
- Choose which Windows Wi-Fi adapter to scan, including compatible USB Wi-Fi adapters.
- Scan one selected card using WlanScan and WlanGetNetworkBssList (SSID, BSSID, RSSI, channel, frequency).
- Tap your JPG floor plan, wait 3, 5, 8, or 10 seconds, and record connected RSSI at 500ms intervals with one full AP scan attempt per spot.
- Import text or CSV AP inventory as device,ip,mac. In this file MAC means WIRELESS BSSID, matched exactly. Show name, IP, MAC on floor plan.
- Color-based signal heatmap, numbered survey points, AP location estimates, AP rename/remove, selective clearing.
- Export floor-plan JPG and all AP observations to CSV. Local auto-save to APPDATA/SidekickSurveyWindows.
- Do not disconnect or force the connection to a different AP. Windows roaming is managed by the operating system.

Direct Windows EXE download: https://github.com/jenkinskg/Android-wifi-analyzer-v1/releases/download/sidekick-windows-v1.0/Sidekick-Survey-Windows-v1.0.exe

Example list:
device,ip,mac
AP-101,10.224.67.119,64:9e:f3:b3:7a:73

Windows privacy/location settings may restrict WLAN BSSID or scanning. A successful WlanScan request does not guarantee fresh radio measurements. App reports if observed BSS timestamps changed, otherwise freshness is not verified. Compatible WLAN driver and WLAN AutoConfig are required.

Build locally:
1. Install Python 3.12 on Windows and run pip install -r requirements-build.txt.
2. Run python app.py for development.
3. Run python -m PyInstaller --clean --noconfirm --onefile --windowed --name Sidekick-Survey-Windows-v1.0 --collect-all PIL app.py.
The EXE is unsigned; Windows may show a SmartScreen warning. GitHub CI validates syntax and builds EXE; it cannot field-test WLAN hardware.
