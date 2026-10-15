package com.jenkinskg.urltomp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.swig.torrent_flags_t;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search and download public-domain / openly licensed movie files.
 * Search only exposes appropriately tagged Internet Archive items; licensing
 * still needs to be verified by the person downloading.
 *
 * HTTP downloads use the Android DownloadManager (survive app exit).
 * Torrent transfers use libtorrent4j and only run while this activity is open.
 * Use Android Always-on VPN + Block connections without VPN for leak protection.
 */
public class MovieActivity extends Activity {
    private static final int BG=0xff0c1219, PANEL=0xff1c2732, GREEN=0xff3ff7aa;
    private static final int REQUEST_TORRENT=407;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private EditText searchField, magnetField;
    private LinearLayout results, fileRows;
    private TextView status, vpnStatus;
    private ProgressBar progress;
    private final ArrayList<MovieFile> files=new ArrayList<>();
    private final ArrayList<CheckBox> checks=new ArrayList<>();
    private volatile SessionManager torrentSession;
    private volatile boolean torrentPaused=false, closed=false;
    private String chosenArchiveId=null, chosenTorrentName=null;
    private final Runnable vpnMonitor=new Runnable() {
        @Override public void run() {
            if(closed)return;
            boolean vpn=vpnActive();
            vpnStatus.setText(vpn?"VPN connected":"VPN not detected");
            vpnStatus.setTextColor(vpn?GREEN:0xffffb261);
            if(!vpn && torrentSession!=null){
                status("VPN lost — stopping torrent session");
                stopTorrent();
            }
            ui.postDelayed(this,1600);
        }
    };
    private static class MovieFile {
        final String name,url;
        final long bytes;
        MovieFile(String n,String u,long b){name=n;url=u;bytes=b;}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);
        root.setPadding(dp(18),dp(23),dp(18),dp(28));
        root.setOrientation(1);
        scroll.addView(root);setContentView(scroll);

        root.addView(text("MOVIES / VIDEO DOWNLOADS",24,Color.WHITE));
        root.addView(text("Find openly licensed movies, then choose full video files or an Archive torrent. Verify download rights before proceeding.",13,0xffb7c5d0));
        searchField=field("Movie title, actor, or year");
        add(root,searchField);
        Button search=button("SEARCH MOVIES",true);add(root,search);
        search.setOnClickListener(v->search());
        results=new LinearLayout(this);results.setOrientation(1);add(root,results);
        root.addView(text("SELECT VIDEO FILES",15,GREEN));
        fileRows=new LinearLayout(this);fileRows.setOrientation(1);add(root,fileRows);
        Button direct=button("DOWNLOAD SELECTED VIDEO FILES",true);add(root,direct);
        direct.setOnClickListener(v->downloadSelected());
        Button archiveTorrent=button("DOWNLOAD ARCHIVE TORRENT",false);add(root,archiveTorrent);
        archiveTorrent.setOnClickListener(v->startArchiveTorrent());

        root.addView(text("YOUR AUTHORIZED TORRENTS",15,GREEN));
        magnetField=field("Paste a magnet:?xt=urn:btih:... link");
        add(root,magnetField);
        Button magnet=button("START MAGNET",false);add(root,magnet);
        magnet.setOnClickListener(v->startMagnet());
        Button open=button("OPEN .TORRENT FILE",false);add(root,open);
        open.setOnClickListener(v->openTorrentFile());
        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        add(root,controls);
        Button pause=button("PAUSE",false),resume=button("RESUME",false),stop=button("STOP",false);
        for(Button btn:new Button[]{pause,resume,stop}){
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(51),1);
            lp.setMargins(dp(2),dp(5),dp(2),dp(5));controls.addView(btn,lp);
        }
        pause.setOnClickListener(v->{
            SessionManager s=torrentSession;
            if(s!=null){s.pause();torrentPaused=true;status("Torrent paused");}
        });
        resume.setOnClickListener(v->{
            SessionManager s=torrentSession;
            if(s!=null){
                if(!vpnActive()){status("Connect VPN before resuming");return;}
                s.resume();torrentPaused=false;status("Torrent resumed");
            }
        });
        stop.setOnClickListener(v->stopTorrent());
        vpnStatus=text("Checking VPN…",14,0xffffb261);add(root,vpnStatus);
        status=text("Ready",15,Color.WHITE);add(root,status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);progress.setMax(100);root.addView(progress);
        root.addView(text("Direct videos save to Movies / URLtoMP3. Torrents save in the app's Movies/TorrentMovies folder while this screen remains open. For VPN leak prevention, enable Android Always-on VPN and Block connections without VPN.",12,0xff9dadbc));
        ui.post(vpnMonitor);
    }

    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private TextView text(String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);t.setPadding(dp(3),dp(7),dp(3),dp(7));return t;
    }
    private Button button(String caption,boolean green){
        Button b=new Button(this);b.setText(caption);
        b.setTextColor(green?0xff0c1219:Color.WHITE);
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(green?GREEN:PANEL);shape.setCornerRadius(dp(9));
        b.setBackground(shape);return b;
    }
    private EditText field(String hint){
        EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);
        e.setTextColor(Color.WHITE);e.setHintTextColor(0xff9dadbc);
        e.setBackgroundColor(PANEL);e.setPadding(dp(10),dp(8),dp(10),dp(8));
        return e;
    }
    private void add(LinearLayout root,View v){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(7),0,dp(6));root.addView(v,lp);
    }
    private void status(String msg){
        ui.post(()->{if(!closed&&status!=null)status.setText(msg);});
    }
    private boolean vpnActive(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return false;
        Network network=cm.getActiveNetwork();
        NetworkCapabilities cap=network==null?null:cm.getNetworkCapabilities(network);
        return cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }
    private static String enc(String s)throws Exception {
        return URLEncoder.encode(s,"UTF-8").replace("+","%20");
    }
    private static byte[] fetch(String url,int limit)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(14000);c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent","MusicMovieDownloader/4.0 (licensed Archive media)");
        c.setInstanceFollowRedirects(true);
        try{
            int code=c.getResponseCode();
            if(code<200||code>=300)throw new Exception("HTTP "+code);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[16384];int n;
                while((n=in.read(buf))!=-1){
                    if(out.size()+n>limit)throw new Exception("Response too large");
                    out.write(buf,0,n);
                }
                return out.toByteArray();
            }
        }finally{c.disconnect();}
    }
    private static JSONObject json(String url)throws Exception{
        return new JSONObject(new String(fetch(url,5*1024*1024),StandardCharsets.UTF_8));
    }
    private static boolean permitted(String raw){
        String s=raw.toLowerCase(Locale.ROOT);
        return s.contains("creativecommons.org/licenses/") ||
               s.contains("creativecommons.org/publicdomain/") ||
               s.contains("creativecommons.org/mark/");
    }
    private void search(){
        String q=searchField.getText().toString().trim();
        if(q.length()<2||q.length()>70){searchField.setError("Enter 2–70 characters");return;}
        results.removeAllViews();fileRows.removeAllViews();
        chosenArchiveId=null;chosenTorrentName=null;
        files.clear();checks.clear();status("Searching Internet Archive…");
        worker.execute(()->{
            try{
                String safe=q.replace('"',' ').replaceAll("[^\\p{L}\\p{N} ._'\\-]"," ").trim();
                String query="mediatype:movies AND (licenseurl:http*creativecommons* OR licenseurl:http*publicdomain*) AND (title:\""+safe+"\" OR creator:\""+safe+"\" OR year:\""+safe+"\")";
                String url="https://archive.org/advancedsearch.php?q="+enc(query)+
                    "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&rows=25&output=json";
                JSONArray found=json(url).getJSONObject("response").getJSONArray("docs");
                ui.post(()->{
                    if(closed)return;
                    status(found.length()+" catalog matches. Select a movie to inspect its files.");
                    for(int i=0;i<found.length();i++){
                        JSONObject o=found.optJSONObject(i);if(o==null)continue;
                        String id=o.optString("identifier",""),name=o.optString("title",id);
                        if(!id.matches("[A-Za-z0-9_.-]+"))continue;
                        Button b=button(name+" ("+o.optString("year","")+")",false);
                        add(results,b);b.setOnClickListener(v->inspect(id));
                    }
                });
            }catch(Exception e){status("Search failed: "+e.getMessage());}
        });
    }
    private static boolean video(String name){
        String n=name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4")||n.endsWith(".mkv")||n.endsWith(".avi")||
            n.endsWith(".webm")||n.endsWith(".mov")||n.endsWith(".m4v")||n.endsWith(".ogv");
    }
    private void inspect(String id){
        chosenArchiveId=id;chosenTorrentName=null;files.clear();checks.clear();fileRows.removeAllViews();
        status("Inspecting movie files and license…");
        worker.execute(()->{
            try{
                JSONObject data=json("https://archive.org/metadata/"+enc(id));
                JSONObject metadata=data.optJSONObject("metadata");
                if(metadata==null||!permitted(metadata.optString("licenseurl","")))
                    throw new Exception("No verified open-license URL on this Archive item");
                JSONArray entries=data.optJSONArray("files");
                if(entries==null)throw new Exception("No downloadable files listed");
                ArrayList<MovieFile> found=new ArrayList<>();
                String torrentName=null;
                for(int i=0;i<entries.length();i++){
                    JSONObject f=entries.optJSONObject(i);if(f==null)continue;
                    String name=f.optString("name","");
                    if(name.toLowerCase(Locale.ROOT).endsWith(".torrent")&&torrentName==null)torrentName=name;
                    if(video(name)&&found.size()<120){
                        found.add(new MovieFile(name,"https://archive.org/download/"+enc(id)+"/"+Uri.encode(name,"/"),f.optLong("size",0)));
                    }
                }
                final String resolvedTorrent=torrentName;
                ui.post(()->{
                    if(closed||!id.equals(chosenArchiveId))return;
                    chosenTorrentName=resolvedTorrent;files.addAll(found);
                    fileRows.removeAllViews();checks.clear();
                    status(found.size()+" movie files available"+(resolvedTorrent==null?" (no torrent)":" (torrent available)"));
                    if(found.isEmpty())add(fileRows,text("No supported video files in this entry.",13,Color.WHITE));
                    for(MovieFile f:found){
                        CheckBox cb=new CheckBox(this);
                        cb.setText(f.name+" — "+String.format(Locale.US,"%.1f MB",f.bytes/1048576.0));
                        cb.setTextColor(Color.WHITE);add(fileRows,cb);checks.add(cb);
                    }
                });
            }catch(Exception e){status("Cannot inspect movie: "+e.getMessage());}
        });
    }
    private void downloadSelected(){
        DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        if(dm==null){status("Android download service not available");return;}
        int queued=0;
        for(int i=0;i<checks.size();i++){
            if(!checks.get(i).isChecked())continue;
            MovieFile f=files.get(i);
            try{
                String leaf=f.name.substring(f.name.lastIndexOf('/')+1).replaceAll("[^A-Za-z0-9._() -]","_");
                if(leaf.isEmpty())leaf="movie-"+i+".mp4";
                DownloadManager.Request req=new DownloadManager.Request(Uri.parse(f.url));
                req.setTitle(leaf);
                req.setDescription("Authorized film from Internet Archive");
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES,"URLtoMP3/"+leaf);
                dm.enqueue(req);queued++;
            }catch(Exception e){status("Could not queue download: "+e.getMessage());}
        }
        if(queued>0)status("Queued "+queued+" full video(s) in Android Downloads");
        else status("Select one or more video files");
    }
    private void startArchiveTorrent(){
        String id=chosenArchiveId,name=chosenTorrentName;
        if(id==null||name==null){status("Select a licensed film that includes a torrent");return;}
        if(!vpnActive()){status("Connect VPN before starting torrents");return;}
        new AlertDialog.Builder(this).setTitle("Download this film torrent?")
            .setMessage("The complete Archive torrent can include extras, metadata and posters. Downloads run only while this screen is open.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Start",(d,w)->worker.execute(()->{
                try{
                    byte[] content=fetch("https://archive.org/download/"+enc(id)+"/"+Uri.encode(name,"/"),16*1024*1024);
                    File tmp=new File(getCacheDir(),"movie-"+id+".torrent");
                    try(FileOutputStream out=new FileOutputStream(tmp)){out.write(content);}
                    startTorrentInfo(new TorrentInfo(tmp));
                }catch(Exception e){status("Torrent failed: "+e.getMessage());}
            })).show();
    }
    private void startMagnet(){
        String uri=magnetField.getText().toString().trim();
        if(!(uri.startsWith("magnet:?xt=urn:btih:")||uri.startsWith("magnet:?xt=urn:btmh:"))){
            magnetField.setError("Enter a valid magnet URI");return;
        }
        if(!vpnActive()){status("Connect VPN before starting torrents");return;}
        new AlertDialog.Builder(this).setTitle("Download authorized torrent?")
            .setMessage("Magnet downloads may include multiple files. Only use content you have rights to download.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Start",(d,w)->worker.execute(()->{
                try{
                    SessionManager s=newTorrentSession();
                    s.download(uri,torrentFolder(),new torrent_flags_t());
                    status("Magnet added. Keep this screen open.");
                }catch(Exception e){status("Magnet failed: "+e.getMessage());}
            })).show();
    }
    private void openTorrentFile(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,REQUEST_TORRENT);
    }
    @Override protected void onActivityResult(int request,int code,Intent data){
        super.onActivityResult(request,code,data);
        if(request!=REQUEST_TORRENT||code!=RESULT_OK||data==null||data.getData()==null)return;
        if(!vpnActive()){status("Connect VPN before starting torrents");return;}
        Uri uri=data.getData();
        worker.execute(()->{
            try{
                File tmp=new File(getCacheDir(),"selected-movie.torrent");
                try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(tmp)){
                    if(in==null)throw new Exception("Cannot open torrent");
                    byte[] buf=new byte[8192];int n,total=0;
                    while((n=in.read(buf))!=-1){total+=n;if(total>16*1024*1024)throw new Exception("Torrent metadata too large");out.write(buf,0,n);}
                }
                startTorrentInfo(new TorrentInfo(tmp));
            }catch(Exception e){status("Cannot open torrent: "+e.getMessage());}
        });
    }
    private File torrentFolder()throws Exception{
        File parent=getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if(parent==null)throw new Exception("Movie storage unavailable");
        File dir=new File(parent,"TorrentMovies");
        if(!dir.exists()&&!dir.mkdirs())throw new Exception("Cannot create torrent folder");
        return dir;
    }
    private synchronized SessionManager newTorrentSession()throws Exception{
        if(!vpnActive())throw new Exception("VPN disconnected; transfer blocked");
        if(torrentSession==null){
            SessionManager s=new SessionManager();s.start();
            torrentSession=s;torrentPaused=false;
            runOnUiThread(()->getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        }
        return torrentSession;
    }
    private void startTorrentInfo(TorrentInfo info)throws Exception{
        if(!info.isValid())throw new Exception("Invalid .torrent metadata");
        SessionManager s=newTorrentSession();
        s.download(info,torrentFolder());
        status("Torrent started. Keep this screen open.");
    }
    private synchronized void stopTorrent(){
        SessionManager s=torrentSession;torrentSession=null;
        torrentPaused=false;
        if(s!=null){
            worker.execute(()->{try{s.stop();}catch(Exception ignored){}});
            status("Torrent session stopped; partial files retained");
        }
        runOnUiThread(()->getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
    }
    @Override protected void onDestroy(){
        closed=true;ui.removeCallbacks(vpnMonitor);
        stopTorrent();
        worker.shutdown();
        super.onDestroy();
    }
}
