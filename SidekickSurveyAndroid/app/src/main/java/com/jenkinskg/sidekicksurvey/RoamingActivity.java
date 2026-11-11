package com.jenkinskg.sidekicksurvey;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v0.6: 500ms *monitoring* of Android's actual Wi-Fi connection.
 * NOT 500ms scanning, forced disconnection, or an automatic roam controller.
 * Android chooses whether/when to roam.
 */
public class RoamingActivity extends Activity {
    private static final int LOCATION_PERMISSION=620;
    private static final long TICK_MS=500;
    private static final long SCAN_INTERVAL_MS=40000;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<AccessPoint> scanAps=new ArrayList<>();
    private final ArrayList<String> roamEvents=new ArrayList<>();
    private WifiManager wifi;
    private TextView currentView, candidateView, otherView, noteView, eventsView, scanView;
    private Button autoButton;
    private ArrayList<InventoryStore.Entry> importedAps=new ArrayList<>();
    private final java.util.HashMap<String,InventoryStore.Entry> inventoryIndex=new java.util.HashMap<>();
    private void rebuildInventoryIndex(){
        inventoryIndex.clear();
        for(InventoryStore.Entry ap:importedAps){
            inventoryIndex.put(ap.mac,ap);
            for(String bssid:ap.bssids)inventoryIndex.put(bssid,ap);
        }
    }
    private boolean listening=false, auto=false;
    private String priorBssid=null, priorSsid="";
    private int roamCount=0, freshScanCount=0, cachedScanCount=0;
    private long lastFresh=0L;
    private String scanMessage="No new scan received";
    private static final class AccessPoint {
        String ssid,bssid;int dbm,mhz;
        AccessPoint(ScanResult r){
            ssid=r.SSID==null?"":r.SSID;
            bssid=r.BSSID==null?"":r.BSSID;
            dbm=r.level;mhz=r.frequency;
        }
    }

    private final BroadcastReceiver scanListener=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            boolean updated=intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED,false);
            if(updated) {
                lastFresh=System.currentTimeMillis();
                freshScanCount++;
                scanMessage="Fresh Android scan #"+freshScanCount;
            }else {
                cachedScanCount++;
                scanMessage="Scan results NOT updated; may be cached";
            }
            readScan();
            render();
        }
    };
    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            if(isFinishing()||isDestroyed())return;
            render();
            handler.postDelayed(this,TICK_MS);
        }
    };
    private final Runnable scheduledScan=new Runnable(){
        @Override public void run(){
            if(!auto||isFinishing()||isDestroyed())return;
            requestScan();
            handler.postDelayed(this,SCAN_INTERVAL_MS);
        }
    };
    private int dp(int size){return (int)(size*getResources().getDisplayMetrics().density+0.5f);}
    private TextView label(String value,int sp){
        TextView t=new TextView(this);
        t.setText(value);t.setTextSize(sp);
        t.setTextColor(Color.rgb(26,44,62));
        t.setPadding(dp(12),dp(8),dp(12),dp(8));return t;
    }
    private Button button(String value,View.OnClickListener listener){
        Button b=new Button(this);b.setText(value);b.setTextSize(12);b.setAllCaps(false);b.setOnClickListener(listener);return b;
    }
    private void row(LinearLayout parent,Button... buttons){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);
        for(Button b:buttons)r.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));
        parent.addView(r);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        wifi=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);setContentView(root);
        TextView title=label("Wi-Fi Roaming Monitor  •  0.5s",19);
        title.setTextColor(Color.rgb(12,74,133));root.addView(title);
        root.addView(label("Monitors the phone's connection every 500 ms. Android decides whether to roam. It does NOT disconnect your Wi-Fi.",12));
        row(root,button("Back to map",v->finish()),
            button("Scan now",v->requestScan()));
        autoButton=button("Auto scans: OFF",v->toggleScan());
        row(root,autoButton,button("Wi-Fi settings",v->startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        scanView=label("",12);root.addView(scanView);
        currentView=label("",16);root.addView(currentView);
        candidateView=label("",14);root.addView(candidateView);
        otherView=label("",13);root.addView(otherView);
        noteView=label("",13);root.addView(noteView);
        root.addView(label("Roaming history (this monitoring session)",15));
        ScrollView scroll=new ScrollView(this);
        eventsView=label("No connection changes yet.",12);
        scroll.addView(eventsView);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(!hasLocation())requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},LOCATION_PERMISSION);
    }
    @Override protected void onResume(){
        super.onResume();
        importedAps=InventoryStore.load(this);
        rebuildInventoryIndex();
        if(!listening){
            IntentFilter filter=new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if(Build.VERSION.SDK_INT>=33)registerReceiver(scanListener,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(scanListener,filter);
            listening=true;
        }
        readScan();handler.removeCallbacks(ticker);
        handler.post(ticker);
        if(auto){handler.removeCallbacks(scheduledScan);handler.postDelayed(scheduledScan,SCAN_INTERVAL_MS);}
    }
    @Override protected void onPause(){
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(scheduledScan);
        if(listening){unregisterReceiver(scanListener);listening=false;}
        super.onPause();
    }
    @Override protected void onDestroy(){
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(scheduledScan);
        super.onDestroy();
    }
    private boolean hasLocation(){
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private boolean locationOn(){
        LocationManager loc=(LocationManager)getSystemService(Context.LOCATION_SERVICE);
        return loc!=null&&(Build.VERSION.SDK_INT<28||loc.isLocationEnabled());
    }
    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] grants){
        super.onRequestPermissionsResult(code,perms,grants);
        if(code==LOCATION_PERMISSION && grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)readScan();
    }
    private void toggleScan(){
        auto=!auto;
        autoButton.setText(auto?"Auto scans: ON":"Auto scans: OFF");
        handler.removeCallbacks(scheduledScan);
        if(auto) {
            requestScan();
            handler.postDelayed(scheduledScan,SCAN_INTERVAL_MS);
        }else scanMessage="Auto scan requests stopped; connection monitoring remains active";
    }
    private void requestScan(){
        if(!hasLocation()){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},LOCATION_PERMISSION);
            return;
        }
        if(!locationOn()){
            new AlertDialog.Builder(this).setTitle("Location required")
                .setMessage("Android requires location services enabled for Wi-Fi scan results.")
                .setPositiveButton("Settings",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton("Cancel",null).show();
            return;
        }
        if(wifi==null||!wifi.isWifiEnabled()){
            Toast.makeText(this,"Enable Wi-Fi first",Toast.LENGTH_SHORT).show();return;
        }
        try{
            if(wifi.startScan())scanMessage="Scan requested; waiting for Android";
            else {
                scanMessage="Scan declined/throttled; showing old results";
                readScan();
            }
            render();
        }catch(SecurityException e){scanMessage="Wi-Fi scan permission denied";render();}
    }
    private void readScan(){
        scanAps.clear();
        if(wifi==null||!hasLocation()||!locationOn())return;
        try{
            List<ScanResult> results=wifi.getScanResults();
            for(ScanResult r:results) {
                if(r.BSSID!=null&&!r.BSSID.isEmpty())scanAps.add(new AccessPoint(r));
            }
            scanAps.sort((a,b)->Integer.compare(b.dbm,a.dbm));
        }catch(SecurityException e){scanMessage="Cannot access scan results; check permissions";}
    }
    private String cleanSsid(String name){
        if(name==null)return "";
        if(name.length()>1&&name.startsWith("\"")&&name.endsWith("\""))return name.substring(1,name.length()-1);
        return name;
    }
    private boolean validBssid(String bssid){
        return bssid!=null&&!bssid.isEmpty()&&!bssid.equalsIgnoreCase("02:00:00:00:00:00");
    }
    private String time(long value){
        return new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date(value));
    }
    private void addEvent(String event){
        roamEvents.add(0,time(System.currentTimeMillis())+"  "+event);
        while(roamEvents.size()>30)roamEvents.remove(roamEvents.size()-1);
        StringBuilder b=new StringBuilder();
        for(String item:roamEvents)b.append(item).append('\n');
        eventsView.setText(b.toString());
    }
    private String apIdentity(String bssid){
        InventoryStore.Entry ap=inventoryIndex.get(InventoryStore.normalizeMac(bssid));
        return ap==null?"(not matched to imported AP list)":
            ap.displayName()+" • IP "+ap.ip+" • AP wired MAC "+ap.mac;
    }
    private void render(){
        if(scanView==null||wifi==null)return;
        WifiInfo connected=null;
        try {if(wifi.isWifiEnabled()&&hasLocation())connected=wifi.getConnectionInfo();}
        catch(SecurityException ignored){}
        String ssid=connected==null?"":cleanSsid(connected.getSSID());
        String bssid=connected==null?"":connected.getBSSID();
        int signal=connected==null?-127:connected.getRssi();
        int frequency=connected==null?0:connected.getFrequency();
        boolean associated=validBssid(bssid);
        if(!associated) bssid="";
        if(associated&&!bssid.equalsIgnoreCase(priorBssid)){
            if(priorBssid!=null&&!priorBssid.isEmpty()){
                roamCount++;
                addEvent("BSSID changed: "+priorBssid+" → "+bssid+
                     (ssid.equalsIgnoreCase(priorSsid)?" (same SSID)":" (SSID changed)")+
                     " • "+signal+" dBm");
            }else addEvent("Connected to "+ssid+" • "+bssid+" • "+signal+" dBm");
            priorBssid=bssid;priorSsid=ssid;
        }else if(!associated&&priorBssid!=null&&!priorBssid.isEmpty()){
            addEvent("Disconnected (previous BSSID "+priorBssid+")");
            priorBssid="";priorSsid="";
        }

        String age=lastFresh==0?"Not confirmed fresh":
            (Math.max(0,(System.currentTimeMillis()-lastFresh)/1000)+" seconds since last fresh scan");
        boolean fresh=lastFresh>0&&System.currentTimeMillis()-lastFresh<=90000;
        scanView.setText(scanMessage+"  •  "+scanAps.size()+" visible BSSIDs\n"+
            "Last fresh scan: "+age+"  •  Roam events: "+roamCount);
        if(!associated)currentView.setText("CURRENT AP: Not connected / BSSID hidden\nEnable Wi-Fi and precise Location permission.");
        else currentView.setText("CURRENT AP\nSSID: "+ssid+
            "\nBSSID: "+bssid.toUpperCase(Locale.US)+
            "\nInventory: "+apIdentity(bssid)+
            "\nSignal: "+signal+" dBm  •  "+frequency+" MHz");

        AccessPoint strongest=scanAps.isEmpty()?null:scanAps.get(0);
        AccessPoint bestSame=null;
        for(AccessPoint a:scanAps){
            if(!ssid.isEmpty()&&!ssid.equalsIgnoreCase("<unknown ssid>")
                &&ssid.equals(a.ssid)) {
                if(bestSame==null||a.dbm>bestSame.dbm)bestSame=a;
            }
        }
        otherView.setText(strongest==null?"STRONGEST VISIBLE AP: No scan data":
            "STRONGEST VISIBLE AP (any SSID)\n"+strongest.ssid+
            "  •  "+strongest.bssid+"  •  "+strongest.dbm+" dBm"+
            "\nInventory: "+apIdentity(strongest.bssid));

        if(bestSame==null){
            candidateView.setText("BEST AP ON CURRENT SSID: no matching BSSID in recent scan.");
            noteView.setText("No scan-based roaming recommendation. Open Wi-Fi settings if needed.");
            return;
        }
        candidateView.setText("STRONGEST SCANNED AP ON CURRENT SSID\n"+
            bestSame.bssid+"  •  "+bestSame.dbm+" dBm  •  "+bestSame.mhz+" MHz"+
            "\nInventory: "+apIdentity(bestSame.bssid));
        if(!fresh) {
            noteView.setText("Scan results are cached/old. They cannot establish which AP is strongest NOW.");
        } else if(!associated){
            noteView.setText("Current connection is unknown. Monitor cannot compare roam candidates.");
        } else if(bestSame.bssid.equalsIgnoreCase(bssid)) {
            noteView.setText("Your phone is already on the strongest scanned BSSID for this SSID.");
        } else {
            int gain=bestSame.dbm-signal;
            if(gain>=8)noteView.setText("POTENTIAL ROAM CANDIDATE: "+bestSame.bssid+
                " is about "+gain+" dB stronger by recent scan. Android decides if/when to roam.");
            else noteView.setText("Another AP is visible, but it is not at least 8 dB stronger."+
                " Avoiding rapid switches helps connection stability.");
        }
    }
}
