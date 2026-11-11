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
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
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
    // A marker is an RSSI-peak survey estimate, not a triangulated AP position.
    private final LinkedHashMap<String,ApMarker> apMarkers = new LinkedHashMap<>();
    private final android.os.Handler scanHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean autoScan = false;
    private int nearbyThreshold = -55;
    private String moveMarkerKey = null;
    private Button autoScanButton, sensitivityButton;
    private final Runnable scanLoop = new Runnable() {
        @Override public void run() {
            if (!autoScan || isFinishing()) return;
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
        long timestamp;
        ArrayList<Reading> readings = new ArrayList<>();
        Sample(float a, float b) { x=a; y=b; timestamp=System.currentTimeMillis(); }
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            boolean fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            if (fresh) lastCompletedScan = System.currentTimeMillis();
            loadWifiResults(fresh ? "Scan finished" : "Scan not updated; readings may be cached");
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
        TextView title = text("Sidekick Survey  |  v0.3 AP Mapping",20);
        title.setTextColor(Color.rgb(16,60,100));
        root.addView(title);
        root.addView(button("Open Wi-Fi Analyzer  •  APs  |  Channels  |  Signal  |  Health",
            v->startActivity(new Intent(this,AnalyzerActivity.class))));
        usbStatus = text("",12); root.addView(usbStatus);
        status = text("Walk, scan, tap your location: close APs get estimated pins.",13);
        root.addView(status);
        addButtons(root,
            button("Import JPG", v-> pickMap()),
            button("Scan Wi-Fi", v-> scanWifi()),
            button("USB status", v-> updateUsb()));
        autoScanButton = button("Auto Scan: OFF",v->toggleAutoScan());
        sensitivityButton = button("Near: -55 dBm",v->chooseSensitivity());
        addButtons(root,autoScanButton,sensitivityButton,
            button("AP Inventory",v->showApInventory()));
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
            button("Clear", v-> confirmClear()));
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
        if(usbStatus!=null)updateUsb();
        scanHandler.removeCallbacks(scanLoop);
        if(autoScan)scanHandler.postDelayed(scanLoop,45000L);
    }
    @Override public void onPause() {
        scanHandler.removeCallbacks(scanLoop);
        super.onPause();
    }
    @Override public void onDestroy() {
        scanHandler.removeCallbacks(scanLoop);
        if (registered) unregisterReceiver(scanReceiver);
        super.onDestroy();
    }
    private void toggleAutoScan() {
        autoScan=!autoScan;
        autoScanButton.setText(autoScan?"Auto Scan: ON":"Auto Scan: OFF");
        scanHandler.removeCallbacks(scanLoop);
        if(autoScan) {
            scanWifi();
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
            if (requested) status.setText("Wi-Fi scan requested; waiting for results...");
            else loadWifiResults("Scan throttled/declined: showing cached scan results");
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
        String date = lastCompletedScan==0?"none":"updated "+new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date(lastCompletedScan));
        status.setText(note+" | "+current.size()+" APs | "+date+" | "+samples.size()+" map points");
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
                samples.clear(); apMarkers.clear(); moveMarkerKey=null; target="BEST";
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
        if (floor==null) { Toast.makeText(this,"Import JPG floor plan first.",Toast.LENGTH_SHORT).show(); return; }
        if (moveMarkerKey!=null) {
            ApMarker selected=apMarkers.get(moveMarkerKey);
            moveMarkerKey=null;
            if(selected!=null) {
                selected.x=x;selected.y=y;selected.pinned=true;
                persist();map.invalidate();
                status.setText("Moved "+selected.name()+" to manually selected position.");
            }
            return;
        }
        if (current.isEmpty()) { Toast.makeText(this,"Scan Wi-Fi first; no AP readings yet.",Toast.LENGTH_LONG).show(); return; }
        Sample sample=new Sample(x,y);
        sample.readings.addAll(current);
        samples.add(sample);
        int placed=placeNearbyAps(sample);
        persist();
        updateSelectors(); map.regenerate();
        String stale=(lastCompletedScan==0 || System.currentTimeMillis()-lastCompletedScan>120000L)
            ?" Readings may be stale; tap Scan Wi-Fi.":"";
        status.setText("Point "+samples.size()+" saved; "+placed+" new AP pins, "+apMarkers.size()+" total."+stale);
    }
    private int placeNearbyAps(Sample s) {
        // Never infer a new AP pin from a stale or unconfirmed Android scan.
        if(lastCompletedScan==0 || System.currentTimeMillis()-lastCompletedScan>120000L)return 0;
        int added=0;
        // Samples are already sorted strongest-first. Never create an unlimited pile
        // of AP pins at a single point. Existing estimates can improve at any point.
        for(Reading r:s.readings) {
            if(r.bssid==null || r.bssid.trim().isEmpty() || r.dbm<nearbyThreshold)continue;
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
            names[i]=m.name()+" | "+m.bssid+" | "+m.bestDbm+" dBm"+(m.verified?" ✓ verified":"");
        }
        new AlertDialog.Builder(this).setTitle("AP Inventory ("+list.size()+")")
            .setItems(names,(dialog,which)->editApMarker(list.get(which)))
            .setPositiveButton("Close",null).show();
    }
    private EditText field(LinearLayout parent,String label,String value,boolean multi) {
        TextView t=text(label,12);parent.addView(t);
        EditText e=new EditText(this);e.setSingleLine(!multi);
        if(multi){e.setMinLines(2);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);}
        e.setText(value==null?"":value);
        parent.addView(e);
        return e;
    }
    private void editApMarker(ApMarker m) {
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16),dp(8),dp(16),dp(8));
        form.addView(text("SSID: "+m.ssid+"\nBSSID / MAC: "+m.bssid+
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
    private void confirmClear() {
        if(samples.isEmpty()&&apMarkers.isEmpty())return;
        new AlertDialog.Builder(this).setTitle("Clear survey and AP pins?")
            .setMessage("Delete ALL local survey points and AP inventory notes for this map? Export a JPG and AP CSV first if you want to keep them.")
            .setPositiveButton("Delete all",(d,w)->{
                samples.clear();apMarkers.clear();moveMarkerKey=null;
                persist();updateSelectors();map.regenerate();
                status.setText("Survey points and AP pins cleared. Floor plan kept.");
            })
            .setNegativeButton("Cancel",null).show();
    }
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
        StringBuilder sb=new StringBuilder("timestamp,x_percent,y_percent,ssid,bssid,frequency_mhz,channel,rssi_dbm,source\n");
        SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);
        for (Sample s:samples) for (Reading r:s.readings) {
            sb.append(csv(fmt.format(new Date(s.timestamp)))).append(',')
              .append(String.format(Locale.US,"%.2f,%.2f",s.x*100,s.y*100)).append(',')
              .append(csv(r.ssid)).append(',').append(csv(r.bssid)).append(',')
              .append(r.mhz).append(',').append(channel(r.mhz)).append(',')
              .append(r.dbm).append(',').append(csv(SOURCE)).append('\n');
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
        try {
            JSONObject root=new JSONObject();
            JSONArray array=new JSONArray();
            for (Sample s:samples) {
                JSONObject sample=new JSONObject();
                sample.put("x",s.x);sample.put("y",s.y);sample.put("time",s.timestamp);
                JSONArray readings=new JSONArray();
                for (Reading r:s.readings) {
                    JSONObject o=new JSONObject();
                    o.put("ssid",r.ssid);o.put("bssid",r.bssid);
                    o.put("dbm",r.dbm);o.put("mhz",r.mhz);readings.put(o);
                }
                sample.put("readings",readings);array.put(sample);
            }
            root.put("samples",array);
            root.put("nearbyThreshold",nearbyThreshold);
            JSONArray pins=new JSONArray();
            for(ApMarker m:apMarkers.values()){
                JSONObject p=new JSONObject();
                p.put("bssid",m.bssid);p.put("ssid",m.ssid);p.put("apName",m.apName);
                p.put("room",m.room);p.put("switchPort",m.switchPort);p.put("notes",m.notes);
                p.put("x",m.x);p.put("y",m.y);p.put("bestDbm",m.bestDbm);
                p.put("frequency",m.frequency);p.put("pinned",m.pinned);
                p.put("verified",m.verified);pins.put(p);
            }
            root.put("apMarkers",pins);
            try (FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"survey.json"))) {
                out.write(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) { status.setText("Could not save survey: "+e.getMessage()); }
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
                if(sensitivityButton!=null)sensitivityButton.setText("Near: "+nearbyThreshold+" dBm");
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
                    float tw=Math.max(p.measureText(name),p.measureText(mac));
                    p.setColor(Color.argb(225,255,255,255));
                    c.drawRoundRect(new RectF(tx-dp(2),ty-font-dp(2),tx+tw+dp(3),ty+font+dp(5)),
                        dp(3),dp(3),p);
                    p.setColor(Color.BLACK);
                    c.drawText(name,tx,ty,p);
                    p.setTextSize(font*0.87f);
                    c.drawText(mac,tx,ty+font,p);
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
        void regenerate() {
            if (heat!=null) { heat.recycle(); heat=null; }
            if (floor==null || samples.isEmpty()) { invalidate(); return; }
            int n=120;
            int[] pixels=new int[n*n];
            for (int y=0;y<n;y++) for (int x=0;x<n;x++) {
                double nx=(x+0.5)/n, ny=(y+0.5)/n;
                double sum=0,weight=0,nearest=99;
                for (Sample s:samples) {
                    int dbm=rssi(s);
                    if (dbm<=-127) continue;
                    double dx=nx-s.x,dy=ny-s.y;
                    double d2=dx*dx+dy*dy;
                    nearest=Math.min(nearest,Math.sqrt(d2));
                    double w=1/(d2+0.00025);
                    sum+=w*dbm;weight+=w;
                }
                if (weight==0 || nearest>0.30) { pixels[y*n+x]=Color.TRANSPARENT;continue; }
                double value=sum/weight;
                float hue=(float)Math.max(0,Math.min(120,(value+90)/30*120));
                int alpha=(int)(160*Math.max(0,1-nearest/0.30));
                pixels[y*n+x]=Color.HSVToColor(alpha,new float[]{hue,0.90f,0.94f});
            }
            heat=Bitmap.createBitmap(pixels,n,n,Bitmap.Config.ARGB_8888);
            invalidate();
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
                                if(hit!=null)editApMarker(hit);
                                else capture((x-r.left)/r.width(),(y-r.top)/r.height());
                            }
                        }
                    }
                    return true;
                default: return true;
            }
        }
    }
}
