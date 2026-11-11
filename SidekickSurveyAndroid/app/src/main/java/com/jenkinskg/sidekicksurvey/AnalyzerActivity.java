package com.jenkinskg.sidekicksurvey;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Analyzer v0.2.
 * RF SOURCE: Android WifiManager only. ScanResult includes AP advertisements,
 * NOT spectrum sweeps, airtime utilization, noise floor, or Sidekick 2 RF data.
 */
public class AnalyzerActivity extends Activity {
    private static final int PERMISSION_REQUEST=220;
    private static final int SAVE_CSV_REQUEST=221;
    private static final int MODE_NETWORKS=0, MODE_CHANNELS=1, MODE_SIGNAL=2, MODE_HEALTH=3;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<Ap> aps=new ArrayList<>();
    private final LinkedHashMap<String,ArrayDeque<Point>> history=new LinkedHashMap<>();
    private final ArrayList<String> targetKeys=new ArrayList<>();
    private WifiManager wifi;
    private TextView status, metrics, help;
    private LinearLayout content;
    private Spinner modeSpinner, bandSpinner, targetSpinner;
    private ChartView graph;
    private Button autoButton;
    private boolean registered=false,auto=false;
    private int mode=MODE_NETWORKS, selectedBand=0;
    private String selectedBssid="AUTO";
    private long lastFresh=0;
    private String scanNote="No scan yet";

    private static class Ap {
        String ssid,bssid,capabilities;
        int dbm,frequency,width,standard;
        long scanTimestampMicro;
        Ap(ScanResult s) {
            ssid=s.SSID==null?"":s.SSID; bssid=s.BSSID==null?"":s.BSSID;
            capabilities=s.capabilities==null?"":s.capabilities;
            dbm=s.level;frequency=s.frequency;width=s.channelWidth;
            standard=Build.VERSION.SDK_INT>=30?s.getWifiStandard():0;
            scanTimestampMicro=s.timestamp;
        }
        int band() {return frequency>=5925?3:frequency>=4900?2:1;}
        int channel() {
            if(frequency==2484)return 14;
            if(frequency>=2412&&frequency<=2472)return (frequency-2407)/5;
            if(frequency==5935)return 2;
            if(frequency>=5955&&frequency<=7115)return (frequency-5950)/5;
            if(frequency>=5000&&frequency<5925)return (frequency-5000)/5;
            return 0;
        }
        String name(){return ssid.isEmpty()?"<hidden>":ssid;}
        String security(){
            String c=capabilities.toUpperCase(Locale.US);
            if(c.contains("SAE"))return "WPA3/SAE";
            if(c.contains("OWE"))return "Enhanced Open";
            if(c.contains("WEP"))return "WEP (legacy/insecure)";
            if(c.contains("EAP"))return "Enterprise";
            if(c.contains("PSK"))return "WPA/WPA2 PSK";
            if(c.contains("RSN"))return "WPA2/WPA3";
            if(c.contains("WPA"))return "WPA";
            return "Open";
        }
        String standardText(){
            switch(standard){
                case 4: return "Wi-Fi 4 (802.11n)";
                case 5: return "Wi-Fi 5 (802.11ac)";
                case 6: return "Wi-Fi 6/6E (802.11ax)";
                case 8: return "Wi-Fi 7 (802.11be)";
                case 1: return "Legacy Wi-Fi";
                default: return "Not reported";
            }
        }
        String widthText(){
            switch(width){case 0:return "20";case 1:return "40";case 2:return "80";
                case 3:return "160";case 4:return "80+80";case 5:return "320";
                default:return "?";}
        }
    }
    private static class Point {
        final long time;final int dbm;
        Point(long t,int r){time=t;dbm=r;}
    }

    private final BroadcastReceiver scanReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            boolean fresh=i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED,false);
            if(fresh)lastFresh=System.currentTimeMillis();
            readResults(fresh?"New Android Wi-Fi scan":"Scan not updated; results may be cached",fresh);
        }
    };
    private final Runnable autoScan=new Runnable(){
        @Override public void run(){
            if(!auto)return;
            scan();
            handler.postDelayed(this,40000L); // Avoid claiming a continuous Sidekick-style RF sweep.
        }
    };

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView label(String value,int size){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);
        t.setPadding(dp(9),dp(5),dp(9),dp(5));t.setTextColor(Color.rgb(25,40,55));
        return t;
    }
    private Button button(String name,View.OnClickListener listener){
        Button b=new Button(this);b.setAllCaps(false);b.setTextSize(12);b.setText(name);b.setOnClickListener(listener);
        return b;
    }
    private Spinner choices(String[] options){
        Spinner s=new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));
        return s;
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        wifi=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);setContentView(root);
        TextView title=label("Wi-Fi Analyzer  •  v0.2",20);
        title.setTextColor(Color.rgb(18,73,122));root.addView(title);
        root.addView(label("Source: Android phone Wi-Fi — not Sidekick RF",11));
        status=label("Tap Scan to discover nearby access points.",12);root.addView(status);

        LinearLayout buttons=new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Survey map",v->finish()),new LinearLayout.LayoutParams(0,dp(48),1));
        buttons.addView(button("Scan",v->scan()),new LinearLayout.LayoutParams(0,dp(48),1));
        autoButton=button("Auto: off",v->toggleAuto());
        buttons.addView(autoButton,new LinearLayout.LayoutParams(0,dp(48),1));
        buttons.addView(button("Save CSV",v->saveCsv()),new LinearLayout.LayoutParams(0,dp(48),1));
        root.addView(buttons);

        LinearLayout selectors=new LinearLayout(this);
        selectors.setOrientation(LinearLayout.HORIZONTAL);
        modeSpinner=choices(new String[]{"Access points","Channels","Signal history","Health checks"});
        bandSpinner=choices(new String[]{"All bands","2.4 GHz","5 GHz","6 GHz"});
        selectors.addView(modeSpinner,new LinearLayout.LayoutParams(0,dp(48),1.3f));
        selectors.addView(bandSpinner,new LinearLayout.LayoutParams(0,dp(48),1));
        root.addView(selectors);

        targetSpinner=choices(new String[]{"Strongest AP"});
        root.addView(targetSpinner,new LinearLayout.LayoutParams(-1,dp(45)));
        metrics=label("No Wi-Fi data yet.",13);root.addView(metrics);
        graph=new ChartView(this);
        root.addView(graph,new LinearLayout.LayoutParams(-1,dp(220)));
        help=label("",12);root.addView(help);

        ScrollView scroll=new ScrollView(this);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(AdapterView<?> p){}
            @Override public void onItemSelected(AdapterView<?> p,View v,int i,long id){mode=i;render();}
        });
        bandSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(AdapterView<?> p){}
            @Override public void onItemSelected(AdapterView<?> p,View v,int i,long id){selectedBand=i;updateTargets();render();}
        });
        targetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(AdapterView<?> p){}
            @Override public void onItemSelected(AdapterView<?> p,View v,int i,long id){
                if(i<targetKeys.size())selectedBssid=targetKeys.get(i);
                render();
            }
        });
        render();
    }
    @Override protected void onResume(){
        super.onResume();
        if(!registered){
            IntentFilter filter=new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if(Build.VERSION.SDK_INT>=33)registerReceiver(scanReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(scanReceiver,filter);
            registered=true;
        }
        if(hasPermission())readResults("Existing Wi-Fi results (may be cached)",false);
        if(auto){handler.removeCallbacks(autoScan);handler.postDelayed(autoScan,40000L);}
    }
    @Override protected void onPause(){
        handler.removeCallbacks(autoScan);
        if(registered){unregisterReceiver(scanReceiver);registered=false;}
        super.onPause();
    }
    private boolean hasPermission(){
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private void toggleAuto(){
        auto=!auto;autoButton.setText(auto?"Auto: on":"Auto: off");
        handler.removeCallbacks(autoScan);
        if(auto){scan();handler.postDelayed(autoScan,40000L);}
        else {scanNote="Auto scan stopped";render();}
    }
    private void scan(){
        if(!hasPermission()){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},PERMISSION_REQUEST);return;
        }
        LocationManager lm=(LocationManager)getSystemService(Context.LOCATION_SERVICE);
        if(lm!=null&&Build.VERSION.SDK_INT>=28&&!lm.isLocationEnabled()){
            new AlertDialog.Builder(this).setTitle("Location is off")
                .setMessage("Android requires Location enabled to discover nearby access points.")
                .setPositiveButton("Location Settings",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton("Cancel",null).show();
            return;
        }
        if(wifi==null||!wifi.isWifiEnabled()){
            scanNote="Enable Wi-Fi on this Android device";render();return;
        }
        try {
            if(wifi.startScan()){scanNote="Wi-Fi scan requested…";render();}
            else readResults("Scan throttled/declined — showing cached data",false);
        } catch(SecurityException e){
            scanNote="Permission denied for Android Wi-Fi scan";render();
        }
    }
    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] grants){
        super.onRequestPermissionsResult(code,perms,grants);
        if(code==PERMISSION_REQUEST){
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)scan();
            else {scanNote="Precise location permission is required";render();}
        }
    }
    private void readResults(String message,boolean fresh){
        aps.clear();
        try {
            if(hasPermission()&&wifi!=null)for(ScanResult s:wifi.getScanResults()){
                if(s.BSSID!=null&&!s.BSSID.isEmpty())aps.add(new Ap(s));
            }
            aps.sort((a,b)->Integer.compare(b.dbm,a.dbm));
            scanNote=message;
            if(fresh){
                long now=System.currentTimeMillis();
                for(Ap a:aps){
                    ArrayDeque<Point> points=history.get(a.bssid);
                    if(points==null){points=new ArrayDeque<>();history.put(a.bssid,points);}
                    points.addLast(new Point(now,a.dbm));
                    while(points.size()>60)points.removeFirst();
                }
            }
        }catch(SecurityException e){scanNote="Wi-Fi results unavailable (permission/location)";}
        updateTargets();
        render();
    }
    private List<Ap> filtered(){
        ArrayList<Ap> f=new ArrayList<>();
        for(Ap a:aps)if(selectedBand==0||selectedBand==a.band())f.add(a);
        return f;
    }
    private void updateTargets(){
        String keep=selectedBssid;
        ArrayList<String> names=new ArrayList<>();targetKeys.clear();
        names.add("Strongest visible AP");targetKeys.add("AUTO");
        for(Ap a:filtered()){
            names.add(a.name()+"  •  "+a.bssid);targetKeys.add(a.bssid);
        }
        targetSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
        int n=targetKeys.indexOf(keep);
        if(n<0)n=0;
        selectedBssid=targetKeys.get(n);targetSpinner.setSelection(n);
    }
    private String connectedInfo(){
        if(wifi==null)return "";
        try{
            WifiInfo info=wifi.getConnectionInfo();
            if(info==null)return "";
            int ip=info.getIpAddress();
            String addr=String.format(Locale.US,"%d.%d.%d.%d",
                ip&255,(ip>>>8)&255,(ip>>>16)&255,(ip>>>24)&255);
            String ssid=info.getSSID();
            if(ssid==null||ssid.equals("<unknown ssid>"))ssid="unknown";
            return "Connected: "+ssid+"  |  IP: "+addr+"  |  "+info.getRssi()+" dBm";
        }catch(Exception e){return "";}
    }
    private String stamp(){
        if(lastFresh==0)return "No confirmed fresh scan";
        return "Fresh scan at "+new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date(lastFresh));
    }
    private void render(){
        if(content==null)return;
        List<Ap> selected=filtered();
        int p24=0,p5=0,p6=0;
        for(Ap a:selected){if(a.band()==1)p24++;else if(a.band()==2)p5++;else p6++;}
        status.setText(scanNote+"  |  "+stamp());
        metrics.setText(selected.size()+" visible APs  •  2.4: "+p24+"  5: "+p5+"  6: "+p6+"\n"+connectedInfo());
        boolean showGraph=(mode==MODE_CHANNELS||mode==MODE_SIGNAL);
        graph.setVisibility(showGraph?View.VISIBLE:View.GONE);
        targetSpinner.setVisibility(mode==MODE_SIGNAL?View.VISIBLE:View.GONE);
        content.removeAllViews();
        graph.invalidate();
        if(mode==MODE_NETWORKS){
            help.setText("Touch an AP for details. Observations come from the phone's latest scan.");
            if(selected.isEmpty())content.addView(label("No APs in this band. Tap Scan, check location permission and device capabilities.",13));
            for(Ap a:selected)addAccessPoint(a);
        } else if(mode==MODE_CHANNELS){
            help.setText("AP counts by primary channel — NOT airtime utilization or spectrum interference.");
            content.addView(label("Each bar counts beaconing access points seen on that channel. A larger bar does not necessarily mean high traffic.",13));
            if(selectedBand==0)content.addView(label("Select an individual frequency band above for clearer channel comparisons.",13));
            if(selected.isEmpty())content.addView(label("No APs in the selected band.",13));
        } else if(mode==MODE_SIGNAL){
            help.setText("History records newly completed Android scans only; Android may throttle scans.");
            Ap t=selectedTarget();
            if(t==null)content.addView(label("Choose an AP and tap Scan to collect RSSI samples.",13));
            else {
                ArrayDeque<Point> points=history.get(t.bssid);
                int n=points==null?0:points.size();
                content.addView(label(t.name()+"  |  "+t.bssid+"  |  "+t.dbm+" dBm  |  "+n+" fresh samples",14));
                content.addView(label("Try walking toward and away from the AP while Auto is on. Gaps are not filled with invented readings.",12));
            }
        } else {
            help.setText("Heuristic diagnostics only. No spectrum, SNR, packet loss, or airtime measurements.");
            renderHealth(selected);
        }
    }
    private Ap selectedTarget(){
        List<Ap> list=filtered();
        if(list.isEmpty())return null;
        if(selectedBssid.equals("AUTO"))return list.get(0);
        for(Ap a:list)if(selectedBssid.equals(a.bssid))return a;
        return null;
    }
    private void addAccessPoint(Ap a){
        String band=a.band()==1?"2.4":a.band()==2?"5":"6";
        String subtitle=a.bssid+"  •  ch "+a.channel()+" ("+band+" GHz)  •  "+a.dbm+" dBm";
        TextView row=label(a.name()+"  |  "+a.security()+"\n"+subtitle,14);
        row.setPadding(dp(12),dp(11),dp(10),dp(11));
        row.setBackgroundColor(aps.indexOf(a)%2==0?Color.rgb(240,247,252):Color.WHITE);
        row.setOnClickListener(v->showDetails(a));
        content.addView(row);
    }
    private void showDetails(Ap a){
        String s="SSID: "+a.name()+"\nBSSID: "+a.bssid+
           "\nSignal: "+a.dbm+" dBm\nFrequency: "+a.frequency+" MHz"+
           "\nChannel: "+a.channel()+"\nChannel width: "+a.widthText()+" MHz"+
           "\nStandard: "+a.standardText()+"\nSecurity: "+a.security()+
           "\nCapabilities: "+a.capabilities+"\n\nSource: Android phone Wi-Fi scan.";
        new AlertDialog.Builder(this).setTitle("Access Point").setMessage(s)
            .setPositiveButton("Close",null).show();
    }
    private void addHealth(String title,String explanation){
        TextView t=label(title+"\n"+explanation,14);
        t.setPadding(dp(10),dp(10),dp(10),dp(10));
        content.addView(t);
    }
    private void renderHealth(List<Ap> list){
        if(list.isEmpty()){addHealth("No measurements","Scan Wi-Fi with Location enabled.");return;}
        int weak=0,insecure=0,wide24=0;
        LinkedHashMap<Integer,Integer> ch24=new LinkedHashMap<>();
        for(Ap a:list){
            if(a.dbm<=-80)weak++;
            if(a.security().equals("Open")||a.security().startsWith("WEP"))insecure++;
            if(a.band()==1){
                ch24.put(a.channel(),ch24.getOrDefault(a.channel(),0)+1);
                if(a.width>0)wide24++;
            }
        }
        addHealth("Signal review",weak+" APs at -80 dBm or weaker (these APs may be far away, not necessarily faulty).");
        addHealth("Security review",insecure+" open or legacy-WEP APs detected. Confirm whether expected (e.g., guest SSIDs).");
        if(selectedBand==0||selectedBand==1){
            int best=1;double bestScore=Double.MAX_VALUE;
            for(int candidate:new int[]{1,6,11}){
                double score=0;
                for(Ap a:aps)if(a.band()==1){
                    int spacing=Math.abs(a.channel()-candidate);
                    if(spacing<5)score+=(5-spacing)*(Math.max(0,a.dbm+100)/40.0);
                }
                if(score<bestScore){bestScore=score;best=candidate;}
            }
            addHealth("2.4 GHz channel estimate","Least-overlapping of channels 1/6/11 by visible AP strength: "+best+
                ". This is not a real airtime/traffic assessment; verify regulatory channels and use a spectrum tool to check non-Wi-Fi interference.");
            addHealth("2.4 GHz configuration",wide24+" observed 2.4 GHz APs advertise wider-than-20 MHz operation (may increase adjacent-channel overlap).");
        }
        addHealth("What requires the Sidekick 2","Spectrum sweeps, noise floor, non-Wi-Fi interferers, RF utilization, calibrated surveys, and packet capture need supported external hardware/software interfaces.");
    }
    private void saveCsv(){
        if(aps.isEmpty()){
            Toast.makeText(this,"Run a scan before saving.",Toast.LENGTH_SHORT).show();return;
        }
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE,"sidekick-analyzer-scan.csv");
        startActivityForResult(i,SAVE_CSV_REQUEST);
    }
    private String csv(String v){return "\""+(v==null?"":v).replace("\"","\"\"")+"\"";}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=SAVE_CSV_REQUEST||result!=RESULT_OK||data==null||data.getData()==null)return;
        try{
            StringBuilder b=new StringBuilder("scan_time,ssid,bssid,rssi_dbm,frequency_mhz,channel,band,channel_width_mhz,security,wifi_standard,capabilities,source\n");
            String time=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date());
            for(Ap a:filtered()){
                b.append(csv(time)).append(',').append(csv(a.ssid)).append(',').append(csv(a.bssid)).append(',')
                 .append(a.dbm).append(',').append(a.frequency).append(',').append(a.channel()).append(',')
                 .append(csv(a.band()==1?"2.4 GHz":a.band()==2?"5 GHz":"6 GHz")).append(',')
                 .append(csv(a.widthText())).append(',').append(csv(a.security())).append(',')
                 .append(csv(a.standardText())).append(',').append(csv(a.capabilities)).append(',')
                 .append(csv("Android phone Wi-Fi")).append('\n');
            }
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
                if(out==null)throw new Exception("Cannot open output");
                out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this,"Wi-Fi scan CSV saved",Toast.LENGTH_LONG).show();
        }catch(Exception e){scanNote="CSV save failed: "+e.getMessage();render();}
    }

    private class ChartView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        ChartView(Context c){super(c);}
        private void text(Canvas c,String s,float x,float y,int color,int sp){
            p.setColor(color);p.setStyle(Paint.Style.FILL);p.setTextSize(dp(sp));
            c.drawText(s,x,y,p);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            int w=getWidth(),h=getHeight();
            if(w==0||h==0)return;
            c.drawColor(Color.rgb(244,248,252));
            if(mode==MODE_CHANNELS)channels(c,w,h);
            else if(mode==MODE_SIGNAL)signal(c,w,h);
        }
        private void channels(Canvas c,int w,int h){
            TreeMap<Integer,Integer> counts=new TreeMap<>();
            for(Ap a:filtered())counts.put(a.frequency,counts.getOrDefault(a.frequency,0)+1);
            int left=dp(40),top=dp(38),right=w-dp(14),bottom=h-dp(40);
            text(c,"Nearby APs by primary channel",dp(10),dp(22),Color.DKGRAY,14);
            if(counts.isEmpty()){text(c,"No APs detected",dp(25),h/2,Color.GRAY,13);return;}
            int max=1;for(int n:counts.values())max=Math.max(max,n);
            p.setStrokeWidth(dp(1));p.setColor(Color.LTGRAY);
            for(int k=0;k<=4;k++){
                float y=bottom-(bottom-top)*k/4f;
                c.drawLine(left,y,right,y,p);
                text(c,String.valueOf(Math.round(max*k/4f)),dp(3),y+dp(3),Color.GRAY,10);
            }
            ArrayList<Integer> frequencies=new ArrayList<>(counts.keySet());
            float step=(right-left)/(float)frequencies.size();
            int every=Math.max(1,(int)Math.ceil(frequencies.size()/13.0));
            for(int i=0;i<frequencies.size();i++){
                int frequency=frequencies.get(i);
                int count=counts.get(frequency);
                float x=left+i*step+step*0.13f;
                float barWidth=step*0.74f;
                float y=bottom-(bottom-top)*count/max;
                p.setColor(frequency<2500?Color.rgb(36,139,193):frequency<5925?Color.rgb(55,166,121):Color.rgb(132,91,203));
                c.drawRoundRect(new RectF(x,y,x+barWidth,bottom),dp(2),dp(2),p);
                if(i%every==0){
                    int channel=frequency==2484?14:frequency<2500?(frequency-2407)/5:
                         frequency==5935?2:frequency>=5955?(frequency-5950)/5:(frequency-5000)/5;
                    text(c,String.valueOf(channel),x,bottom+dp(15),Color.DKGRAY,10);
                }
            }
            text(c,"Primary channel (2.4 / 5 / 6 GHz)",left,bottom+dp(32),Color.GRAY,10);
        }
        private void signal(Canvas c,int w,int h){
            Ap selected=selectedTarget();
            int left=dp(44),right=w-dp(15),top=dp(35),bottom=h-dp(36);
            text(c,"Signal history (fresh Android scans)",dp(10),dp(20),Color.DKGRAY,14);
            p.setColor(Color.LTGRAY);p.setStrokeWidth(dp(1));
            for(int dbm=-90;dbm<=-30;dbm+=10){
                float y=bottom-(dbm+100)/80f*(bottom-top);
                c.drawLine(left,y,right,y,p);
                text(c,String.valueOf(dbm),dp(4),y+dp(4),Color.GRAY,10);
            }
            if(selected==null){text(c,"Scan and select an AP",dp(30),h/2,Color.GRAY,13);return;}
            ArrayDeque<Point> deque=history.get(selected.bssid);
            if(deque==null||deque.size()<1){
                text(c,"No fresh samples for this AP yet",dp(25),h/2,Color.GRAY,13);return;
            }
            List<Point> points=new ArrayList<>(deque);
            p.setColor(Color.rgb(20,115,192));p.setStrokeWidth(dp(3));p.setStyle(Paint.Style.STROKE);
            float previousX=0,previousY=0;
            for(int i=0;i<points.size();i++){
                float x=left+(right-left)*(points.size()==1?0.5f:i/(float)(points.size()-1));
                int clamped=Math.max(-100,Math.min(-20,points.get(i).dbm));
                float y=bottom-(clamped+100)/80f*(bottom-top);
                if(i>0)c.drawLine(previousX,previousY,x,y,p);
                previousX=x;previousY=y;
            }
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(previousX,previousY,dp(4),p);
            text(c,points.size()+" sample(s)   Latest: "+points.get(points.size()-1).dbm+" dBm",
                 left,bottom+dp(26),Color.DKGRAY,11);
        }
    }
}
