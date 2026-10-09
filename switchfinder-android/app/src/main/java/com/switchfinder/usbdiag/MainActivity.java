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
    private volatile UsbCapture activeCapture;
    private Button captureButton;
    private Button stopCaptureButton;
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
        heading.setText("SWITCHFINDER V5 — RX RESTART");
        heading.setTextSize(21);
        main.addView(heading);
        TextView note = new TextView(this);
        note.setText("Cisco CDP / LLDP receive test. V5 temporarily enables USB Ethernet RX and multicast.");
        note.setPadding(0,0,0,14);
        main.addView(note);
        Button refresh = new Button(this);
        refresh.setText("SCAN USB + ETHERNET");
        refresh.setText("SCAN + TEST ASIX USB");
        refresh.setOnClickListener(v -> {
            refresh();
            new Thread(this::probeAxReadOnly).start();
        });
        main.addView(refresh);
        Button asixProbe = new Button(this);
        asixProbe.setText("TEST ASIX USB READ-ONLY");
        asixProbe.setOnClickListener(v -> new Thread(this::probeAxReadOnly).start());
        main.addView(asixProbe);

        captureButton = new Button(this);
        captureButton.setText("FIND MY SWITCH PORT — V5 TEST");
        captureButton.setOnClickListener(v -> showCaptureWarning());
        main.addView(captureButton);
        stopCaptureButton = new Button(this);
        stopCaptureButton.setText("STOP CAPTURE / RELEASE USB");
        stopCaptureButton.setEnabled(false);
        stopCaptureButton.setOnClickListener(v -> {
            UsbCapture c = activeCapture;
            if (c != null) c.stop();
        });
        main.addView(stopCaptureButton);

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

    /**
     * Read-only ASIX AX88179A vendor-control transfer probe.
     * NO interface claim, USB reset, RX filter change, or data capture.
     * These reads may fail while the Android kernel owns the Ethernet interface.
     */
    private void probeAxReadOnly() {
        StringBuilder b = new StringBuilder();
        b.append("\n===== AX88179A READ-ONLY PROBE =====\n");
        UsbDevice ethernet = null;
        try {
            for (UsbDevice d: usbManager.getDeviceList().values()) {
                if (d.getVendorId() == 0x0B95 && d.getProductId() == 0x1790) {
                    ethernet = d;
                    break;
                }
            }
            if (ethernet == null) {
                b.append("ASIX 0B95:1790 not found. Reconnect Baseus hub.\n");
            } else if (!usbManager.hasPermission(ethernet)) {
                b.append("USB permission not yet granted. Tap REQUEST USB ACCESS.\n");
            } else {
                android.hardware.usb.UsbDeviceConnection connection = usbManager.openDevice(ethernet);
                if (connection == null) {
                    b.append("openDevice FAILED; Android/kernel blocked device access.\n");
                } else {
                    try {
                        b.append("openDevice: SUCCESS\n");
                        b.append("No USB interface will be claimed or detached.\n");
                        readAxRegister(connection, b, "MAC address", 0x10, 6);
                        readAxRegister(connection, b, "RX control", 0x0B, 2);
                        readAxRegister(connection, b, "Media status", 0x22, 2);
                        readAxRegister(connection, b, "Bulk IN configuration", 0x2E, 5);
                    } finally {
                        connection.close();
                    }
                }
            }
        } catch (Exception e) {
            b.append("Probe error: ").append(e.getClass().getSimpleName()).append(": ")
             .append(e.getMessage()).append("\n");
        }
        b.append("NO USB interface claimed, NO switch credentials used.\n");
        b.append("Read success does NOT prove Ethernet packet capture is possible.\n");
        final String results = b.toString();
        runOnUiThread(() -> {
            report += results;
            output.setText(report);
        });
    }

    private static void readAxRegister(
            android.hardware.usb.UsbDeviceConnection connection,
            StringBuilder result,
            String label, int register, int length) {
        byte[] buf = new byte[length];
        // 0xC0: device-to-host, vendor-specific, device recipient.
        // ASIX command 0x01: read MAC register at wValue=register, wIndex=length.
        int n = connection.controlTransfer(0xC0, 0x01, register, length,
                                            buf, 0, length, 1000);
        result.append(label).append(": ");
        if (n < 0) {
            result.append("READ FAILED (").append(n).append(")\n");
        } else {
            result.append(n).append(" bytes ");
            for (int i=0;i<n;i++) result.append(String.format(Locale.US,"%02X ",buf[i]&255));
            result.append("\n");
        }
    }

    private void showCaptureWarning() {
        if (activeCapture != null) {
            Toast.makeText(this,"Capture already running",Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("USB Ethernet may disconnect")
            .setMessage("This 70-second experiment will claim the ASIX AX88179A USB interface and temporarily change its receive and multicast settings. "
              + "Android's Ethernet connection may stop working temporarily, and you may need "
              + "to unplug and reconnect the Baseus hub afterward. "
              + "It will only RECEIVE frames and will not change any switch settings. The app will attempt to restore the ASIX registers afterward. "
              + "Do not run this test over a network your work depends on.")
            .setNegativeButton("Cancel", (dlg,which) -> {})
            .setPositiveButton("START 70-SECOND TEST", (dlg,which) -> startCapture())
            .show();
    }

    private void startCapture() {
        if (activeCapture != null) return;
        UsbCapture c = new UsbCapture();
        activeCapture = c;
        captureButton.setEnabled(false);
        stopCaptureButton.setEnabled(true);
        report = "Preparing ASIX USB capture...";
        output.setText(report);
        new Thread(() -> c.capture(usbManager, (s,done) -> runOnUiThread(() -> {
            report = s;
            output.setText(report);
            if(done) {
                activeCapture = null;
                captureButton.setEnabled(true);
                stopCaptureButton.setEnabled(false);
            }
        })), "switchfinder-usb-capture").start();
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
        UsbCapture c = activeCapture;
        if (c != null) c.stop();
        try { unregisterReceiver(permissionReceiver); }catch(Exception ignored){}
        super.onDestroy();
    }
}
