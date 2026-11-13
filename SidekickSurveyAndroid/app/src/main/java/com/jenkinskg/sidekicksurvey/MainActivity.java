package com.jenkinskg.sidekicksurvey;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AtomicFile;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final int PICK_MAP = 100;
    private static final int SAVE_JPG = 101;
    private static final int SAVE_CSV = 102;
    private static final int PERMISSION_WIFI = 103;
    private static final int SAVE_AP_CSV = 104;
    private static final String SOURCE = "Android phone Wi-Fi";

    private WifiManager wifi;
    private Bitmap floor;
    private final ArrayList<Reading> current = new ArrayList<>();
    private final ArrayList<Sample> samples = new ArrayList<>();
    private final ArrayList<String> targetIds = new ArrayList<>();
    private MapView map;
    private TextView status, usbStatus, resultsText;
    private Spinner selector;
    private String target = "BEST";
    private boolean registered;
    private long lastCompletedScan = 0L;
    private boolean pendingSurveyCapture=false;
    private float pendingSurveyX,pendingSurveyY;
    private boolean displayPointLabels=true;
    private ArrayList<InventoryStore.Entry> importedAps=new ArrayList<>();
    private final java.util.HashMap<String,InventoryStore.Entry> inventoryIndex=new java.util.HashMap<>();
    private void rebuildInventoryIndex(){
        inventoryIndex.clear();
        for(InventoryStore.Entry ap:importedAps){
            inventoryIndex.put(ap.mac,ap);
            for(String bssid:ap.bssids)inventoryIndex.put(bssid,ap);
        }
    }
    private int freshScanCounter=0;
    private Button pointLabelButton;
    private final Runnable scanCaptureTimeout=()->{
        if(pendingSurveyCapture)offerCachedCapture("No new scan results received. Android may be limiting scans.");
    };

    // A marker is an RSSI-peak survey estimate, not a triangulated AP position.
    private final LinkedHashMap<String,ApMarker> apMarkers = new LinkedHashMap<>();
    // Explicitly removed APs remain hidden until the user restores them.
    private final HashSet<String> hiddenApKeys = new HashSet<>();
    // Disk saving never blocks the main Android screen thread.
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger saveRevision = new AtomicInteger();

    private final android.os.Handler scanHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean autoScan = false;
    private int nearbyThreshold = -55;
    private String moveMarkerKey = null;
    private Button autoScanButton, sensitivityButton;
    private long lastTouchCaptured = 0L;
    // Stop & Go: keep radio connection intact, sample connected RSSI twice per second.
    private boolean stopGoEnabled = true;
    private int stopGoSeconds = 5;
    private boolean stopGoActive = false;
    private float stopGoX,stopGoY;
    private long stopGoStartElapsed;
    private boolean stopGoFreshScan = false;
    private long stopGoFreshAt = 0L;
    private String stopGoScanNote = "";
    private final ArrayList<Reading> stopGoFreshReadings = new ArrayList<>();
    private final LinkedHashMap<String,StopGoSignal> stopGoConnected = new LinkedHashMap<>();
    private int stopGoRoams = 0;
    private String stopGoLastBssid = "";
    private Button stopGoModeButton, stopGoDurationButton, stopGoCancelButton;
    private static final class StopGoSignal {
        String ssid,bssid;int mhz,linkMbps;
        int count=0,sum=0,min=0,max=0;
        StopGoSignal(String name,String mac,int frequency,int speed){
            ssid=name;bssid=mac;mhz=frequency;linkMbps=speed;
        }
        void add(int rssi,int frequency,int speed) {
            if(count==0){min=rssi;max=rssi;}
            min=Math.min(min,rssi);max=Math.max(max,rssi);
            sum+=rssi;count++;mhz=frequency;linkMbps=speed;
        }
        int avg(){return count==0?-127:Math.round((float)sum/count);}
    }
    private final Runnable stopGoTick = new Runnable() {
        @Override public void run() {
            if(!stopGoActive||isFinishing()||isDestroyed())return;
            sampleConnectedWifi();
            long duration=android.os.SystemClock.elapsedRealtime()-stopGoStartElapsed;
            long max=stopGoSeconds*1000L;
            int left=(int)Math.max(0,Math.ceil((max-duration)/1000.0));
            int polls=0;for(StopGoSignal item:stopGoConnected.values())polls+=item.count;
            String report=stopGoFreshScan?"fresh full scan captured":stopGoScanNote;
            status.setText("STOP & GO  •  Stay still "+left+"s  •  "+polls+" live RSSI samples"+
                "\n"+report+"  •  "+stopGoConnected.size()+" connected BSSID(s)");
            if(map!=null)map.invalidate();
            if(duration>=max)finishStopGo();
            else scanHandler.postDelayed(this,500L);
        }
    };

    private final Runnable scanLoop = new Runnable() {
        @Override public void run() {
            if (!autoScan || isFinishing()) return;
            if(stopGoActive){scanHandler.postDelayed(this,45000L);return;}
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                    && locationEnabled() && wifi!=null && wifi.isWifiEnabled()) scanWifi();
            scanHandler.postDelayed(this,45000L);
        }
    };

    static class Reading {
        String ssid, bssid;
        int dbm, mhz;
        Reading(String s, String b, int r, int f) { ssid=s; bssid=b; dbm=r; mhz=f; }
    }
    static class ApMarker {
        String bssid,ssid,apName="",room="",switchPort="",notes="";
        float x,y;
        int bestDbm=-127,frequency;
        boolean pinned=false,verified=false;
        ApMarker(Reading r,float px,float py) {
            bssid=r.bssid.toLowerCase(Locale.US);
            ssid=r.ssid; x=px; y=py; bestDbm=r.dbm; frequency=r.mhz;
        }
        String name() {
            if(apName!=null&&!apName.trim().isEmpty())return apName.trim();
            return ssid==null||ssid.isEmpty()?"Unknown AP":ssid;
        }
    }
    static class Sample {
        float x,y;
        long timestamp,scanAt;
        boolean fresh=false;
        String selectedBssid="";
        String mode="TAP", connectedBssid="", connectedSsid="";
        boolean connectedOnly=false;
        int dwellSeconds=0, connectedSamples=0, connectedAvg=-127;
        int connectedMin=-127, connectedMax=-127, connectedFreq=0;
        int connectedLinkMbps=-1, connectedRoams=0;
        ArrayList<Reading> readings = new ArrayList<>();
        Sample(float a,float b){ x=a;y=b;timestamp=System.currentTimeMillis(); }
        Reading strongest() {
            Reading top=null;
            for(Reading r:readings){
                if(r.bssid==null||r.bssid.isEmpty())continue;
                if(top==null||r.dbm>top.dbm)top=r;
            }
            return top;
        }
        Reading displayedAp(){
            if(selectedBssid!=null && !selectedBssid.isEmpty())
                for(Reading r:readings)if(selectedBssid.equalsIgnoreCase(r.bssid))return r;
            return strongest();
        }
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx,Intent intent) {
            boolean fresh=intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED,false);
            if(fresh){lastCompletedScan=System.currentTimeMillis();freshScanCounter++;}
            loadWifiResults(fresh?"FRESH scan #"+freshScanCounter:"CACHED / scan not updated");
            if(stopGoActive){
                if(fresh){
                    stopGoFreshScan=true;stopGoFreshAt=lastCompletedScan;
                    stopGoFreshReadings.clear();stopGoFreshReadings.addAll(current);
                    stopGoScanNote="Fresh Wi-Fi scan captured";
                }else stopGoScanNote="Android scan not updated; sampling connected signal";
                return;
            }
            if(pendingSurveyCapture&&fresh){
                scanHandler.removeCallbacks(scanCaptureTimeout);
                boolean had=pendingSurveyCapture;
                pendingSurveyCapture=false;
                if(had)commitCapture(pendingSurveyX,pendingSurveyY,true);
            } else if(pendingSurveyCapture&&!fresh) {
                scanHandler.removeCallbacks(scanCaptureTimeout);
                offerCachedCapture("Android scan did not update.");
            }
        }
    };

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(dp(9),dp(5),dp(9),dp(5));
        return t;
    }
    private int dp(int x) { return (int)(x*getResources().getDisplayMetrics().density + 0.5f); }
    private Button button(String label, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(12); b.setAllCaps(false);
        b.setOnClickListener(action);
        return b;
    }
    private void addButtons(LinearLayout parent, Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : buttons) row.addView(b, new LinearLayout.LayoutParams(0, dp(52),1));
        parent.addView(row);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        wifi = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        TextView title = text("Sidekick Survey  |  v0.9 Stop & Go",20);
        title.setTextColor(Color.rgb(16,60,100));
        root.addView(title);
        root.addView(button("Open Wi-Fi Analyzer  •  APs  |  Channels  |  Signal  |  Health",
            v->startActivity(new Intent(this,AnalyzerActivity.class))));
        root.addView(button("Roaming Monitor  •  Connected AP / strongest BSSID (0.5s)",
            v->startActivity(new Intent(this,RoamingActivity.class))));
        usbStatus = text("",12); root.addView(usbStatus);
        status = text("Tap your position: app requests fresh Wi-Fi scan, then labels the strongest AP at that point.",13);
        root.addView(status);
        addButtons(root,
            button("Import JPG", v-> pickMap()),
            button("Scan Wi-Fi", v-> scanWifi()),
            button("USB status", v-> updateUsb()));
        autoScanButton = button("Auto Scan: OFF",v->toggleAutoScan());
        sensitivityButton = button("Near: -55 dBm",v->chooseSensitivity());
        addButtons(root,autoScanButton,sensitivityButton,
            button("AP Inventory",v->showApInventory()));
        root.addView(button("Import / Match Aruba AP MAC + IP List",
            v->startActivity(new Intent(this,InventoryActivity.class))),new LinearLayout.LayoutParams(-1,dp(46)));
        pointLabelButton=button("Point MAC labels: ON",v->{
            displayPointLabels=!displayPointLabels;
            pointLabelButton.setText(displayPointLabels?"Point MAC labels: ON":"Point MAC labels: OFF");
            if(map!=null)map.invalidate();
        });
        root.addView(pointLabelButton,new LinearLayout.LayoutParams(-1,dp(40)));
        stopGoModeButton=button("Stop & Go: ON",v->{
            if(stopGoActive){Toast.makeText(this,"Finish or cancel the current stop first.",Toast.LENGTH_SHORT).show();return;}
            stopGoEnabled=!stopGoEnabled;
            stopGoModeButton.setText(stopGoEnabled?"Stop & Go: ON":"Stop & Go: OFF");
            status.setText(stopGoEnabled?"Tap a map position, stay still, then the app saves a point.":
                "Quick tap mode: save after a new Wi-Fi scan.");
            persist();
        });
        stopGoDurationButton=button("Dwell: 5 sec",v->chooseDwellDuration());
        stopGoCancelButton=button("Cancel Stop",v->cancelStopGo("Stop & Go canceled; point not saved."));
        addButtons(root,stopGoModeButton,stopGoDurationButton,stopGoCancelButton);
        stopGoCancelButton.setEnabled(false);
        LinearLayout selectionRow = new LinearLayout(this);
        selectionRow.setOrientation(LinearLayout.HORIZONTAL);
        selectionRow.addView(text("Coverage target:",13));
        selector = new Spinner(this);
        selectionRow.addView(selector,new LinearLayout.LayoutParams(0,dp(46),1));
        root.addView(selectionRow);
        map = new MapView(this);
        root.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        addButtons(root,
            button("Save JPG", v-> beginSave(SAVE_JPG, "image/jpeg", "survey-map.jpg")),
            button("Survey CSV", v-> beginSave(SAVE_CSV, "text/csv", "survey-data.csv")),
            button("AP CSV", v-> beginSave(SAVE_AP_CSV, "text/csv", "access-points.csv")),
            button("Selective Clear", v-> showSelectiveClear()));
        resultsText = text("No scan results yet.",12);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(resultsText);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(103)));
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < targetIds.size()) {
                    target=targetIds.get(pos);
                    if (map!=null) map.regenerate();
                }
            }
        });
        restore();
        updateSelectors();
        updateUsb();
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(scanReceiver,filter);
        registered=true;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
            loadWifiResults("Tap Scan Wi-Fi for fresh readings");
    }
    @Override public void onResume() {
        super.onResume();
        importedAps=InventoryStore.load(this);
        rebuildInventoryIndex();
        if(map!=null)map.invalidate();
        if(usbStatus!=null)updateUsb();
        scanHandler.removeCallbacks(scanLoop);
        if(autoScan)scanHandler.postDelayed(scanLoop,45000L);
    }
    @Override public void onPause() {
        scanHandler.removeCallbacks(scanLoop);
        scanHandler.removeCallbacks(scanCaptureTimeout);
        pendingSurveyCapture=false;
        cancelStopGo(null);
        super.onPause();
    }
    @Override public void onDestroy() {
        scanHandler.removeCallbacks(scanLoop);
        scanHandler.removeCallbacks(scanCaptureTimeout);
        cancelStopGo(null);
        if(map!=null)map.release();
        saveExecutor.shutdown();
        if (registered) unregisterReceiver(scanReceiver);
        super.onDestroy();
    }
    private void chooseDwellDuration(){
        if(stopGoActive){Toast.makeText(this,"Cancel the current stop to change dwell time.",Toast.LENGTH_SHORT).show();return;}
        final int[] values={3,5,8,10};
        final String[] labels={"3 seconds","5 seconds (recommended)","8 seconds","10 seconds"};
        int sel=1;for(int i=0;i<values.length;i++)if(values[i]==stopGoSeconds)sel=i;
        new AlertDialog.Builder(this).setTitle("Stop & Go dwell duration")
            .setSingleChoiceItems(labels,sel,(dialog,which)->{
                stopGoSeconds=values[which];
                stopGoDurationButton.setText("Dwell: "+stopGoSeconds+" sec");
                persist();dialog.dismiss();
            }).setNegativeButton("Cancel",null).show();
    }
    private void cancelStopGo(String message){
        scanHandler.removeCallbacks(stopGoTick);
        if(!stopGoActive)return;
        stopGoActive=false;
        stopGoConnected.clear();stopGoFreshReadings.clear();
        if(stopGoCancelButton!=null)stopGoCancelButton.setEnabled(false);
        if(map!=null)map.invalidate();
        if(message!=null&&status!=null)status.setText(message);
    }
    private void sampleConnectedWifi(){
        if(wifi==null ||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            WifiInfo connection=wifi.getConnectionInfo();
            if(connection==null)return;
            String bssid=InventoryStore.normalizeMac(connection.getBSSID());
            if(bssid.isEmpty())return;
            int dbm=connection.getRssi();
            if(dbm>0 || dbm<-110)return;
            String ssid=connection.getSSID();
            if(ssid==null)ssid="";
            if(ssid.length()>=2&&ssid.startsWith("\"")&&ssid.endsWith("\""))
                ssid=ssid.substring(1,ssid.length()-1);
            if(ssid.equalsIgnoreCase("<unknown ssid>"))ssid="";
            if(!stopGoLastBssid.isEmpty()&&!stopGoLastBssid.equals(bssid))stopGoRoams++;
            stopGoLastBssid=bssid;
            StopGoSignal signal=stopGoConnected.get(bssid);
            if(signal==null){
                signal=new StopGoSignal(ssid,bssid,connection.getFrequency(),connection.getLinkSpeed());
                stopGoConnected.put(bssid,signal);
            }
            signal.add(dbm,connection.getFrequency(),connection.getLinkSpeed());
        }catch(SecurityException ignored){}
    }
    private void beginStopGo(float x,float y) {
        if(stopGoActive)return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
            ||!locationEnabled()||wifi==null||!wifi.isWifiEnabled()){
            scanWifi();
            Toast.makeText(this,"Enable Wi-Fi and precise location, then tap again.",Toast.LENGTH_LONG).show();
            return;
        }
        stopGoActive=true;stopGoX=x;stopGoY=y;
        stopGoStartElapsed=android.os.SystemClock.elapsedRealtime();
        stopGoFreshScan=false;stopGoFreshAt=0L;
        stopGoFreshReadings.clear();stopGoConnected.clear();
        stopGoRoams=0;stopGoLastBssid="";
        if(stopGoCancelButton!=null)stopGoCancelButton.setEnabled(true);
        // Avoid clashing with the periodic scanner; a stop requests at most one new scan.
        scanHandler.removeCallbacks(scanLoop);
        stopGoScanNote="Requesting one Wi-Fi scan";
        try{
            if(!wifi.startScan())stopGoScanNote="Wi-Fi scan throttled; collecting live connected RSSI";
        }catch(SecurityException e){stopGoScanNote="Scan denied; collecting live connected RSSI";}
        stopGoTick.run();
    }
    private void finishStopGo(){
        if(!stopGoActive)return;
        scanHandler.removeCallbacks(stopGoTick);
        stopGoActive=false;
        if(stopGoCancelButton!=null)stopGoCancelButton.setEnabled(false);
        if(autoScan)scanHandler.postDelayed(scanLoop,45000L);
        // Choose the most frequently observed connected BSSID so averaged RSSI
        // belongs to one radio, even if the tablet roamed during the stop.
        StopGoSignal connected=null;
        for(StopGoSignal value:stopGoConnected.values())
            if(connected==null || value.count>connected.count)connected=value;
        final boolean fresh=stopGoFreshScan&&!stopGoFreshReadings.isEmpty();
        if(!fresh && connected==null){
            stopGoFreshReadings.clear();stopGoConnected.clear();
            new AlertDialog.Builder(this).setTitle("No Wi-Fi readings collected")
                .setMessage("Android returned no fresh Wi-Fi scan and the tablet reported no connected AP. Nothing was saved.")
                .setPositiveButton("Retry",(d,w)->beginStopGo(stopGoX,stopGoY))
                .setNegativeButton("Close",null).show();
            if(map!=null)map.invalidate();
            return;
        }
        Sample point=new Sample(stopGoX,stopGoY);
        point.mode="STOP_GO";point.dwellSeconds=stopGoSeconds;
        point.fresh=fresh;point.scanAt=fresh?stopGoFreshAt:0L;
        point.connectedRoams=stopGoRoams;
        if(fresh)point.readings.addAll(stopGoFreshReadings);
        if(connected!=null){
            point.connectedBssid=connected.bssid;point.connectedSsid=connected.ssid;
            point.connectedSamples=connected.count;point.connectedAvg=connected.avg();
            point.connectedMin=connected.min;point.connectedMax=connected.max;
            point.connectedFreq=connected.mhz;point.connectedLinkMbps=connected.linkMbps;
        }
        if(!fresh&&connected!=null){
            // Live client RSSI is not a full AP scan. Record only the connected radio.
            point.connectedOnly=true;
            point.readings.add(new Reading(connected.ssid,connected.bssid,connected.avg(),connected.mhz));
        }
        Reading strongest=point.strongest();
        point.selectedBssid=strongest==null?"":strongest.bssid;
        samples.add(point);
        int pins=fresh?placeNearbyAps(point):0;
        stopGoFreshReadings.clear();stopGoConnected.clear();
        persist();updateSelectors();
        String origin=fresh?"FRESH Wi-Fi scan + live connected RSSI":"CONNECTED-ONLY (full scan throttled/unavailable)";
        status.setText("Stop & Go point "+samples.size()+" saved • "+stopGoSeconds+" seconds"+
            "\n"+origin+" • "+(strongest==null?"No AP":strongest.bssid+" "+strongest.dbm+" dBm")+
            "\n"+point.connectedSamples+" live RSSI polls • "+pins+" new estimated AP pins.");
        if(map!=null)map.invalidate();
    }
    private void toggleAutoScan() {
        autoScan=!autoScan;
        autoScanButton.setText(autoScan?"Auto Scan: ON":"Auto Scan: OFF");
        scanHandler.removeCallbacks(scanLoop);
        if(autoScan) {
            if(!stopGoActive)scanWifi();
            scanHandler.postDelayed(scanLoop,45000L);
        } else status.setText("Automatic Wi-Fi scan attempts stopped.");
    }
    private void chooseSensitivity() {
        final String[] names={"Very close (-45 dBm)","Close (-55 dBm)","Nearby (-65 dBm)","Broad (-75 dBm)"};
        final int[] thresholds={-45,-55,-65,-75};
        int selected=1;
        for(int i=0;i<thresholds.length;i++)if(thresholds[i]==nearbyThreshold)selected=i;
        new AlertDialog.Builder(this)
            .setTitle("Automatic AP pin sensitivity")
            .setSingleChoiceItems(names,selected,(dialog,which)->{
                nearbyThreshold=thresholds[which];
                sensitivityButton.setText("Near: "+nearbyThreshold+" dBm");
                persist();
                dialog.dismiss();
            })
            .setNegativeButton("Cancel",null).show();
    }
    private void updateUsb() {
        UsbManager m=(UsbManager)getSystemService(Context.USB_SERVICE);
        if (m==null || m.getDeviceList().isEmpty()) {
            usbStatus.setText("USB: none detected  |  Source: Android Wi-Fi (not Sidekick)");
            return;
        }
        StringBuilder b=new StringBuilder("USB devices: ");
        for (UsbDevice d:m.getDeviceList().values()) {
            b.append(String.format(Locale.US, "[VID %04X PID %04X] ",d.getVendorId(),d.getProductId()));
        }
        usbStatus.setText(b.toString()+"\nSidekick RF data requires vendor interface; using phone Wi-Fi.");
    }
    private boolean locationEnabled() {
        LocationManager lm=(LocationManager)getSystemService(Context.LOCATION_SERVICE);
        return lm!=null && (Build.VERSION.SDK_INT<28 || lm.isLocationEnabled());
    }
    private void scanWifi() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},PERMISSION_WIFI); return;
        }
        if (!locationEnabled()) {
            new AlertDialog.Builder(this).setMessage("Android requires Location to be enabled for Wi-Fi scan results.")
                .setPositiveButton("Open Settings",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton("Cancel",null).show(); return;
        }
        if (wifi==null || !wifi.isWifiEnabled()) {
            status.setText("Enable Wi-Fi on the phone, then scan again."); return;
        }
        try {
            boolean requested=wifi.startScan();
            if (requested) status.setText("Wi-Fi scan requested. Tap a map position to measure with a fresh scan.");
            else loadWifiResults("CACHED: scan throttled/declined; existing results may be old");
        } catch (SecurityException e) { status.setText("Location permission is required for scanning."); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] p, int[] results) {
        super.onRequestPermissionsResult(code,p,results);
        if (code==PERMISSION_WIFI && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) scanWifi();
        else if (code==PERMISSION_WIFI) status.setText("Precise location permission is needed for Wi-Fi scanning.");
    }
    private void loadWifiResults(String note) {
        current.clear();
        try {
            if (wifi!=null) {
                List<ScanResult> r=wifi.getScanResults();
                for (ScanResult a:r) current.add(new Reading(a.SSID==null?"":a.SSID,
                    a.BSSID==null?"":a.BSSID, a.level,a.frequency));
            }
        } catch (Exception e) { status.setText("Wi-Fi scan inaccessible: check Location permission and settings."); }
        Collections.sort(current,(a,b)->Integer.compare(b.dbm,a.dbm));
        StringBuilder top = new StringBuilder();
        for (int i=0;i<Math.min(current.size(),8);i++) {
            Reading r=current.get(i);
            top.append(r.ssid.isEmpty()?"<hidden>":r.ssid)
               .append("  ").append(r.dbm).append(" dBm  ch ").append(channel(r.mhz))
               .append("  ").append(r.bssid).append("\n");
        }
        resultsText.setText(top.length()==0?"No APs visible.":top.toString());
        String date=lastCompletedScan==0?"No confirmed fresh scan":
            "last fresh "+new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date(lastCompletedScan));
        Reading strongest=current.isEmpty()?null:current.get(0);
        String best=strongest==null?"none":strongest.bssid+" "+strongest.dbm+" dBm";
        status.setText(note+"  |  "+current.size()+" APs  |  "+date+
            "\nStrongest NOW: "+best+"  |  "+samples.size()+" survey points");
        updateSelectors();
    }
    private int channel(int mhz) {
        if (mhz==2484) return 14;
        if (mhz>=2412 && mhz<=2472) return (mhz-2407)/5;
        if (mhz==5935) return 2;
        if (mhz>=5955 && mhz<=7115) return (mhz-5950)/5;
        if (mhz>=5000 && mhz<5925) return (mhz-5000)/5;
        return 0;
    }
    private void updateSelectors() {
        String previous=target;
        ArrayList<String> labels=new ArrayList<>();
        targetIds.clear();
        labels.add("Strongest visible AP"); targetIds.add("BEST");
        Set<String> ssids=new LinkedHashSet<>(), bssids=new LinkedHashSet<>();
        for (Reading r:current) { if (!r.ssid.isEmpty()) ssids.add(r.ssid); bssids.add(r.bssid); }
        for (Sample s:samples) for (Reading r:s.readings) {
            if (!r.ssid.isEmpty()) ssids.add(r.ssid);
            bssids.add(r.bssid);
        }
        ArrayList<String> ssidList=new ArrayList<>(ssids), bssidList=new ArrayList<>(bssids);
        ssidList.sort(String.CASE_INSENSITIVE_ORDER);
        bssidList.sort(String.CASE_INSENSITIVE_ORDER);
        for (String s:ssidList) { labels.add("SSID: "+s); targetIds.add("S:"+s); }
        for (String s:bssidList) if (!s.isEmpty()) {
            labels.add("BSSID: "+s); targetIds.add("B:"+s);
        }
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);
        selector.setAdapter(adapter);
        int idx=targetIds.indexOf(previous);
        if (idx<0) idx=0;
        selector.setSelection(idx);
        target=targetIds.get(idx);
        if (map!=null) map.regenerate();
    }
    private void pickMap() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,PICK_MAP);
    }
    private void beginSave(int kind, String mime, String name) {
        if (kind==SAVE_AP_CSV ? apMarkers.isEmpty() : (floor==null || samples.isEmpty())) {
            Toast.makeText(this,kind==SAVE_AP_CSV?"No AP pins to export.":"Import a map and record survey positions first.",Toast.LENGTH_LONG).show();
            return;
        }
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE,name);
        startActivityForResult(i,kind);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (result!=RESULT_OK || data==null || data.getData()==null) return;
        try {
            if (request==PICK_MAP) {
                Bitmap loaded=readScaledMap(data.getData());
                if (loaded==null) throw new Exception("Invalid image");
                floor=loaded;
                samples.clear(); apMarkers.clear(); hiddenApKeys.clear();
                moveMarkerKey=null; target="BEST";
                try (OutputStream out=new FileOutputStream(new File(getFilesDir(),"floorplan.jpg"))) {
                    floor.compress(Bitmap.CompressFormat.JPEG,94,out);
                }
                persist(); map.resetView(); updateSelectors();
                status.setText("Map imported. Scan Wi-Fi, then tap your position to save a measurement.");
            } else if (request==SAVE_JPG) {
                Bitmap picture=map.exportImage();
                if (picture==null) throw new Exception("No map loaded");
                try (OutputStream out=getContentResolver().openOutputStream(data.getData())) {
                    if (out==null || !picture.compress(Bitmap.CompressFormat.JPEG,95,out))
                        throw new Exception("Could not write JPG");
                }
                picture.recycle();
                status.setText("Annotated floor plan JPG saved.");
            } else if (request==SAVE_CSV || request==SAVE_AP_CSV) {
                try (OutputStream out=getContentResolver().openOutputStream(data.getData())) {
                    if (out==null) throw new Exception("Cannot open destination");
                    String contents=request==SAVE_CSV ? makeCsv():makeApCsv();
                    out.write(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                status.setText(request==SAVE_CSV?"Survey CSV saved.":"AP inventory CSV saved with "+apMarkers.size()+" pins.");
            }
        } catch (Exception e) {
            status.setText("File operation failed: "+e.getMessage());
        }
    }
    private Bitmap readScaledMap(android.net.Uri uri) throws Exception {
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        try (InputStream in=getContentResolver().openInputStream(uri)) {
            if (in==null) throw new Exception("Could not open image");
            BitmapFactory.decodeStream(in,null,bounds);
        }
        if (bounds.outWidth<1 || bounds.outHeight<1) throw new Exception("Invalid map");
        int sample=1;
        while (Math.max(bounds.outWidth,bounds.outHeight)/sample>2400) sample*=2;
        BitmapFactory.Options o=new BitmapFactory.Options(); o.inSampleSize=sample;
        try (InputStream in=getContentResolver().openInputStream(uri)) {
            if (in==null) throw new Exception("Could not open image");
            return BitmapFactory.decodeStream(in,null,o);
        }
    }
    private void capture(float x,float y) {
        if(floor==null) {Toast.makeText(this,"Import JPG floor plan first.",Toast.LENGTH_SHORT).show();return;}
        if(moveMarkerKey!=null){
            ApMarker selected=apMarkers.get(moveMarkerKey);
            moveMarkerKey=null;
            if(selected!=null){
                selected.x=x;selected.y=y;selected.pinned=true;
                persist();map.invalidate();
                status.setText("Moved "+selected.name()+" to its manually selected location.");
            }
            return;
        }
        if(stopGoActive){
            Toast.makeText(this,"Stay still: the current Stop & Go is collecting readings.",Toast.LENGTH_SHORT).show();
            return;
        }
        if(pendingSurveyCapture){
            Toast.makeText(this,"Scan in progress. One survey point at a time.",Toast.LENGTH_SHORT).show();
            return;
        }
        if(stopGoEnabled){
            beginStopGo(x,y);
            return;
        }
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
            || !locationEnabled() || wifi==null || !wifi.isWifiEnabled()){
            scanWifi();
            Toast.makeText(this,"Enable Wi-Fi and precise location, then tap this position again.",Toast.LENGTH_LONG).show();
            return;
        }
        pendingSurveyCapture=true;
        pendingSurveyX=x;pendingSurveyY=y;
        status.setText("Scanning Wi-Fi at your marked position. Waiting for FRESH readings...");
        try {
            boolean accepted=wifi.startScan();
            if(accepted)scanHandler.postDelayed(scanCaptureTimeout,8500L);
            else offerCachedCapture("Fresh scan rejected/throttled by Android.");
        }catch(SecurityException exception){
            pendingSurveyCapture=false;
            status.setText("Android denied Wi-Fi scan. Check precise location permission.");
        }
    }
    private void offerCachedCapture(String reason) {
        if(!pendingSurveyCapture)return;
        scanHandler.removeCallbacks(scanCaptureTimeout);
        final float x=pendingSurveyX,y=pendingSurveyY;
        pendingSurveyCapture=false;
        if(isFinishing()||isDestroyed())return;
        new AlertDialog.Builder(this).setTitle("No fresh Wi-Fi scan")
            .setMessage(reason+"\n\nSaving cached measurements may repeatedly show the SAME AP/MAC even as you walk. Retry when Wi-Fi scan limits allow, or explicitly save the cached snapshot.")
            .setPositiveButton("Retry scan",(dialog,which)->capture(x,y))
            .setNeutralButton("Save cached",(dialog,which)->commitCapture(x,y,false))
            .setNegativeButton("Cancel",null)
            .show();
    }
    private void commitCapture(float x,float y,boolean fresh) {
        if(current.isEmpty()){
            Toast.makeText(this,"No Wi-Fi readings to record; scan Wi-Fi again.",Toast.LENGTH_LONG).show();
            return;
        }
        Sample point=new Sample(x,y);
        point.readings.addAll(current);
        point.fresh=fresh;
        point.scanAt=fresh?lastCompletedScan:0L;
        Reading strongest=point.strongest();
        point.selectedBssid=strongest==null?"":strongest.bssid;
        samples.add(point);
        // Cached snapshots are displayed distinctly but never used to locate AP pins.
        int added=fresh?placeNearbyAps(point):0;
        persist();updateSelectors();
        status.setText("Point "+samples.size()+"  •  "+(fresh?"FRESH":"CACHED")+"  •  strongest: "+
             (strongest==null?"none":strongest.bssid+" "+strongest.dbm+" dBm")+
             "\n"+added+" new estimated AP location pins. Touch a point to choose another visible AP.");
    }
    private int placeNearbyAps(Sample s) {
        // Never infer a new AP pin from a stale or unconfirmed Android scan.
        if(!s.fresh || lastCompletedScan==0 || System.currentTimeMillis()-lastCompletedScan>120000L)return 0;
        int added=0;
        // Samples are already sorted strongest-first. Never create an unlimited pile
        // of AP pins at a single point. Existing estimates can improve at any point.
        for(Reading r:s.readings) {
            if(r.bssid==null || r.bssid.trim().isEmpty() || r.dbm<nearbyThreshold)continue;
            if(hiddenApKeys.contains(r.bssid.toLowerCase(Locale.US)))continue;
            String key=r.bssid.toLowerCase(Locale.US);
            ApMarker m=apMarkers.get(key);
            if(m==null) {
                if(added>=4)continue;
                m=new ApMarker(r,s.x,s.y);
                apMarkers.put(key,m);
                added++;
            } else {
                if(r.dbm>m.bestDbm+2 && !m.pinned && !m.verified) {
                    m.x=s.x;m.y=s.y;
                }
                if(r.dbm>m.bestDbm){m.bestDbm=r.dbm;m.frequency=r.mhz;}
                if((m.ssid==null||m.ssid.isEmpty()) && r.ssid!=null)m.ssid=r.ssid;
            }
        }
        return added;
    }
    private void showApInventory() {
        if(apMarkers.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("AP Inventory")
                .setMessage("No AP pins yet. Tap Scan Wi-Fi, then tap your position on the map whenever you're near an AP. Use Near sensitivity to adjust the signal threshold.")
                .setPositiveButton("OK",null).show();
            return;
        }
        ArrayList<ApMarker> list=new ArrayList<>(apMarkers.values());
        String[] names=new String[list.size()];
        for(int i=0;i<list.size();i++){
            ApMarker m=list.get(i);
            InventoryStore.Entry entry=linkedAp(m.bssid);
            names[i]=m.name()+" | "+m.bssid+
                (entry==null?" | Unmapped":" | "+entry.displayName()+" "+entry.ip)+
                " | "+m.bestDbm+" dBm"+(m.verified?" ✓ verified":"");
        }
        new AlertDialog.Builder(this).setTitle("AP Inventory ("+list.size()+")")
            .setItems(names,(dialog,which)->showApActions(list.get(which)))
            .setPositiveButton("Close",null).show();
    }
    private void showApActions(ApMarker m) {
        if(m==null)return;
        final String[] actions={"Rename AP","Edit AP details","Move AP pin","Remove AP pin"};
        new AlertDialog.Builder(this).setTitle(m.name()+"\n"+m.bssid)
            .setItems(actions,(dialog,index)->{
                if(index==0)renameAp(m);
                else if(index==1)editApMarker(m);
                else if(index==2){
                    moveMarkerKey=m.bssid;
                    status.setText("Tap verified AP position on the map for "+m.name()+".");
                    Toast.makeText(this,"Tap the new pin position on the floor plan",Toast.LENGTH_LONG).show();
                }
                else if(index==3)confirmAction("Remove AP pin",
                    "Remove "+m.name()+" ("+m.bssid+")? It will stay hidden on future scans until you restore hidden APs.",
                    ()->{apMarkers.remove(m.bssid);hiddenApKeys.add(m.bssid);
                         if(m.bssid.equals(moveMarkerKey))moveMarkerKey=null;
                         onSurveyChanged("Removed AP pin "+m.name()+".");});
            })
            .setNegativeButton("Cancel",null).show();
    }
    private void renameAp(ApMarker m){
        final EditText edit=new EditText(this);
        edit.setSingleLine(true);
        edit.setText(m.apName==null?"":m.apName);
        edit.setSelectAllOnFocus(true);
        int p=dp(16);edit.setPadding(p,p,p,p);
        new AlertDialog.Builder(this).setTitle("Rename "+m.bssid)
            .setView(edit)
            .setPositiveButton("Save",(dialog,which)->{
                m.apName=edit.getText().toString().trim();
                onSurveyChanged("AP renamed: "+m.name());
            })
            .setNegativeButton("Cancel",null).show();
    }
    private void confirmAction(String title,String message,Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Remove",(d,w)->action.run())
            .setNegativeButton("Cancel",null).show();
    }
    private void onSurveyChanged(String message) {
        persist();
        updateSelectors();
        map.regenerate();
        status.setText(message+"  •  "+samples.size()+" points, "+apMarkers.size()+" AP pins");
    }
    private void removeSample(Sample s) {
        if(samples.remove(s))onSurveyChanged("Survey point removed");
    }
    // Point inspection always uses the stored snapshot, never the phone's current scan.
    private void showSampleActions(Sample s) {
        if(s==null)return;
        int index=samples.indexOf(s);
        if(index<0)return;
        ArrayList<Reading> recorded=new ArrayList<>(s.readings);
        recorded.sort((a,b)->Integer.compare(b.dbm,a.dbm));
        Reading strongest=s.strongest();
        SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);
        StringBuilder details=new StringBuilder();
        details.append("Recorded: ").append(fmt.format(new Date(s.timestamp)))
            .append("\\nScan: ").append(s.fresh?"FRESH":"CACHED / CONNECTED ONLY")
            .append("\\nMeasurements: ").append(recorded.size()).append(" BSSIDs");
        if(strongest!=null){
            details.append("\\n\\nSTRONGEST ACCESS POINT")
                .append("\\n").append(strongest.ssid.isEmpty()?"<hidden SSID>":strongest.ssid)
                .append("  •  ").append(strongest.dbm).append(" dBm")
                .append("\\nBSSID: ").append(strongest.bssid)
                .append("\\nChannel: ").append(channel(strongest.mhz))
                .append("  •  ").append(strongest.mhz).append(" MHz");
            InventoryStore.Entry entry=inventoryIndex.get(InventoryStore.normalizeMac(strongest.bssid));
            if(entry!=null)details.append("\\nInventory: ").append(entry.displayName())
                .append("  •  IP ").append(entry.ip.isEmpty()?"unknown":entry.ip);
        }else details.append("\\n\\nNo AP readings stored.");
        if("STOP_GO".equals(s.mode)){
            details.append("\\n\\nSTOP & GO: ").append(s.dwellSeconds).append(" seconds")
                .append("  •  ").append(s.connectedSamples).append(" live samples")
                .append("\\nConnected RSSI avg/min/max: ").append(s.connectedAvg).append("/")
                .append(s.connectedMin).append("/").append(s.connectedMax).append(" dBm")
                .append("\\nRoams: ").append(s.connectedRoams);
            if(s.connectedOnly)details.append("\\nOnly connected AP was measured; no fresh full scan.");
        }
        details.append("\\n\\nALL RECORDED ACCESS POINTS (strongest first)");
        for(Reading reading:recorded){
            details.append("\\n\\n").append(reading.ssid.isEmpty()?"<hidden SSID>":reading.ssid)
                .append("  •  ").append(reading.dbm).append(" dBm")
                .append("\\n").append(reading.bssid)
                .append("  •  Ch ").append(channel(reading.mhz))
                .append("  •  ").append(reading.mhz).append(" MHz");
            InventoryStore.Entry entry=inventoryIndex.get(InventoryStore.normalizeMac(reading.bssid));
            if(entry!=null)details.append("\\n").append(entry.displayName())
                .append("  •  ").append(entry.ip.isEmpty()?"IP unknown":entry.ip);
        }
        ScrollView scroll=new ScrollView(this);
        TextView content=text(details.toString(),14);
        content.setTextIsSelectable(true);
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("Survey point "+(index+1)+" • Saved RSSI")
            .setView(scroll)
            .setPositiveButton("Choose displayed AP",(d,w)->choosePointAp(s))
            .setNeutralButton("Remove point",(d,w)->confirmAction("Remove point","Remove this survey point?",()->removeSample(s)))
            .setNegativeButton("Close",null).show();
    }
    private void choosePointAp(Sample sample) {
        ArrayList<Reading> options=new ArrayList<>();
        for(Reading r:sample.readings)if(r.bssid!=null&&!r.bssid.isEmpty())options.add(r);
        options.sort((a,b)->Integer.compare(b.dbm,a.dbm));
        if(options.isEmpty()){Toast.makeText(this,"No APs at this point",Toast.LENGTH_SHORT).show();return;}
        String[] labels=new String[options.size()];
        for(int i=0;i<options.size();i++){
            Reading r=options.get(i);
            labels[i]=(r.ssid.isEmpty()?"<hidden>":r.ssid)+"  "+r.dbm+" dBm  ch "+channel(r.mhz)+"\n"+r.bssid;
        }
        new AlertDialog.Builder(this).setTitle("Show AP at this survey point")
            .setSingleChoiceItems(labels,findSelectedIndex(options,sample.selectedBssid),(dialog,which)->{
                sample.selectedBssid=options.get(which).bssid;
                onSurveyChanged("Point AP selected: "+sample.selectedBssid);
                dialog.dismiss();
            })
            .setNegativeButton("Cancel",null).show();
    }
    private int findSelectedIndex(List<Reading> options,String chosen){
        for(int i=0;i<options.size();i++)if(options.get(i).bssid.equalsIgnoreCase(chosen))return i;
        return 0;
    }
    private Sample hitSample(float px,float py,RectF area,float tolerance) {
        Sample nearest=null;
        float best=tolerance*tolerance;
        for(Sample s:samples){
            float x=area.left+s.x*area.width(),y=area.top+s.y*area.height();
            float dx=px-x,dy=py-y;
            float dist=dx*dx+dy*dy;
            if(dist<best){best=dist;nearest=s;}
        }
        return nearest;
    }
    private void showSelectiveClear() {
        final String[] choices={
            "Remove last survey point",
            "Choose survey point to remove",
            "Choose AP pin to remove",
            "Clear all survey points (keep AP pins)",
            "Clear all AP pins (keep survey points)",
            "Clear ALL measurements and AP pins",
            "Restore hidden APs for future detection"
        };
        new AlertDialog.Builder(this).setTitle("Selective Clear")
            .setItems(choices,(dialog,index)->{
                if(index==0){
                    if(samples.isEmpty()){Toast.makeText(this,"No survey points",Toast.LENGTH_SHORT).show();return;}
                    Sample last=samples.get(samples.size()-1);
                    confirmAction("Remove last point","Remove point "+samples.size()+" only?",()->removeSample(last));
                } else if(index==1){
                    if(samples.isEmpty()){Toast.makeText(this,"No survey points",Toast.LENGTH_SHORT).show();return;}
                    String[] names=new String[samples.size()];
                    SimpleDateFormat fmt=new SimpleDateFormat("HH:mm:ss",Locale.US);
                    for(int i=0;i<samples.size();i++)names[i]="Point "+(i+1)+"  •  "+fmt.format(new Date(samples.get(i).timestamp));
                    new AlertDialog.Builder(this).setTitle("Choose point")
                        .setItems(names,(d,w)->showSampleActions(samples.get(w)))
                        .setNegativeButton("Cancel",null).show();
                } else if(index==2){
                    if(apMarkers.isEmpty()){Toast.makeText(this,"No AP pins",Toast.LENGTH_SHORT).show();return;}
                    showApInventory();
                } else if(index==3)confirmAction("Clear survey measurements",
                    "Delete all "+samples.size()+" numbered survey measurements, while keeping "+apMarkers.size()+" AP pins and their notes?",
                    ()->{samples.clear();onSurveyChanged("All survey points cleared");});
                else if(index==4)confirmAction("Clear AP pins",
                    "Remove all "+apMarkers.size()+" AP pins and their editable notes? Previously mapped APs will stay hidden until you restore hidden APs.",
                    ()->{hiddenApKeys.addAll(apMarkers.keySet());apMarkers.clear();moveMarkerKey=null;
                         onSurveyChanged("All AP pins cleared");});
                else if(index==5)confirmAction("Clear ALL survey data",
                    "Delete all "+samples.size()+" measurements and "+apMarkers.size()+" AP pins, including their editable notes? The JPG floor plan remains.",
                    ()->{samples.clear();apMarkers.clear();hiddenApKeys.clear();
                         moveMarkerKey=null;onSurveyChanged("All survey data cleared");});
                else if(index==6)confirmAction("Restore hidden APs",
                    "Allow "+hiddenApKeys.size()+" previously removed AP identifiers to be detected on NEW survey taps?",
                    ()->{hiddenApKeys.clear();onSurveyChanged("Hidden APs restored for future detection");});
            })
            .setNegativeButton("Cancel",null).show();
    }
    private EditText field(LinearLayout parent,String label,String value,boolean multi) {
        TextView t=text(label,12);parent.addView(t);
        EditText e=new EditText(this);e.setSingleLine(!multi);
        if(multi){e.setMinLines(2);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);}
        e.setText(value==null?"":value);
        parent.addView(e);
        return e;
    }
    private InventoryStore.Entry linkedAp(String bssid){
        return inventoryIndex.get(InventoryStore.normalizeMac(bssid));
    }
    private String inventoryLabel(String bssid) {
        InventoryStore.Entry entry=linkedAp(bssid);
        return entry==null?"":entry.displayName()+"  IP "+entry.ip;
    }
    private void editApMarker(ApMarker m) {
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16),dp(8),dp(16),dp(8));
        InventoryStore.Entry linked=linkedAp(m.bssid);
        form.addView(text("SSID: "+m.ssid+"\nBSSID / MAC: "+m.bssid+
            (linked==null?"\nInventory: NOT MATCHED. Link BSSID in Import AP List.":
              "\nImported AP: "+linked.displayName()+"\nIP: "+linked.ip+"\nWired MAC: "+linked.mac)+
            "\nPeak RSSI: "+m.bestDbm+" dBm ("+m.frequency+" MHz)"+
            "\nPosition: "+(m.pinned?"Manually positioned":"Estimated from strongest survey reading"),12));
        EditText name=field(form,"AP name / hostname",m.apName,false);
        EditText room=field(form,"Building / room / closet",m.room,false);
        EditText sw=field(form,"Switch / port",m.switchPort,false);
        EditText note=field(form,"Notes",m.notes,true);
        CheckBox verified=new CheckBox(this);
        verified.setText("I physically verified this AP location");
        verified.setChecked(m.verified);
        form.addView(verified);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("Edit AP pin")
            .setView(scroll)
            .setPositiveButton("Save",null)
            .setNeutralButton("Move pin",null)
            .setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(ignored->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                m.apName=name.getText().toString().trim();
                m.room=room.getText().toString().trim();
                m.switchPort=sw.getText().toString().trim();
                m.notes=note.getText().toString().trim();
                m.verified=verified.isChecked();
                persist();map.invalidate();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                m.apName=name.getText().toString().trim();
                m.room=room.getText().toString().trim();
                m.switchPort=sw.getText().toString().trim();
                m.notes=note.getText().toString().trim();
                m.verified=verified.isChecked();
                moveMarkerKey=m.bssid;
                persist();
                status.setText("Tap the AP's physical location on the map to move "+m.name()+".");
                Toast.makeText(this,"Tap new AP position on map",Toast.LENGTH_LONG).show();
                dialog.dismiss();
            });
        });
        dialog.show();
    }
    private ApMarker hitMarker(float px,float py,RectF area,float tolerance) {
        ApMarker found=null;float closest=tolerance*tolerance;
        for(ApMarker m:apMarkers.values()){
            float x=area.left+m.x*area.width();
            float y=area.top+m.y*area.height();
            float dx=px-x,dy=py-y;
            float d=dx*dx+dy*dy;
            if(d<closest){closest=d;found=m;}
        }
        return found;
    }
    // Legacy clear entry now opens the safer granular selector.
    private void confirmClear(){showSelectiveClear();}

    private int rssi(Sample s) {
        int best=-127;
        for (Reading r:s.readings) {
            if (target.equals("BEST") ||
                (target.startsWith("S:") && r.ssid.equals(target.substring(2))) ||
                (target.startsWith("B:") && r.bssid.equalsIgnoreCase(target.substring(2))))
                best=Math.max(best,r.dbm);
        }
        return best;
    }
    private String csv(String v) { return "\"" + (v==null?"":v).replace("\"","\"\"") + "\""; }
    private String makeCsv() {
        StringBuilder sb=new StringBuilder("timestamp,x_percent,y_percent,ssid,bssid,frequency_mhz,channel,rssi_dbm,source,point_selected_bssid,point_scan_fresh,point_scan_time,mode,dwell_seconds,connected_bssid,connected_ssid,connected_avg_dbm,connected_min_dbm,connected_max_dbm,connected_rssi_samples,connected_frequency_mhz,connected_link_speed_mbps,connected_bssid_changes,connected_only\n");
        SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);
        for (Sample s:samples) for (Reading r:s.readings) {
            sb.append(csv(fmt.format(new Date(s.timestamp)))).append(',')
              .append(String.format(Locale.US,"%.2f,%.2f",s.x*100,s.y*100)).append(',')
              .append(csv(r.ssid)).append(',').append(csv(r.bssid)).append(',')
              .append(r.mhz).append(',').append(channel(r.mhz)).append(',')
              .append(r.dbm).append(',').append(csv(s.connectedOnly?"Connected live RSSI":SOURCE)).append(',')
              .append(csv(s.selectedBssid)).append(',').append(s.fresh).append(',')
              .append(csv(s.scanAt>0?fmt.format(new Date(s.scanAt)):"no fresh scan")).append(',')
              .append(csv(s.mode)).append(',').append(s.dwellSeconds).append(',')
              .append(csv(s.connectedBssid)).append(',').append(csv(s.connectedSsid)).append(',')
              .append(s.connectedAvg).append(',').append(s.connectedMin).append(',')
              .append(s.connectedMax).append(',').append(s.connectedSamples).append(',')
              .append(s.connectedFreq).append(',').append(s.connectedLinkMbps).append(',')
              .append(s.connectedRoams).append(',').append(s.connectedOnly).append('\n');
        }
        return sb.toString();
    }
    private String makeApCsv() {
        StringBuilder b=new StringBuilder("ap_name,ssid,bssid,best_rssi_dbm,frequency_mhz,x_percent,y_percent,room_closet,switch_port,notes,manually_positioned,physically_verified,source\n");
        for(ApMarker m:apMarkers.values()){
            b.append(csv(m.apName)).append(',').append(csv(m.ssid)).append(',')
            .append(csv(m.bssid)).append(',').append(m.bestDbm).append(',')
            .append(m.frequency).append(',')
            .append(String.format(Locale.US,"%.2f,%.2f",100*m.x,100*m.y)).append(',')
            .append(csv(m.room)).append(',').append(csv(m.switchPort)).append(',')
            .append(csv(m.notes)).append(',').append(m.pinned).append(',')
            .append(m.verified).append(',').append(csv(SOURCE)).append('\n');
        }
        return b.toString();
    }
    private void persist() {
        // Immutable snapshot references: samples/readings aren't modified after capture.
        // AP marker fields are copied on the UI thread to avoid background races.
        final ArrayList<Sample> surveySnapshot=new ArrayList<>(samples);
        final ArrayList<ApMarker> pinSnapshot=new ArrayList<>();
        for(ApMarker pin:apMarkers.values()) {
            ApMarker m=new ApMarker(new Reading(pin.ssid,pin.bssid,pin.bestDbm,pin.frequency),pin.x,pin.y);
            m.apName=pin.apName;m.room=pin.room;m.switchPort=pin.switchPort;
            m.notes=pin.notes;m.pinned=pin.pinned;m.verified=pin.verified;
            pinSnapshot.add(m);
        }
        final ArrayList<String> hiddenSnapshot=new ArrayList<>(hiddenApKeys);
        final int threshold=nearbyThreshold;
        final int duration=stopGoSeconds;
        final boolean selectedMode=stopGoEnabled;
        final int ticket=saveRevision.incrementAndGet();
        saveExecutor.execute(()->{
            if(ticket!=saveRevision.get())return;
            try {
                JSONObject root=new JSONObject();
                JSONArray array=new JSONArray();
                for(Sample sample:surveySnapshot) {
                    JSONObject entry=new JSONObject();
                    entry.put("x",sample.x);entry.put("y",sample.y);
                    entry.put("time",sample.timestamp);
                    entry.put("fresh",sample.fresh);
                    entry.put("scanAt",sample.scanAt);
                    entry.put("selectedBssid",sample.selectedBssid);
                    entry.put("mode",sample.mode);entry.put("dwellSeconds",sample.dwellSeconds);
                    entry.put("connectedBssid",sample.connectedBssid);
                    entry.put("connectedSsid",sample.connectedSsid);
                    entry.put("connectedAvg",sample.connectedAvg);entry.put("connectedMin",sample.connectedMin);
                    entry.put("connectedMax",sample.connectedMax);entry.put("connectedSamples",sample.connectedSamples);
                    entry.put("connectedFreq",sample.connectedFreq);entry.put("connectedLinkMbps",sample.connectedLinkMbps);
                    entry.put("connectedRoams",sample.connectedRoams);entry.put("connectedOnly",sample.connectedOnly);
                    JSONArray readings=new JSONArray();
                    for(Reading reading:sample.readings) {
                        JSONObject r=new JSONObject();
                        r.put("ssid",reading.ssid);r.put("bssid",reading.bssid);
                        r.put("dbm",reading.dbm);r.put("mhz",reading.mhz);readings.put(r);
                    }
                    entry.put("readings",readings);array.put(entry);
                }
                root.put("samples",array);
                root.put("nearbyThreshold",threshold);
                root.put("stopGoEnabled",selectedMode);
                root.put("stopGoSeconds",duration);
                JSONArray pins=new JSONArray();
                for(ApMarker m:pinSnapshot) {
                    JSONObject p=new JSONObject();
                    p.put("bssid",m.bssid);p.put("ssid",m.ssid);p.put("apName",m.apName);
                    p.put("room",m.room);p.put("switchPort",m.switchPort);p.put("notes",m.notes);
                    p.put("x",m.x);p.put("y",m.y);p.put("bestDbm",m.bestDbm);
                    p.put("frequency",m.frequency);p.put("pinned",m.pinned);
                    p.put("verified",m.verified);pins.put(p);
                }
                root.put("apMarkers",pins);
                JSONArray removed=new JSONArray();
                for(String id:hiddenSnapshot)removed.put(id);
                root.put("hiddenApKeys",removed);
                if(ticket!=saveRevision.get())return;
                byte[] bytes=root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                AtomicFile atomic=new AtomicFile(new File(getFilesDir(),"survey.json"));
                FileOutputStream output=null;
                try {
                    output=atomic.startWrite();
                    output.write(bytes);
                    atomic.finishWrite(output);
                } catch(Exception e) {
                    if(output!=null)atomic.failWrite(output);
                    throw e;
                }
            }catch(Exception e){
                runOnUiThread(()->status.setText("Survey save failed: "+e.getMessage()));
            }
        });
    }
    private void restore() {
        try {
            File image=new File(getFilesDir(),"floorplan.jpg");
            if (image.exists()) floor=BitmapFactory.decodeFile(image.getAbsolutePath());
            File stored=new File(getFilesDir(),"survey.json");
            if (stored.exists()) {
                byte[] bytes=java.nio.file.Files.readAllBytes(stored.toPath());
                JSONObject root=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
                nearbyThreshold=root.optInt("nearbyThreshold",-55);
                stopGoEnabled=root.optBoolean("stopGoEnabled",true);
                stopGoSeconds=root.optInt("stopGoSeconds",5);
                if(stopGoSeconds!=3&&stopGoSeconds!=5&&stopGoSeconds!=8&&stopGoSeconds!=10)stopGoSeconds=5;
                if(stopGoModeButton!=null)stopGoModeButton.setText(stopGoEnabled?"Stop & Go: ON":"Stop & Go: OFF");
                if(stopGoDurationButton!=null)stopGoDurationButton.setText("Dwell: "+stopGoSeconds+" sec");
                if(sensitivityButton!=null)sensitivityButton.setText("Near: "+nearbyThreshold+" dBm");
                JSONArray removed=root.optJSONArray("hiddenApKeys");
                if(removed!=null)for(int i=0;i<removed.length();i++)hiddenApKeys.add(removed.optString(i));
                JSONArray pins=root.optJSONArray("apMarkers");
                if(pins!=null)for(int j=0;j<pins.length();j++){
                    JSONObject p=pins.getJSONObject(j);
                    Reading initial=new Reading(p.optString("ssid"),p.optString("bssid"),
                        p.optInt("bestDbm",-127),p.optInt("frequency",0));
                    if(initial.bssid.isEmpty())continue;
                    ApMarker pin=new ApMarker(initial,(float)p.optDouble("x"),(float)p.optDouble("y"));
                    pin.apName=p.optString("apName");pin.room=p.optString("room");
                    pin.switchPort=p.optString("switchPort");pin.notes=p.optString("notes");
                    pin.pinned=p.optBoolean("pinned");pin.verified=p.optBoolean("verified");
                    apMarkers.put(pin.bssid,pin);
                }
                JSONArray array=root.optJSONArray("samples");
                if (array!=null) for (int i=0;i<array.length();i++) {
                    JSONObject a=array.getJSONObject(i);
                    Sample s=new Sample((float)a.getDouble("x"),(float)a.getDouble("y"));
                    s.timestamp=a.optLong("time",System.currentTimeMillis());
                    s.fresh=a.optBoolean("fresh",false);
                    s.scanAt=a.optLong("scanAt",0L);
                    s.selectedBssid=a.optString("selectedBssid","");
                    s.mode=a.optString("mode","TAP");
                    s.dwellSeconds=a.optInt("dwellSeconds",0);
                    s.connectedBssid=a.optString("connectedBssid","");
                    s.connectedSsid=a.optString("connectedSsid","");
                    s.connectedAvg=a.optInt("connectedAvg",-127);
                    s.connectedMin=a.optInt("connectedMin",-127);
                    s.connectedMax=a.optInt("connectedMax",-127);
                    s.connectedSamples=a.optInt("connectedSamples",0);
                    s.connectedFreq=a.optInt("connectedFreq",0);
                    s.connectedLinkMbps=a.optInt("connectedLinkMbps",-1);
                    s.connectedRoams=a.optInt("connectedRoams",0);
                    s.connectedOnly=a.optBoolean("connectedOnly",false);
                    JSONArray readings=a.optJSONArray("readings");
                    if (readings!=null) for (int j=0;j<readings.length();j++) {
                        JSONObject r=readings.getJSONObject(j);
                        s.readings.add(new Reading(r.optString("ssid"),r.optString("bssid"),
                            r.optInt("dbm"),r.optInt("mhz")));
                    }
                    samples.add(s);
                }
            }
            map.regenerate();
            if (floor!=null) status.setText("Restored "+samples.size()+" survey positions and "+apMarkers.size()+" AP pins.");
        } catch (Exception e) { status.setText("Could not restore previous survey: "+e.getMessage()); }
    }

    private class MapView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final ScaleGestureDetector scaler;
        private Bitmap heat;
        private final ExecutorService heatExecutor=Executors.newSingleThreadExecutor();
        private final AtomicInteger heatRevision=new AtomicInteger();
        private float zoom=1f, panX=0f, panY=0f;
        private float lastX,lastY,downX,downY;
        private boolean moved;
        MapView(Context context) {
            super(context);
            setBackgroundColor(Color.rgb(235,240,246));
            scaler=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float next=Math.max(1f,Math.min(6f,zoom*d.getScaleFactor()));
                    if (next!=zoom) {
                        panX=d.getFocusX()-(d.getFocusX()-panX)*next/zoom;
                        panY=d.getFocusY()-(d.getFocusY()-panY)*next/zoom;
                        zoom=next; invalidate();
                    }
                    return true;
                }
            });
        }
        void resetView() { zoom=1;panX=panY=0;regenerate(); }
        private RectF placement(int width,int height) {
            if (floor==null) return new RectF(0,0,width,height);
            float factor=Math.min((float)width/floor.getWidth(),(float)height/floor.getHeight());
            float w=floor.getWidth()*factor,h=floor.getHeight()*factor;
            return new RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (floor==null) {
                Paint t=new Paint(Paint.ANTI_ALIAS_FLAG);t.setColor(Color.DKGRAY);t.setTextSize(dp(16));
                c.drawText("Import a JPG floor plan to start",dp(12),getHeight()/2f,t);
                return;
            }
            int saved=c.save();
            c.translate(panX,panY);c.scale(zoom,zoom);
            drawMap(c,placement(getWidth(),getHeight()),true);
            c.restoreToCount(saved);
        }
        private void drawMap(Canvas c,RectF area,boolean showPins) {
            paint.setAlpha(255);
            c.drawBitmap(floor,null,area,paint);
            if (heat!=null) {
                paint.setAlpha(255);
                c.drawBitmap(heat,null,area,paint);
            }
            if (showPins) {
                Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
                float pointRadius=Math.max(dp(5),Math.min(dp(9),area.width()/175f));
                for (int i=0;i<samples.size();i++) {
                    Sample s=samples.get(i);
                    float x=area.left+area.width()*s.x,y=area.top+area.height()*s.y;
                    p.setColor(Color.WHITE);p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x,y,pointRadius,p);
                    p.setColor(Color.BLACK);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,pointRadius/4));
                    c.drawCircle(x,y,pointRadius,p);p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.BLACK);p.setTextSize(Math.max(dp(7),pointRadius));
                    p.setTextAlign(Paint.Align.CENTER);
                    c.drawText(String.valueOf(i+1),x,y+pointRadius/3,p);
                    if(displayPointLabels){
                        Reading ap=s.displayedAp();
                        if(ap!=null){
                            String label=(ap.ssid==null||ap.ssid.isEmpty()?"AP":ap.ssid);
                            if(label.length()>13)label=label.substring(0,12)+"…";
                            float fs=Math.max(dp(9),Math.min(dp(12),area.width()/160f));
                            p.setTextAlign(Paint.Align.LEFT);p.setTextSize(fs);
                            String line1=label+"  "+ap.dbm+" dBm"+
                                (s.connectedOnly?" [connected]":s.fresh?"":" [cached]");
                            String line2=ap.bssid.toUpperCase(Locale.US);
                            InventoryStore.Entry identified=linkedAp(ap.bssid);
                            if(identified!=null)line1=identified.displayName()+"  "+ap.dbm+" dBm"+
                                (s.connectedOnly?" [connected]":"");
                            float boxWidth=Math.max(p.measureText(line1),p.measureText(line2))+dp(8);
                            float bx=x+pointRadius+dp(2),by=y+pointRadius+dp(2);
                            p.setColor(Color.argb(236,248,250,255));
                            c.drawRoundRect(new RectF(bx,by,bx+boxWidth,by+fs*2.55f),dp(3),dp(3),p);
                            p.setColor(s.fresh?Color.rgb(19,55,95):Color.rgb(145,77,25));
                            c.drawText(line1,bx+dp(4),by+fs,p);
                            p.setTextSize(fs*0.94f);
                            c.drawText(line2,bx+dp(4),by+fs*2.15f,p);
                        }
                    }
                }
                // AP pins are drawn after sample dots and the heatmap, so they remain visible.
                float radius=Math.max(dp(10),Math.min(dp(17),area.width()/95f));
                float font=Math.max(dp(10),Math.min(dp(14),area.width()/105f));
                for(ApMarker m:apMarkers.values()){
                    float x=area.left+area.width()*m.x,y=area.top+area.height()*m.y;
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(m.verified?Color.rgb(34,153,87):m.pinned?
                        Color.rgb(52,111,206):Color.rgb(225,120,35));
                    c.drawCircle(x,y,radius,p);
                    p.setColor(Color.WHITE);
                    p.setTextSize(radius*0.85f);p.setTextAlign(Paint.Align.CENTER);
                    c.drawText("AP",x,y+radius*0.28f,p);
                    String name=m.name();
                    if(name.length()>22)name=name.substring(0,21)+"…";
                    String mac=m.bssid.toUpperCase(Locale.US);
                    float tx=x+radius+dp(3),ty=y-radius*0.2f;
                    p.setTextSize(font);p.setTextAlign(Paint.Align.LEFT);
                    InventoryStore.Entry matched=linkedAp(m.bssid);
                    String effectiveName=(matched!=null&&m.apName.isEmpty()&&!matched.name.isEmpty())?matched.displayName():name;
                    if(effectiveName.length()>22)effectiveName=effectiveName.substring(0,21)+"…";
                    String line2=matched==null?mac:(matched.ip+"  "+mac);
                    float tw=Math.max(p.measureText(effectiveName),p.measureText(line2));
                    p.setColor(Color.argb(225,255,255,255));
                    c.drawRoundRect(new RectF(tx-dp(2),ty-font-dp(2),tx+tw+dp(3),ty+font+dp(5)),
                        dp(3),dp(3),p);
                    p.setColor(Color.BLACK);
                    InventoryStore.Entry imported=linkedAp(m.bssid);
                    if(imported!=null&&m.apName.isEmpty()&&!imported.name.isEmpty()){
                        String importedName=imported.displayName();
                        if(importedName.length()>22)importedName=importedName.substring(0,21)+"…";
                        c.drawText(importedName,tx,ty,p);
                    } else c.drawText(name,tx,ty,p);
                    p.setTextSize(font*0.87f);
                    c.drawText(imported==null?mac:(imported.ip+"  "+mac),tx,ty+font,p);
                }
                // Progress ring marks the selected Stop & Go dwell position.
                if(stopGoActive){
                    float x=area.left+area.width()*stopGoX,y=area.top+area.height()*stopGoY;
                    long elapsed=android.os.SystemClock.elapsedRealtime()-stopGoStartElapsed;
                    float fraction=Math.max(0f,Math.min(1f,elapsed/(stopGoSeconds*1000f)));
                    p.setStyle(Paint.Style.FILL);p.setColor(Color.argb(210,255,255,255));
                    c.drawCircle(x,y,dp(24),p);
                    p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(5));
                    p.setColor(Color.rgb(27,110,189));
                    c.drawArc(new RectF(x-dp(23),y-dp(23),x+dp(23),y+dp(23)),-90,360*fraction,false,p);
                    p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);
                    p.setTextSize(dp(13));p.setColor(Color.rgb(20,65,106));
                    int secondsLeft=(int)Math.max(0,Math.ceil((stopGoSeconds*1000f-elapsed)/1000f));
                    c.drawText(secondsLeft+"s",x,y+dp(5),p);
                }
            }
        }
        Bitmap exportImage() {
            if (floor==null) return null;
            Bitmap out=Bitmap.createBitmap(floor.getWidth(),floor.getHeight(),Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(out);
            drawMap(c,new RectF(0,0,out.getWidth(),out.getHeight()),true);
            Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setTextSize(Math.max(16f, out.getWidth()/75f));
            label.setColor(Color.WHITE);
            c.drawRect(0,0,out.getWidth(),label.getTextSize()*2.1f,label);
            label.setColor(Color.BLACK);
            c.drawText("Wi-Fi: "+(target.equals("BEST")?"strongest AP":target)+" | "+samples.size()+" survey points | Android Wi-Fi",
                12,label.getTextSize()*1.5f,label);
            return out;
        }
        void release() {
            heatRevision.incrementAndGet();
            heatExecutor.shutdownNow();
            if(heat!=null){heat.recycle();heat=null;}
        }
        void regenerate() {
            final int revision=heatRevision.incrementAndGet();
            if(floor==null || samples.isEmpty()) {
                if(heat!=null){heat.recycle();heat=null;}
                invalidate();
                return;
            }
            // Cache only one RSSI value for each survey sample on the UI thread.
            final int size=samples.size();
            final float[] locationsX=new float[size];
            final float[] locationsY=new float[size];
            final int[] signal=new int[size];
            for(int i=0;i<size;i++){
                Sample sample=samples.get(i);
                locationsX[i]=sample.x;locationsY[i]=sample.y;
                signal[i]=rssi(sample);
            }
            // Latest request wins; previous work is dropped rather than queuing UI freezes.
            heatExecutor.execute(()->{
                if(revision!=heatRevision.get())return;
                final int n=90;
                int[] pixels=new int[n*n];
                for(int y=0;y<n;y++){
                    if(revision!=heatRevision.get()||Thread.currentThread().isInterrupted())return;
                    for(int x=0;x<n;x++){
                        double nx=(x+0.5)/n,ny=(y+0.5)/n;
                        double sum=0,weight=0,nearest=99;
                        for(int i=0;i<size;i++){
                            int dbm=signal[i];if(dbm<=-127)continue;
                            double dx=nx-locationsX[i],dy=ny-locationsY[i];
                            double d2=dx*dx+dy*dy;
                            nearest=Math.min(nearest,Math.sqrt(d2));
                            double w=1/(d2+0.00025);
                            sum+=w*dbm;weight+=w;
                        }
                        if(weight==0||nearest>0.30){pixels[y*n+x]=Color.TRANSPARENT;continue;}
                        double value=sum/weight;
                        float hue=(float)Math.max(0,Math.min(120,(value+90)/30*120));
                        int alpha=(int)(160*Math.max(0,1-nearest/0.30));
                        pixels[y*n+x]=Color.HSVToColor(alpha,new float[]{hue,0.90f,0.94f});
                    }
                }
                if(revision!=heatRevision.get())return;
                Bitmap result=Bitmap.createBitmap(pixels,n,n,Bitmap.Config.ARGB_8888);
                post(()->{
                    if(revision!=heatRevision.get()||isFinishing()||isDestroyed()){
                        result.recycle();
                        return;
                    }
                    Bitmap old=heat;
                    heat=result;
                    if(old!=null)old.recycle();
                    invalidate();
                });
            });
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX=downX=e.getX();lastY=downY=e.getY();moved=false;return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    moved=true;return true;
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount()==1 && !scaler.isInProgress()) {
                        float x=e.getX(),y=e.getY();
                        if (Math.hypot(x-downX,y-downY)>dp(9)) moved=true;
                        if (moved) { panX+=x-lastX;panY+=y-lastY;invalidate(); }
                        lastX=x;lastY=y;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && !scaler.isInProgress() && floor!=null) {
                        float x=(e.getX()-panX)/zoom,y=(e.getY()-panY)/zoom;
                        RectF r=placement(getWidth(),getHeight());
                        if (r.contains(x,y)) {
                            if(moveMarkerKey!=null)capture((x-r.left)/r.width(),(y-r.top)/r.height());
                            else {
                                ApMarker hit=hitMarker(x,y,r,dp(24)/zoom);
                                if(hit!=null)showApActions(hit);
                                else {
                                    Sample point=hitSample(x,y,r,dp(15)/zoom);
                                    if(point!=null)showSampleActions(point);
                                    else if(android.os.SystemClock.elapsedRealtime()-lastTouchCaptured>=250){
                                        lastTouchCaptured=android.os.SystemClock.elapsedRealtime();
                                        capture((x-r.left)/r.width(),(y-r.top)/r.height());
                                    }
                                }
                            }
                        }
                    }
                    return true;
                default: return true;
            }
        }
    }
}
