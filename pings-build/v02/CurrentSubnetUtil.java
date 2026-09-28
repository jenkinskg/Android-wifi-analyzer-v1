package com.keith.pings;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

final class CurrentSubnetUtil {
    private CurrentSubnetUtil() {}

    static String detect(Context context) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                // Prefer a real LAN transport and use Android's actual prefix length.
                Network[] networks = cm.getAllNetworks();
                if (networks != null) {
                    for (Network network : networks) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                        if (caps == null) continue;
                        boolean lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                        if (!lan) continue;
                        String subnet = fromLinkProperties(cm.getLinkProperties(network));
                        if (subnet != null) return subnet;
                    }
                }

                // Vendor/older Android fallback: try the active network.
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    String subnet = fromLinkProperties(cm.getLinkProperties(active));
                    if (subnet != null) return subnet;
                }
            }
        } catch (Exception ignored) {}

        // Last resort for devices that don't expose LinkProperties correctly.
        // We can still identify a private IPv4 address, but must assume /24.
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address
                            && !addr.isLoopbackAddress()
                            && addr.isSiteLocalAddress()) {
                        long ip = SubnetUtil.ipv4ToLong(addr.getHostAddress());
                        long network = ip & 0xffffff00L;
                        return SubnetUtil.longToIpv4(network) + "/24";
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String fromLinkProperties(LinkProperties props) {
        if (props == null) return null;
        for (LinkAddress link : props.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            if (!(address instanceof Inet4Address)
                    || address.isLoopbackAddress()
                    || !address.isSiteLocalAddress()) {
                continue;
            }
            int prefix = link.getPrefixLength();
            if (prefix < 0 || prefix > 32) continue;
            try {
                return SubnetUtil.normalize(address.getHostAddress() + "/" + prefix);
            } catch (Exception ignored) {}
        }
        return null;
    }
}
