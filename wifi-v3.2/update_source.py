#!/usr/bin/env python3
"""Apply IP/network readout to the original Android WiFi Analyzer v3.1 source."""
import sys
from pathlib import Path

root = Path(sys.argv[1])
projects = list(root.rglob("settings.gradle"))
assert len(projects) == 1, f"Expected one Android project; found {projects}"
project = projects[0].parent
java = project / "app/src/main/java/com/keith/wifianalyzer/MainActivity.java"
manifest = project / "app/src/main/AndroidManifest.xml"
gradle = project / "app/build.gradle"
assert java.exists(), java
code = java.read_text()
assert "networkDetails" not in code, "Unexpected prepatched source"

def replace(old, new):
    global code
    if old not in code:
        raise RuntimeError(f"Anchor missing: {old[:100]!r}")
    code = code.replace(old, new, 1)

replace("import android.net.Uri;", """import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import java.net.Inet4Address;
import java.net.InetAddress;""")

replace("private TextView status, strongest, strongestMeta, rows, logStatus;",
        "private TextView status, strongest, strongestMeta, rows, logStatus, networkDetails;\n    private final Runnable networkRefresh = new Runnable() { @Override public void run() { updateNetworkDetails(); handler.postDelayed(this, 3000); } };")

replace("if(hasPermissions()) startScanning();\n    }",
        "if(hasPermissions()) startScanning();\n        handler.removeCallbacks(networkRefresh);\n        handler.post(networkRefresh);\n    }")

replace("@Override protected void onPause(){\n        super.onPause();\n        handler.removeCallbacks(scanLoop);",
        "@Override protected void onPause(){\n        super.onPause();\n        handler.removeCallbacks(scanLoop);\n        handler.removeCallbacks(networkRefresh);")

replace("@Override protected void onDestroy(){\n        super.onDestroy();\n        handler.removeCallbacks(scanLoop);",
        "@Override protected void onDestroy(){\n        super.onDestroy();\n        handler.removeCallbacks(scanLoop);\n        handler.removeCallbacks(networkRefresh);")

method = """
    private void updateNetworkDetails() {
        if (networkDetails == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) { networkDetails.setText("Connectivity service unavailable"); return; }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                LinkProperties props = cm.getLinkProperties(n);
                if (caps == null || props == null) continue;
                boolean wifiNetwork = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                boolean ethernetNetwork = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                if (!wifiNetwork && !ethernetNetwork) continue;
                count++;
                sb.append(wifiNetwork ? "Wi-Fi" : "Ethernet").append(" (")
                  .append(props.getInterfaceName()).append(")\\n");
                if (wifiNetwork && wifi != null) {
                    try {
                        WifiInfo info = wifi.getConnectionInfo();
                        if (info != null && info.getSSID() != null)
                            sb.append("SSID: ").append(info.getSSID()).append("\\n");
                    } catch (SecurityException ignored) {}
                }
                boolean foundIpv4 = false;
                for (LinkAddress address : props.getLinkAddresses()) {
                    if (!(address.getAddress() instanceof Inet4Address)) continue;
                    foundIpv4 = true;
                    sb.append("IP address: ").append(address.getAddress().getHostAddress()).append("\\n");
                    int bits = address.getPrefixLength();
                    int mask = bits == 0 ? 0 : (int)(0xffffffffL << (32 - bits));
                    sb.append("Subnet mask: ")
                      .append((mask >>> 24) & 255).append(".")
                      .append((mask >>> 16) & 255).append(".")
                      .append((mask >>> 8) & 255).append(".")
                      .append(mask & 255).append("\\n");
                }
                if (!foundIpv4) sb.append("IPv4: Not assigned\\n");
                for (RouteInfo route : props.getRoutes()) {
                    if (route.isDefaultRoute() && route.getGateway() instanceof Inet4Address) {
                        sb.append("Gateway: ").append(route.getGateway().getHostAddress()).append("\\n");
                        break;
                    }
                }
                sb.append("DNS: ");
                boolean first = true;
                for (InetAddress dns : props.getDnsServers()) {
                    if (!first) sb.append(", ");
                    sb.append(dns.getHostAddress());
                    first = false;
                }
                if (first) sb.append("Not supplied");
                sb.append("\\n\\n");
            }
            if (count == 0) sb.append("No active Wi-Fi or Ethernet network");
            networkDetails.setText(sb.toString().trim());
        } catch (Exception e) {
            networkDetails.setText("Network information unavailable: " + e.getMessage());
        }
    }

"""
replace("    private void buildUi(){", method + "    private void buildUi(){")

replace("root.addView(subtitle);",
        """root.addView(subtitle);
        root.addView(text("CURRENT NETWORK / IP ADDRESS", 16, Color.rgb(53,216,255), true));
        networkDetails = text("Reading network information...", 15, Color.WHITE, false);
        networkDetails.setPadding(dp(8), dp(8), dp(8), dp(12));
        root.addView(networkDetails);""")

code = code.replace("v3.1", "v3.2")
java.write_text(code)
xml = manifest.read_text()
assert 'android.permission.ACCESS_WIFI_STATE' in xml
xml = xml.replace('<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />',
                  '<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />\n    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />')
manifest.write_text(xml)
build = gradle.read_text()
assert "versionName '3.1'" in build
build = build.replace("versionName '3.1'", "versionName '3.2'")
build = build.replace("versionCode 4", "versionCode 5")
gradle.write_text(build)
print(f"Applied WiFi Analyzer v3.2 IP readout updates to {project}")
