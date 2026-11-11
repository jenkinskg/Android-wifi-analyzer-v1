package com.jenkinskg.sidekicksurvey;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Imports TXT/CSV and allows BSSID-to-wired AP MAC manual matching. Matching only
 * exact MACs or explicitly linked aliases avoids misleading Aruba AP identities.
 */
public class InventoryActivity extends Activity {
    private static final int PICK_FILE=701;
    private final ArrayList<InventoryStore.Entry> entries=new ArrayList<>();
    private TextView summary;
    private ListView list;
    private WifiManager wifi;
    private int dp(int i){return (int)(i*getResources().getDisplayMetrics().density+0.5f);}
    private Button button(String s,android.view.View.OnClickListener callback){
        Button b=new Button(this);b.setText(s);b.setTextSize(12);b.setAllCaps(false);b.setOnClickListener(callback);return b;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        setContentView(layout);
        TextView title=new TextView(this);
        title.setText("Aruba AP Inventory  •  v0.7");title.setTextSize(21);title.setPadding(dp(12),dp(10),dp(12),dp(8));
        layout.addView(title);
        TextView info=new TextView(this);
        info.setText("Import AP name, wired MAC and IP from a TXT/CSV. Tap an AP to link an observed Wi-Fi BSSID. No guessed MAC-to-BSSID matches.");
        info.setPadding(dp(10),dp(7),dp(10),dp(7));layout.addView(info);
        LinearLayout buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Import TXT/CSV",v->pick()),new LinearLayout.LayoutParams(0,dp(50),1));
        buttons.addView(button("Back to map",v->finish()),new LinearLayout.LayoutParams(0,dp(50),1));
        layout.addView(buttons);
        summary=new TextView(this);summary.setPadding(dp(12),dp(9),dp(12),dp(9));layout.addView(summary);
        list=new ListView(this);layout.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((parent,view,pos,id)->showActions(entries.get(pos)));
        entries.addAll(InventoryStore.load(this));redraw();
    }
    private void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/plain","text/csv","application/csv","application/octet-stream"});
        startActivityForResult(i,PICK_FILE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_FILE||result!=RESULT_OK||data==null||data.getData()==null)return;
        try(InputStream in=getContentResolver().openInputStream(data.getData())){
            ArrayList<InventoryStore.Entry> imported=InventoryStore.parse(InventoryStore.readText(in,2_000_000));
            if(imported.isEmpty()){
                new AlertDialog.Builder(this).setTitle("No MAC addresses found")
                    .setMessage("Expected name, IP and MAC in CSV or TXT, such as:\nAP-102,10.10.1.20,AA:BB:CC:DD:EE:FF")
                    .setPositiveButton("OK",null).show();
                return;
            }
            new AlertDialog.Builder(this).setTitle("Import "+imported.size()+" APs")
                .setMessage("Merge with existing inventory? Existing manually linked BSSIDs are kept for matching MACs.")
                .setPositiveButton("Merge",(d,w)->merge(imported))
                .setNeutralButton("Replace",(d,w)->{
                    new AlertDialog.Builder(this).setTitle("Replace AP inventory?")
                        .setMessage("Existing inventory and its manual BSSID links will be replaced. Your survey map and AP notes are not affected.")
                        .setPositiveButton("Replace",(x,y)->{entries.clear();entries.addAll(imported);saveAndRefresh();})
                        .setNegativeButton("Cancel",null).show();
                }).setNegativeButton("Cancel",null).show();
        }catch(Exception ex){summary.setText("Import failed: "+ex.getMessage());}
    }
    private void merge(List<InventoryStore.Entry> imported){
        LinkedHashMap<String,InventoryStore.Entry> matched=new LinkedHashMap<>();
        for(InventoryStore.Entry old:entries)matched.put(old.mac,old);
        for(InventoryStore.Entry incoming:imported){
            InventoryStore.Entry existing=matched.get(incoming.mac);
            if(existing==null)matched.put(incoming.mac,incoming);
            else {
                if(!incoming.ip.isEmpty())existing.ip=incoming.ip;
                if(!incoming.name.isEmpty())existing.name=incoming.name;
                for(String b:incoming.bssids)if(!existing.bssids.contains(b))existing.bssids.add(b);
            }
        }
        entries.clear();entries.addAll(matched.values());saveAndRefresh();
    }
    private void redraw(){
        String[] rows=new String[entries.size()];
        int links=0;
        for(int i=0;i<entries.size();i++){
            InventoryStore.Entry e=entries.get(i);
            links+=e.bssids.size();
            rows[i]=e.displayLine();
        }
        summary.setText(entries.size()+" imported APs  •  "+links+" BSSID links\nTap any AP to link a scanned BSSID or manually enter one.");
        list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows));
    }
    private void saveAndRefresh(){
        try{InventoryStore.save(this,entries);redraw();}
        catch(Exception e){summary.setText("Inventory save failed: "+e.getMessage());}
    }
    private void showActions(InventoryStore.Entry ap){
        final String[] choices={"Link detected BSSID","Enter BSSID manually","Remove BSSID link"};
        new AlertDialog.Builder(this).setTitle(ap.displayName()+"\nIP "+ap.ip+"  •  "+ap.mac)
            .setItems(choices,(d,i)->{
                if(i==0)linkScanned(ap);
                else if(i==1)linkManual(ap);
                else unlink(ap);
            }).setNegativeButton("Cancel",null).show();
    }
    private void linkScanned(InventoryStore.Entry ap){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            new AlertDialog.Builder(this).setTitle("Location permission required")
                .setMessage("The Android Wi-Fi scan list needs precise location permission. You can enter the BSSID manually instead.")
                .setPositiveButton("Manual link",(d,w)->linkManual(ap)).setNegativeButton("Cancel",null).show();
            return;
        }
        ArrayList<String> bssids=new ArrayList<>(),labels=new ArrayList<>();
        try {
            for(ScanResult r:wifi.getScanResults()){
                String normalized=InventoryStore.normalizeMac(r.BSSID);
                if(!normalized.isEmpty()&&!bssids.contains(normalized)){
                    bssids.add(normalized);
                    labels.add((r.SSID==null||r.SSID.isEmpty()?"<hidden>":r.SSID)+"  "+r.level+" dBm\n"+normalized);
                }
            }
        }catch(Exception ignored){}
        if(bssids.isEmpty()){
            Toast.makeText(this,"No recent Wi-Fi scan. Use Manual BSSID.",Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Link observed BSSID to "+ap.displayName())
            .setItems(labels.toArray(new String[0]),(d,index)->{
                InventoryStore.link(entries,ap,bssids.get(index));saveAndRefresh();
                Toast.makeText(this,"BSSID linked. Mapping will show AP name/IP.",Toast.LENGTH_LONG).show();
            }).setNegativeButton("Cancel",null).show();
    }
    private void linkManual(InventoryStore.Entry ap){
        EditText input=new EditText(this);
        input.setSingleLine(true);input.setHint("AA:BB:CC:DD:EE:FF");
        input.setPadding(dp(15),dp(12),dp(15),dp(12));
        new AlertDialog.Builder(this).setTitle("BSSID for "+ap.displayName())
            .setView(input).setPositiveButton("Link",(d,w)->{
                String id=InventoryStore.normalizeMac(input.getText().toString());
                if(id.isEmpty()){Toast.makeText(this,"Invalid 12-digit MAC/BSSID",Toast.LENGTH_LONG).show();return;}
                InventoryStore.link(entries,ap,id);saveAndRefresh();
            }).setNegativeButton("Cancel",null).show();
    }
    private void unlink(InventoryStore.Entry ap){
        if(ap.bssids.isEmpty()){
            Toast.makeText(this,"No manual BSSID links on this AP",Toast.LENGTH_SHORT).show();return;
        }
        ArrayList<String> all=new ArrayList<>(ap.bssids);
        new AlertDialog.Builder(this).setTitle("Unlink BSSID")
            .setItems(all.toArray(new String[0]),(d,i)->{
                ap.bssids.remove(all.get(i));saveAndRefresh();
            }).setNegativeButton("Cancel",null).show();
    }
}
