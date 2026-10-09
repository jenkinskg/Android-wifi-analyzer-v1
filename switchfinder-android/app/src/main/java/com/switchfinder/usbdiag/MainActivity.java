package com.switchfinder.usbdiag;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;
import java.util.Map;

/** Non-root USB host/Android Ethernet enumeration; no CDP/LLDP capture. */
public class MainActivity extends Activity {
    private static final String USB_PERMISSION_ACTION = "com.switchfinder.usbdiag.USB_PERMISSION";
    private TextView output;
    private UsbManager usbManager;
    private String report = "";
    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!USB_PERMISSION_ACTION.equals(intent.getAction())) return;
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            Toast.makeText(MainActivity.this, granted ? "USB access allowed" : "USB access not granted", Toast.LENGTH_LONG).show();
            refresh();
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(USB_PERMISSION_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permissionReceiver, filter);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(18,20,18,12);
        TextView heading = new TextView(this);
        heading.setText("SWITCHFINDER — USB TEST");
        heading.setTextSize(21);
        main.addView(heading);
        TextView note = new TextView(this);
        note.setText("Non-root hardware diagnostic; not yet a switch-port finder");
        note.setPadding(0,0,0,14);
        main.addView(note);
        Button refresh = new Button(this);
        refresh.setText("SCAN USB + ETHERNET");
        refresh.setOnClickListener(v -> refresh());
        main.addView(refresh);
        Button permission = new Button(this);
        permission.setText("REQUEST USB ACCESS (OPTIONAL)");
        permission.setOnClickListener(v -> requestUsbAccess());
        main.addView(permission);
        Button copy = new Button(this);
        copy.setText("COPY REPORT");
        copy.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("SwitchFinder USB report", report));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        main.addView(copy);
        Button share = new Button(this);
        share.setText("SHARE REPORT");
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(i, "Share diagnostic results"));
        });
        main.addView(share);
        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setTextSize(14);
        output.setPadding(8,8,8,8);
        scroll.addView(output);
        main.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(main);
        refresh();
    }
    private void requestUsbAccess() {
        if (usbManager == null || usbManager.getDeviceList().isEmpty()) {
            Toast.makeText(this, "No USB devices exposed to this app", Toast.LENGTH_LONG).show();
            return;
        }
        UsbDevice d = usbManager.getDeviceList().values().iterator().next();
        Intent intent = new Intent(USB_PERMISSION_ACTION);
        intent.setPackage(getPackageName());
        PendingIntent pending = PendingIntent.getBroadcast(this,0,intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        try { usbManager.requestPermission(d,pending); }
        catch (Exception e) { Toast.makeText(this,"USB permission error: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
    }
    private static String hx(int n) { return String.format(Locale.US,"%04X",n); }
    private void refresh() {
        StringBuilder b = new StringBuilder();
        b.append("TABLET\nManufacturer: ").append(Build.MANUFACTURER)
         .append("\nModel: ").append(Build.MODEL)
         .append("\nAndroid: ").append(Build.VERSION.RELEASE)
         .append("\n\nUSB DEVICES VISIBLE TO APP\n");
        try {
            Map<String, UsbDevice> devices = usbManager.getDeviceList();
            b.append("Count: ").append(devices.size()).append("\n");
            for (UsbDevice d : devices.values()) {
                b.append("\nDevice: ").append(d.getDeviceName())
                 .append("\nVID: 0x").append(hx(d.getVendorId()))
                 .append(" PID: 0x").append(hx(d.getProductId()))
                 .append("\nClass/subclass: ").append(d.getDeviceClass()).append("/").append(d.getDeviceSubclass())
                 .append("\nUSB permission: ").append(usbManager.hasPermission(d))
                 .append("\nInterfaces: ").append(d.getInterfaceCount()).append("\n");
                for (int n=0;n<d.getInterfaceCount();n++) {
                    UsbInterface inf=d.getInterface(n);
                    b.append(" #").append(n).append(" class ").append(inf.getInterfaceClass())
                     .append(" subclass ").append(inf.getInterfaceSubclass())
                     .append(" endpoints ").append(inf.getEndpointCount()).append("\n");
                }
                try { b.append("Product: ").append(d.getProductName()).append("\n"); }
                catch (SecurityException ignored) { b.append("Product: restricted\n"); }
            }
        } catch (Exception e) { b.append("USB error: ").append(e).append("\n"); }

        b.append("\nANDROID NETWORK CONNECTIONS\n");
        try {
            ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network network: cm.getAllNetworks()) {
                NetworkCapabilities cap=cm.getNetworkCapabilities(network);
                LinkProperties lp=cm.getLinkProperties(network);
                if (cap==null) continue;
                b.append("\nNetwork: ").append(network.toString());
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) b.append(" [ETHERNET]");
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) b.append(" [WI-FI]");
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) b.append(" [CELLULAR]");
                b.append("\n");
                if (lp!=null) {
                    b.append("Interface: ").append(lp.getInterfaceName()).append("\n");
                    for (LinkAddress a:lp.getLinkAddresses()) b.append("Address: ").append(a).append("\n");
                    for (RouteInfo route:lp.getRoutes())
                        if(route.isDefaultRoute()) b.append("Default route: ").append(route).append("\n");
                    b.append("DNS: ").append(lp.getDnsServers()).append("\n");
                }
            }
        } catch (Exception e) { b.append("Network error: ").append(e).append("\n"); }
        b.append("\nUSB enumeration and DHCP do NOT prove raw CDP/LLDP access.\n");
        b.append("This app does NOT yet identify physical switch ports.\n");
        report=b.toString();
        output.setText(report);
    }
    @Override public void onDestroy(){
        try { unregisterReceiver(permissionReceiver); }catch(Exception ignored){}
        super.onDestroy();
    }
}
