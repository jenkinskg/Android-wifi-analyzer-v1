package com.keith.ethernettools;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.*;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int GREEN=Color.rgb(79,255,133), BG=Color.rgb(12,21,37), PANEL=Color.rgb(24,39,60), CYAN=Color.rgb(70,213,255);
    private final ExecutorService pool=Executors.newCachedThreadPool();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final StringBuilder report=new StringBuilder();
    private TextView reportView;private LinearLayout content,toolbar;private ScrollView mainScroll;
    private String tab="Wi-Fi"; private volatile boolean monitoring=false;private NortelTelnet nortel;
    private EditText pingAddress,nortelHost,nortelPort,nortelUser,nortelPass,nortelCommand;
    private TextView wifiInfo; private int pingCount=0,failedCount=0;private boolean previousUp=false;
    // Credentials survive navigation within this app session but are not saved to disk.
    private String ciscoHost="",ciscoUsername="",ciscoPassword="",ciscoEnable="",ciscoPort="";
    private String fiberResult="Run Fiber Link Health to view transceiver readings and stack warnings.";
    private TextView fiberSummaryView;
    private EditText fiberHostEdit,fiberUserEdit,fiberPassEdit,fiberEnableEdit,fiberPortEdit;
    private EditText ciscoHostEdit,ciscoUserEdit,ciscoPassEdit,ciscoEnableEdit,ciscoPortEdit;
    private final SimpleDateFormat timestamp=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);
    @Override public void onCreate(Bundle b){super.onCreate(b);showScreen();if(android.os.Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},11);}
    @Override public void onDestroy(){monitoring=false;if(nortel!=null)nortel.close();pool.shutdownNow();super.onDestroy();}
    private TextView text(String s,int size,int col){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(col);t.setPadding(12,12,12,12);return t;}
    private EditText entry(String value,String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setText(value);e.setHint(hint);e.setHintTextColor(0xff9caec0);e.setTextColor(Color.WHITE);e.setTextSize(15);e.setSelectAllOnFocus(true);return e;}
    private Button btn(String text,Runnable click){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff24577f));b.setOnClickListener(v->click.run());return b;}
    private void addLabel(String title){content.addView(text(title,17,CYAN));}
    private void addEntry(String title,EditText e){content.addView(text(title,13,0xffc8d8e9));content.addView(e);}
    private void addButton(String caption,Runnable runnable){content.addView(btn(caption,runnable));}
    private void showScreen(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        TextView head=text("⚡ KEITH'S ETHERNET TOOLS  •  ANDROID 3.5.3 BETA",19,GREEN);head.setTypeface(Typeface.DEFAULT,Typeface.BOLD);root.addView(head);
        HorizontalScrollView nav=new HorizontalScrollView(this);LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        for(String name:new String[]{"Wi-Fi","Ping Bounce","Traceroute","Port & VLAN","Switch Utils","Nortel Telnet","Reports"}){
            Button b=btn(name,()->{tab=name;showScreen();});if(tab.equals(name))b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff248858));row.addView(b);
        }
        nav.addView(row);root.addView(nav);
        mainScroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(12,8,12,10);mainScroll.addView(content);root.addView(mainScroll,new LinearLayout.LayoutParams(-1,0,1));
        addTab();setContentView(root);
    }
    private void append(String s){String line="["+timestamp.format(new Date())+"] "+s; synchronized(report){report.append(line).append('\n');}handler.post(()->{if(reportView!=null){reportView.setText(getReport());}});}
    private String getReport(){synchronized(report){return report.toString();}}
    private void addReportPanel(){addLabel("SESSION REPORT (select, copy, or share)");reportView=text(getReport(),13,GREEN);reportView.setTextIsSelectable(true);reportView.setTypeface(Typeface.MONOSPACE);reportView.setBackgroundColor(PANEL);reportView.setMinHeight(300);content.addView(reportView);}
    private interface CheckedRunnable { void run() throws Exception; }
    private void async(CheckedRunnable r){pool.execute(()->{try{r.run();}catch(Exception e){append("ERROR "+e.getClass().getSimpleName()+": "+e.getMessage());}});}
    private void addTab(){switch(tab){case "Wi-Fi":wifiTab();break;case "Ping Bounce":pingTab();break;case "Traceroute":traceTab();break;case "Port & VLAN":portVlanTab();break;case "Switch Utils":switchTab();break;case "Nortel Telnet":nortelTab();break;case "Reports":reportsTab();break;}}
    private Network active(){ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);return cm==null?null:cm.getActiveNetwork();}
    private LinkProperties link(){ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);Network n=active();return cm==null||n==null?null:cm.getLinkProperties(n);}
    private String activeNetwork(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);Network n=active();if(cm==null||n==null)return "No active network";
        NetworkCapabilities caps=cm.getNetworkCapabilities(n);LinkProperties lp=cm.getLinkProperties(n);StringBuilder b=new StringBuilder();
        if(caps!=null){b.append(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"WIFI":caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"ETHERNET":caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"CELLULAR":"OTHER");}
        if(lp!=null){b.append("\nInterface: ").append(lp.getInterfaceName());for(LinkAddress la:lp.getLinkAddresses())if(la.getAddress() instanceof Inet4Address)b.append("\nIP: ").append(la.getAddress().getHostAddress()).append("/").append(la.getPrefixLength());for(RouteInfo r:lp.getRoutes())if(r.isDefaultRoute())b.append("\nGateway: ").append(r.getGateway());}
        try{WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);WifiInfo wi=wm==null?null:wm.getConnectionInfo();if(wi!=null && caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){
            b.append("\nSSID: ").append(wi.getSSID()).append("\nBSSID (AP MAC): ").append(wi.getBSSID()).append("\nSignal: ").append(wi.getRssi()).append(" dBm\nFrequency: ").append(wi.getFrequency()).append(" MHz\nLink speed: ").append(wi.getLinkSpeed()).append(" Mbps");}}
        catch(SecurityException se){b.append("\nEnable Location permission and device Location to display SSID/BSSID");}
        return b.toString();
    }
    private void wifiTab(){addLabel("ACTIVE CONNECTION / ACCESS POINT");wifiInfo=text(activeNetwork(),16,GREEN);wifiInfo.setBackgroundColor(PANEL);content.addView(wifiInfo);addButton("Refresh Connection",()->{wifiInfo.setText(activeNetwork());append("Connection refreshed: "+activeNetwork().replace('\n',' '));});
        addButton("Start Wi-Fi Roaming Log (5 sec)",()->{if(monitoring)return;monitoring=true;append("Roaming monitor started");async(()->{String last="";while(monitoring&&!Thread.currentThread().isInterrupted()){
            String now=activeNetwork();if(!now.equals(last)){append("Connection change: "+now.replace('\n',' '));last=now;handler.post(()->{if(wifiInfo!=null)wifiInfo.setText(now);});}Thread.sleep(5000);
        }});});addButton("Stop Wi-Fi Roaming Log",()->{monitoring=false;append("Roaming monitor stopped");});addReportPanel();}
    private String subnetRange(){LinkProperties lp=link();if(lp==null)return "";for(LinkAddress addr:lp.getLinkAddresses()){
        if(addr.getAddress() instanceof Inet4Address){byte[] a=addr.getAddress().getAddress();int p=addr.getPrefixLength();long v=0;for(byte by:a)v=(v<<8)|(by&255);long mask=p==0?0:(0xffffffffL<<(32-p))&0xffffffffL;long network=v&mask, broadcast=network|(~mask&0xffffffffL);
            return toIp(network+1)+" - "+toIp(broadcast-1)+" (max 256 hosts per batch)";}}
        return "No IPv4 address";}
    private static String toIp(long x){return ((x>>24)&255)+"."+((x>>16)&255)+"."+((x>>8)&255)+"."+(x&255);}
    private void pingTab(){addLabel("PING BOUNCE • DNS & FAILED COUNTERS");pingAddress=entry("8.8.8.8","IP address or hostname");addEntry("Target",pingAddress);
        addButton("Use Current Subnet",()->{String subnet=subnetRange();append("Current network range: "+subnet);new AlertDialog.Builder(this).setTitle("Current subnet").setMessage(subnet+"\nThis beta currently monitors one host; multi-host scanning is coming next.").setPositiveButton("OK",null).show();});
        addButton("Ping Once",()->{String host=pingAddress.getText().toString().trim();async(()->{String ip=NetTools.resolve(host);String status=NetTools.ping(ip);pingCount++;if(!status.startsWith("UP"))failedCount++;append(ip+" ["+NetTools.reverse(ip)+"] "+status+" total="+pingCount+" failures="+failedCount);});});
        addButton("Start Ping Bounce (every 30 sec)",()->{if(monitoring)return;monitoring=true;String host=pingAddress.getText().toString().trim();async(()->{String ip=NetTools.resolve(host),dns=NetTools.reverse(ip);while(monitoring&&!Thread.currentThread().isInterrupted()){
            String status=NetTools.ping(ip);boolean up=status.startsWith("UP");pingCount++;if(!up)failedCount++;if(pingCount==1||up!=previousUp||!up)append(ip+" ["+dns+"] "+status+" pings="+pingCount+" failures="+failedCount);previousUp=up;Thread.sleep(30000);
        }});});addButton("Stop Monitoring",()->monitoring=false);addReportPanel();}
    private void traceTab(){addLabel("TRACEROUTE • HOP LATENCY");EditText host=entry("8.8.8.8","Target IP or hostname");addEntry("Destination",host);addButton("Trace (up to 16 hops)",()->async(()->append(NetTools.trace(host.getText().toString().trim(),16))));addReportPanel();}
    private void switchTab(){
        addLabel("SWITCH UTILITIES • FIBER LINK HEALTH");
        content.addView(text("Cisco IOS / IOS-XE: optical Tx/Rx power, transceiver alarm/warning thresholds, link state, interface errors, and stack members. Read-only SSH.",14,0xffccefff));
        fiberHostEdit=entry(ciscoHost,"Cisco switch IP or DNS name");
        fiberUserEdit=entry(ciscoUsername,"Switch SSH username");
        fiberPassEdit=entry(ciscoPassword,"Switch SSH password");
        fiberPassEdit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fiberEnableEdit=entry(ciscoEnable,"Enable password (optional)");
        fiberEnableEdit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fiberPortEdit=entry("","Optional Te1/1/1; leave blank to scan all");
        addEntry("Cisco Switch IP / Hostname",fiberHostEdit);
        addEntry("Username",fiberUserEdit);
        addEntry("Password",fiberPassEdit);
        addEntry("Enable Secret (optional)",fiberEnableEdit);
        addEntry("Fiber Interface (optional)",fiberPortEdit);
        addButton("SCAN ALL FIBER LINKS + STACK HEALTH",()->scanFiber(false));
        addButton("CHECK SELECTED FIBER PORT",()->scanFiber(true));
        addButton("Test SSH Port 22",()->{
            final String host=fiberHostEdit.getText().toString().trim();
            if(host.isEmpty()){Toast.makeText(this,"Enter switch IP or name",Toast.LENGTH_LONG).show();return;}
            async(()->{append("DNS "+host+" → "+NetTools.resolve(host));append(NetTools.tcpTest(host,22,5000));});
        });
        content.addView(text("RED = bad optical alarm/failed fiber link. AMBER = warning or only one active fiber observed in a multi-member stack. GREEN = within reported thresholds. GRAY = not enough data.",13,0xffd3e8ff));
        fiberSummaryView=text(fiberResult,14,Color.WHITE);
        fiberSummaryView.setTextIsSelectable(true);
        fiberSummaryView.setTypeface(Typeface.MONOSPACE);
        fiberSummaryView.setBackgroundColor(PANEL);
        fiberSummaryView.setPadding(12,16,12,16);
        fiberSummaryView.setText(styleFiberText(fiberResult));
        content.addView(fiberSummaryView);
        addButton("Copy Fiber Health Summary",()->{
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Fiber Health Summary",fiberResult));
            Toast.makeText(this,"Fiber summary copied",Toast.LENGTH_SHORT).show();
        });
        addButton("Share / Email Fiber Report",()->{
            Intent i=new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT,"Keith's Ethernet Tools - Fiber Health Report");
            i.putExtra(Intent.EXTRA_TEXT(getReport());
            startActivity(Intent.createChooser(i,"Share fiber report"));
        });
        addReportPanel();
    }
    private CharSequence styleFiberText(String value){
        SpannableStringBuilder text=new SpannableStringBuilder(value);
        int offset=0;
        for(String line:value.split("\n",-1)){
            String upper=line.trim().toUpperCase(Locale.US);
            int color=-1;
            if(upper.startsWith("BAD")||upper.startsWith("STATUS: BAD")||upper.contains("ALARM:")||upper.contains("ACTIVE OPTICAL ALARM"))
                color=Color.rgb(255,80,92);
            else if(upper.startsWith("WARNING")||upper.startsWith("STATUS: WARNING")||upper.contains("ONLY ONE ACTIVE FIBER"))
                color=Color.rgb(255,196,56);
            else if(upper.startsWith("GOOD")||upper.startsWith("STATUS: GOOD"))
                color=Color.rgb(64,245,125);
            else if(upper.startsWith("UNKNOWN")||upper.startsWith("STATUS: UNKNOWN"))
                color=Color.rgb(170,190,205);
            if(color!=-1&&line.length()>0)
                text.setSpan(new ForegroundColorSpan(color),offset,offset+line.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            offset+=line.length()+1;
        }
        return text;
    }
    private void scanFiber(boolean selectedOnly){
        final String host=fiberHostEdit.getText().toString().trim();
        final String user=fiberUserEdit.getText().toString().trim();
        final String password=fiberPassEdit.getText().toString();
        final String enable=fiberEnableEdit.getText().toString();
        final String iface=fiberPortEdit.getText().toString().trim();
        if(host.isEmpty()||user.isEmpty()||password.isEmpty()){
            Toast.makeText(this,"Enter Cisco switch IP, username and password",Toast.LENGTH_LONG).show();return;
        }
        if(selectedOnly&&!CiscoPortParser.validInterface(iface)){
            Toast.makeText(this,"Enter a fiber port, for example Te1/1/1",Toast.LENGTH_LONG).show();return;
        }
        ciscoHost=host;ciscoUsername=user;ciscoPassword=password;ciscoEnable=enable;
        final String filter=selectedOnly?iface:"";
        append("==== FIBER LINK HEALTH: "+host+(selectedOnly?" / "+iface:" / ALL OPTICS")+" ====");
        fiberResult="Scanning fiber interfaces and switch stack...\n";
        if(fiberSummaryView!=null)fiberSummaryView.setText(styleFiberText(fiberResult));
        async(()->{
            try(CiscoSsh ssh=new CiscoSsh(this,message->append(message))){
                ssh.connect(host,user,password,enable);
                Map<String,String> output=ssh.commands(FiberHealthReport.commands());
                FiberHealthReport.Result result=FiberHealthReport.analyze(output,filter);
                fiberResult=result.text;
                append(result.text);
                handler.post(()->{
                    if(fiberSummaryView!=null)fiberSummaryView.setText(styleFiberText(result.text));
                });
                appendRawSwitchCommands(output);
            }catch(Exception ex){
                fiberResult="UNKNOWN | Fiber scan failed: "+ex.getClass().getSimpleName()+": "+ex.getMessage();
                append(fiberResult);
                handler.post(()->{
                    if(fiberSummaryView!=null)fiberSummaryView.setText(styleFiberText(fiberResult));
                });
            }
        });
    }
    private void nortelTab(){addLabel("NORTEL ERS 5520 • LIVE TELNET SESSION");content.addView(text("WARNING: Telnet sends usernames, passwords and commands unencrypted. Use only on an authorized trusted management network.",14,0xffffb76b));
        nortelHost=entry("","Nortel switch IP");nortelPort=entry("23","Port");nortelUser=entry("RW","RW username");nortelPass=entry("","Nortel password");nortelPass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        nortelCommand=entry("show stack-info","Read-only CLI command");addEntry("Switch IP",nortelHost);addEntry("Port",nortelPort);addEntry("Username",nortelUser);addEntry("Password (not saved)",nortelPass);
        addButton("Connect Telnet",()->new AlertDialog.Builder(this).setTitle("Unencrypted Telnet").setMessage("Username and password will be sent without encryption. Connect only to an authorized Nortel switch on a trusted network?").setNegativeButton("Cancel",null).setPositiveButton("Connect",(d,w)->async(()->{
            if(nortel!=null)nortel.close();nortel=new NortelTelnet(msg->append("NORTEL "+msg));nortel.connect(nortelHost.getText().toString().trim(),Integer.parseInt(nortelPort.getText().toString().trim()));
        })).show());
        addButton("Send Ctrl+Y",()->nortelAction(n->n.ctrlY()));addButton("Send RW + Enter",()->nortelAction(n->n.submitUser(nortelUser.getText().toString())));
        addButton("Send Password + Enter",()->nortelAction(n->n.submitPassword(nortelPass.getText().toString())));
        addButton("Select CLI menu (C)",()->nortelAction(n->n.menuCli()));addEntry("Nortel command",nortelCommand);
        addButton("Send Command",()->nortelAction(n->n.command(nortelCommand.getText().toString().trim())));
        addButton("Get Stack Info",()->nortelAction(n->n.command("show stack-info")));
        addButton("Get Fan / Power Info",()->nortelAction(n->n.command("show system verbose")));
        addButton("Get PoE Info",()->nortelAction(n->n.command("show poe-main-status")));
        addButton("Disconnect",()->{if(nortel!=null)nortel.close();append("Telnet disconnected");});
        content.addView(text("Use Connect → Ctrl+Y → RW → Password → CLI. The login steps are manual in this beta so you can see where your firmware prompts differ. Password is not included in the report.",13,0xffb9cde0));addReportPanel();}
    private interface TelnetAction {void apply(NortelTelnet client)throws Exception;}
    private void nortelAction(TelnetAction fn){async(()->{if(nortel==null||!nortel.connected()){append("Nortel not connected");return;}try{fn.apply(nortel);}catch(Exception e){append("TELNET ERROR: "+e.getMessage());}});}

    private void portVlanTab(){
        addLabel("CISCO SWITCH PORT / VLAN IDENTIFIER");
        content.addView(text("NEW: Easy-to-read VLAN chart showing DATA / ACCESS VLAN, VOICE VLAN, TRUNK NATIVE and ALLOWED VLANs, plus link status. Requires Cisco SSH login; port details depend on switch support.",14,0xffccefff));
        ciscoHostEdit=entry(ciscoHost,"Cisco switch IP or hostname");
        ciscoUserEdit=entry(ciscoUsername,"SSH username");
        ciscoPassEdit=entry(ciscoPassword,"SSH password");
        ciscoPassEdit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ciscoEnableEdit=entry(ciscoEnable,"Enable secret (optional)");
        ciscoEnableEdit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ciscoPortEdit=entry(ciscoPort,"Gi1/0/24");
        addEntry("Cisco Switch Address (SSH Port 22)",ciscoHostEdit);
        addEntry("Switch Username",ciscoUserEdit);addEntry("Switch Password",ciscoPassEdit);
        addEntry("Enable Password (only if needed)",ciscoEnableEdit);
        addEntry("Physical Port (optional, for single-port detail)",ciscoPortEdit);
        addButton("SHOW ALL PORTS + VLAN CHART",()->portVlanAction("all"));
        addButton("SHOW PORT: ACCESS + VOICE + TRUNK VLAN",()->portVlanAction("port"));
        addButton("Copy Complete Report",()->{
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Switch Port VLAN report",getReport()));
            Toast.makeText(this,"Report copied",Toast.LENGTH_SHORT).show();
        });
        addButton("Email / Share Report",()->{
            Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_SUBJECT,"Keith's Ethernet Tools - Switch Port VLAN Report");i.putExtra(Intent.EXTRA_TEXT,getReport());startActivity(Intent.createChooser(i,"Share Port Report"));
        });
        content.addView(text("The nonworking AP finder was removed. Enter a Cisco switch IP and a port to view VLAN details. Credentials stay in memory only while the app is open.",13,0xffe6c68e));
        addReportPanel();
    }
    private void portVlanAction(String action){
        final String host=ciscoHostEdit.getText().toString().trim();
        final String user=ciscoUserEdit.getText().toString().trim();
        final String pass=ciscoPassEdit.getText().toString();
        final String enable=ciscoEnableEdit.getText().toString();
        final String port=ciscoPortEdit.getText().toString().trim();
        if(host.isEmpty()||user.isEmpty()||pass.isEmpty()){Toast.makeText(this,"Enter switch IP, username and password",Toast.LENGTH_LONG).show();return;}
        if(action.equals("port")&&!CiscoPortParser.validInterface(port)){Toast.makeText(this,"Enter a port such as Gi1/0/24",Toast.LENGTH_LONG).show();return;}
        ciscoHost=host;ciscoUsername=user;ciscoPassword=pass;ciscoEnable=enable;ciscoPort=port;
        append("==== CISCO "+host+" / "+action.toUpperCase(Locale.US)+" PORT & VLAN REPORT ====");
        async(()->{
            try(CiscoSsh ssh=new CiscoSsh(this,msg->append(msg))){
                ssh.connect(host,user,pass,enable);
                if(action.equals("all")){
                    Map<String,String> vlanResults=ssh.commands(Arrays.asList(
                        "show interfaces status",
                        "show interfaces switchport",
                        "show vlan brief",
                        "show interfaces trunk",
                        "show power inline",
                        "show etherchannel summary"
                    ));
                    append(CiscoVlanReport.summarizeAll(vlanResults));
                    appendRawSwitchCommands(vlanResults);
                }else if(action.equals("port")){
                    queryCiscoPort(ssh,port);

                }
            }catch(Exception e){
                append("CISCO SSH ERROR: "+e.getClass().getSimpleName()+": "+e.getMessage());
            }
            append("==== END SWITCH PORT / VLAN REPORT ====");
        });
    }
    private void reportSwitchCommands(CiscoSsh ssh,List<String> commands)throws Exception{
        appendRawSwitchCommands(ssh.commands(commands));
    }
    private void appendRawSwitchCommands(Map<String,String> results){
        append("========== ORIGINAL CISCO CLI OUTPUT (select/copy) ==========");
        for(Map.Entry<String,String> result:results.entrySet()){
            append("---- "+result.getKey()+" ----\n"+result.getValue());
        }
    }
    private void queryCiscoPort(CiscoSsh ssh,String port)throws Exception{
        if(!CiscoPortParser.validInterface(port)){append("Invalid interface name returned: "+port);return;}
        append("PORT DETAILS: "+port);
        Map<String,String> results=ssh.commands(Arrays.asList(
            "show interfaces status",
            "show interfaces "+port+" switchport",
            "show running-config interface "+port,
            "show interfaces "+port,
            "show power inline "+port,
            "show mac address-table interface "+port,
            "show cdp neighbors "+port+" detail",
            "show lldp neighbors "+port+" detail"
        ));
        append(CiscoVlanReport.summarizePort(port,results));
        appendRawSwitchCommands(results);
        append("The formatted summary above is based on the switch output. Fields marked (not reported) were not returned by this switch or command.");
    }

    private void reportsTab(){addLabel("COMPLETE SESSION REPORT");addButton("Copy All",()->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Keith's Ethernet Tools",getReport()));Toast.makeText(this,"Report copied",Toast.LENGTH_SHORT).show();});
        addButton("Share / Email Text Report",()->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_SUBJECT,"Keith's Ethernet Tools Report");i.putExtra(Intent.EXTRA_TEXT,getReport());startActivity(Intent.createChooser(i,"Share report"));});
        addButton("Clear Report",()->{synchronized(report){report.setLength(0);}if(reportView!=null)reportView.setText("");});addReportPanel();}
}
