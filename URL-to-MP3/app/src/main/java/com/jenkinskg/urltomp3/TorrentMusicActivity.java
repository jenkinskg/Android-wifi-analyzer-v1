package com.jenkinskg.urltomp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.view.WindowManager;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.libtorrent4j.FileStorage;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.torrent_flags_t;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Searches license-marked audio in the Internet Archive, examines the torrent's
 * manifest before transfer, and downloads only explicitly selected audio files.
 *
 * Initial torrent transfers require keeping this screen open. Network failures,
 * missing seeds, VPN disconnection, or backgrounding stop the session. The user's
 * Android VPN settings (Always-on + Block without VPN) remain the stronger
 * guarantee against network leaks than an app-level periodic check.
 */
public class TorrentMusicActivity extends Activity {
    private static final int BG=0xff0c1219, PANEL=0xff1c2732, GREEN=0xff3ff7aa;
    private static final String API="https://archive.org/";
    private static final int MAX_RESULTS=20, MAX_FILES=150;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel=new AtomicBoolean(false);
    private volatile boolean downloading=false, searching=false;
    private volatile SessionManager torrentSession;
    private EditText queryInput;
    private LinearLayout results;
    private TextView status, detail, vpnStatus;
    private ProgressBar progress;
    private Button searchButton,cancelButton;
    private CheckBox requireVpn;

    private static class AudioFile {
        final String name;
        final long bytes;
        AudioFile(String name,long bytes){this.name=name;this.bytes=bytes;}
    }
    private static class ArchiveItem {
        final String identifier,title,creator,license;
        ArchiveItem(String identifier,String title,String creator,String license){
            this.identifier=identifier;this.title=title;this.creator=creator;this.license=license;
        }
    }
    private static class ArchiveRelease {
        final ArchiveItem item;
        final String torrentFile;
        final ArrayList<AudioFile> audioFiles;
        ArchiveRelease(ArchiveItem item,String torrentFile,ArrayList<AudioFile> audioFiles){
            this.item=item;this.torrentFile=torrentFile;this.audioFiles=audioFiles;
        }
    }

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);
        root.setPadding(dp(19),dp(21),dp(19),dp(30));scroll.addView(root);setContentView(scroll);
        root.addView(text("MUSIC TORRENTS / VERSION 3",13,GREEN,true));
        TextView heading=text("Find legal music",30,Color.WHITE,true);
        add(root,heading,55,9);
        root.addView(text("Search Internet Archive's Creative Commons/public-domain audio. Inspect the files and download only the music you select.",14,0xffbacbd2,false));
        queryInput=new EditText(this);queryInput.setSingleLine(true);
        queryInput.setHint("Band, artist, live performance, album");
        queryInput.setHintTextColor(0xffa8b9c6);queryInput.setTextColor(Color.WHITE);
        queryInput.setTextSize(15);queryInput.setBackground(round(PANEL,10));
        queryInput.setPadding(dp(13),0,dp(13),0);
        add(root,queryInput,54,15);
        searchButton=button("SEARCH MUSIC TORRENTS",true);
        add(root,searchButton,51,10);
        searchButton.setOnClickListener(v->search());
        requireVpn=new CheckBox(this);requireVpn.setChecked(true);
        requireVpn.setButtonTintList(ColorStateList.valueOf(GREEN));
        requireVpn.setTextColor(Color.WHITE);
        requireVpn.setTextSize(14);
        requireVpn.setText("Require connected VPN for torrent transfers");
        add(root,requireVpn,55,10);
        vpnStatus=text("",14,0xffbbd6c7,false);add(root,vpnStatus,38,3);
        Button check=button("CHECK VPN",false);add(root,check,46,4);
        check.setOnClickListener(v->refreshVpn());
        status=text("Ready to search",18,Color.WHITE,true);add(root,status,35,20);
        detail=text("Torrent files are inspected before anything is downloaded.",13,0xffb6cad6,false);
        root.addView(detail);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);progress.setProgressTintList(ColorStateList.valueOf(GREEN));
        add(root,progress,11,15);
        cancelButton=button("STOP TORRENT DOWNLOAD",false);
        cancelButton.setEnabled(false);
        add(root,cancelButton,52,10);
        cancelButton.setOnClickListener(v->{
            cancel.set(true);
            setStatus("Stopping…","Ending the peer connections and saving partial data.");
        });
        TextView found=text("SEARCH RESULTS",13,GREEN,true);
        add(root,found,38,20);
        results=new LinearLayout(this);results.setOrientation(1);root.addView(results);
        TextView note=text("A VPN is not proof of licensing or a guaranteed kill switch. For stronger leak protection, enable your VPN's Android Always-on and Block connections without VPN settings. Keep this screen open while downloading.",12,0xff9badb9,false);
        note.setPadding(0,dp(18),0,0);root.addView(note);
        refreshVpn();
    }

    private void refreshVpn(){
        boolean active=isVpnActive();
        vpnStatus.setText(active?"VPN connected (Android reports VPN transport)":"VPN not detected");
        vpnStatus.setTextColor(active?GREEN:0xffffb261);
    }
    private boolean isVpnActive(){
        ConnectivityManager mgr=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if(mgr==null)return false;
        Network n=mgr.getActiveNetwork();
        NetworkCapabilities cap=n==null?null:mgr.getNetworkCapabilities(n);
        return cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }
    private void search(){
        if(searching||downloading)return;
        String term=queryInput.getText().toString().trim().replaceAll("[^\\p{L}\\p{N} _'-]"," ");
        if(term.length()<2){queryInput.setError("Enter a search");return;}
        if(term.length()>70){queryInput.setError("Keep search under 70 characters");return;}
        searching=true;searchButton.setEnabled(false);
        results.removeAllViews();
        setStatus("Searching licensed music…",term);
        worker.execute(()->{
            try{
                String escaped=term.replace("\"","").trim();
                String q="(mediatype:audio OR mediatype:etree) AND (licenseurl:http*creativecommons* OR licenseurl:http*publicdomain*) AND (title:\""+escaped+"\" OR creator:\""+escaped+"\" OR "+escaped+")";
                String url=API+"advancedsearch.php?q="+enc(q)
                    +"&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator"
                    +"&fl%5B%5D=licenseurl&rows="+MAX_RESULTS+"&output=json";
                JSONObject response=new JSONObject(readJson(url));
                JSONArray docs=response.getJSONObject("response").getJSONArray("docs");
                ArrayList<ArchiveItem> found=new ArrayList<>();
                for(int i=0;i<docs.length();i++){
                    JSONObject d=docs.optJSONObject(i);if(d==null)continue;
                    String id=d.optString("identifier","");
                    String license=d.optString("licenseurl","");
                    if(!id.matches("[a-zA-Z0-9_\\-.]+"))continue;
                    if(!licensed(license))continue;
                    found.add(new ArchiveItem(id,d.optString("title",id),d.optString("creator",""),license));
                }
                runOnUiThread(()->{
                    for(ArchiveItem a:found)addResult(a);
                    setStatus(found.size()+" licensed results","Tap CHECK AUDIO & TORRENT to see actual downloadable tracks.");
                    if(found.isEmpty())showEmpty();
                });
            }catch(Exception e){
                setStatus("Search failed",shortError(e));
            }finally{
                searching=false;
                runOnUiThread(()->searchButton.setEnabled(!downloading));
            }
        });
    }
    private static boolean licensed(String raw){
        String s=raw.toLowerCase(Locale.ROOT);
        return s.contains("creativecommons.org/licenses/")||
               s.contains("creativecommons.org/publicdomain/")||
               s.contains("creativecommons.org/mark/");
    }
    private void showEmpty(){
        results.addView(text("No matching Creative Commons or public-domain recordings in this search. Try another artist or 'live music'.",14,0xffafbecb,false));
    }
    private void addResult(ArchiveItem item){
        LinearLayout card=new LinearLayout(this);card.setOrientation(1);
        card.setPadding(dp(13),dp(12),dp(13),dp(13));
        card.setBackground(round(PANEL,12));
        TextView title=text(item.title,16,Color.WHITE,true);card.addView(title);
        if(!item.creator.isEmpty())card.addView(text(item.creator,13,0xffbdcfd8,false));
        card.addView(text("License: "+item.license,11,0xff87beac,false));
        Button inspect=button("CHECK AUDIO & TORRENT",false);
        add(card,inspect,46,9);
        inspect.setOnClickListener(v->inspect(item,inspect));
        add(results,card,-2,10);
    }
    private void inspect(ArchiveItem item,Button btn){
        if(searching||downloading)return;
        btn.setEnabled(false);
        setStatus("Checking files…",item.title);
        worker.execute(()->{
            try{
                JSONObject all=new JSONObject(readJson(API+"metadata/"+enc(item.identifier)));
                JSONObject meta=all.optJSONObject("metadata");
                String license=meta==null?"":meta.optString("licenseurl","");
                if(!licensed(license))throw new IOException("Item has no verified CC/public-domain license URL");
                JSONArray files=all.optJSONArray("files");
                if(files==null)throw new IOException("No file metadata available");
                String torrentName=null;
                ArrayList<AudioFile> tracks=new ArrayList<>();
                for(int i=0;i<files.length();i++){
                    JSONObject f=files.optJSONObject(i);if(f==null)continue;
                    String name=f.optString("name","");
                    if(name.toLowerCase(Locale.ROOT).endsWith(".torrent"))torrentName=name;
                    if(isAudio(name)&&tracks.size()<MAX_FILES)tracks.add(new AudioFile(name,f.optLong("size",0)));
                }
                if(torrentName==null)throw new IOException("No BitTorrent file is provided for this recording.");
                if(tracks.isEmpty())throw new IOException("Torrent listing has no supported audio files.");
                ArchiveRelease release=new ArchiveRelease(
                    new ArchiveItem(item.identifier,item.title,item.creator,license),torrentName,tracks);
                runOnUiThread(()->chooseTracks(release));
                setStatus(tracks.size()+" audio tracks found","Choose tracks to download via BitTorrent.");
            }catch(Exception e){
                setStatus("Not downloadable as music torrent",shortError(e));
            }finally{runOnUiThread(()->btn.setEnabled(!downloading));}
        });
    }
    private void chooseTracks(ArchiveRelease release){
        int n=release.audioFiles.size();
        CharSequence[] labels=new CharSequence[n];boolean[] checked=new boolean[n];
        for(int i=0;i<n;i++){
            AudioFile a=release.audioFiles.get(i);
            labels[i]=a.name+" ("+formatBytes(a.bytes)+")";
            checked[i]=false;
        }
        new AlertDialog.Builder(this)
            .setTitle("Choose music files — "+release.item.title)
            .setMultiChoiceItems(labels,checked,(dialog,which,isChecked)->checked[which]=isChecked)
            .setNeutralButton("All audio",(dialog,which)->{
                // Reopen the chooser with everything checked, rather than auto-start downloading.
                chooseAllTracks(release);
            })
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Download selected",(dialog,which)->startSelected(release,checked))
            .show();
    }
    private void chooseAllTracks(ArchiveRelease release){
        int n=release.audioFiles.size();
        CharSequence[] labels=new CharSequence[n];boolean[] checked=new boolean[n];
        for(int i=0;i<n;i++){
            AudioFile a=release.audioFiles.get(i);labels[i]=a.name+" ("+formatBytes(a.bytes)+")";
            checked[i]=true;
        }
        new AlertDialog.Builder(this)
            .setTitle("Choose music files — "+release.item.title)
            .setMultiChoiceItems(labels,checked,(dialog,i,b)->checked[i]=b)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Download selected",(dialog,which)->startSelected(release,checked))
            .show();
    }
    private void startSelected(ArchiveRelease release,boolean[] selected){
        if(downloading||searching)return;
        Set<String> requested=new HashSet<>();long size=0;
        for(int i=0;i<selected.length;i++)if(selected[i]){
            AudioFile a=release.audioFiles.get(i);
            requested.add(a.name.toLowerCase(Locale.ROOT).replace('\\','/'));
            size+=Math.max(0,a.bytes);
        }
        if(requested.isEmpty()){
            Toast.makeText(this,"Select at least one audio track",Toast.LENGTH_LONG).show();return;
        }
        if(requireVpn.isChecked()&&!isVpnActive()){
            refreshVpn();
            new AlertDialog.Builder(this).setTitle("VPN not connected")
                .setMessage("Connect your VPN, then try downloading again. The app will block peer transfers until Android reports an active VPN.")
                .setPositiveButton("OK",null).show();return;
        }
        final long total=size;
        new AlertDialog.Builder(this)
            .setTitle("Start music torrent?")
            .setMessage(requested.size()+" audio files, approximately "+formatBytes(total)
                +". Only the selected audio files will be requested. Keep this screen open during the transfer.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Start torrent",(d,w)->downloadTorrent(release,requested,total))
            .show();
    }
    private void downloadTorrent(ArchiveRelease release,Set<String> selected,long estimatedSize){
        if(downloading)return;
        downloading=true;cancel.set(false);
        searchButton.setEnabled(false);cancelButton.setEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setStatus("Preparing torrent…",release.item.title);
        worker.execute(()->transfer(release,selected,estimatedSize));
    }
    private void transfer(ArchiveRelease release,Set<String> selected,long estimatedSize){
        SessionManager session=null;
        File torrentFile=null;
        try{
            File stage=new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                "TorrentStage/"+release.item.identifier);
            if(!stage.exists()&&!stage.mkdirs())throw new IOException("Could not create transfer folder");
            if(new StatFs(stage.getAbsolutePath()).getAvailableBytes()<estimatedSize+32L*1024*1024)
                throw new IOException("Not enough free storage for selected tracks.");
            if(requireVpn.isChecked()&&!isVpnActive())
                throw new IOException("VPN disconnected before starting transfer");
            String safePath=encodePath(release.torrentFile);
            String torrentUrl=API+"download/"+enc(release.item.identifier)+"/"+safePath;
            torrentFile=new File(getCacheDir(),"audio-"+release.item.identifier+".torrent");
            downloadSmall(torrentUrl,torrentFile,8*1024*1024);
            TorrentInfo info=new TorrentInfo(torrentFile);
            if(!info.isValid())throw new IOException("The site's torrent metadata is invalid");
            FileStorage files=info.files();
            Priority[] priorities=new Priority[files.numFiles()];
            int included=0;
            ArrayList<String> paths=new ArrayList<>();
            for(int i=0;i<files.numFiles();i++){
                String path=files.filePath(i).replace('\\','/');
                boolean chosen=false;
                for(String wanted:selected){
                    if(path.toLowerCase(Locale.ROOT).equals(wanted)||path.toLowerCase(Locale.ROOT).endsWith("/"+wanted)){
                        chosen=isAudio(path);break;
                    }
                }
                priorities[i]=chosen?Priority.DEFAULT:Priority.IGNORE;
                if(chosen){included++;paths.add(path);}
            }
            if(included==0)throw new IOException("The torrent does not contain the selected audio files.");
            if(requireVpn.isChecked()&&!isVpnActive())throw new IOException("VPN disconnected before torrent engine started");
            session=new SessionManager();
            torrentSession=session;
            session.start();
            session.download(info,stage,null,priorities,null,new torrent_flags_t());
            setStatus("Connecting to torrent peers…",included+" audio files selected");
            long started=System.currentTimeMillis();
            int lastProgress=-1;
            while(!cancel.get()){
                if(requireVpn.isChecked()&&!isVpnActive()){
                    session.pause();
                    throw new IOException("VPN disconnected — torrent session was stopped. Enable VPN and retry.");
                }
                TorrentHandle handle=session.find(info.infoHash());
                if(handle!=null&&handle.isValid()){
                    TorrentStatus state=handle.status();
                    int pct=Math.max(0,Math.min(100,(int)(state.progress()*100)));
                    if(pct!=lastProgress){
                        lastProgress=pct;int shown=pct;
                        runOnUiThread(()->progress.setProgress(shown));
                    }
                    setStatus(state.isFinished()?"Transfer complete":"Torrent downloading: "+pct+"%",
                        state.numPeers()+" peers · "+state.numSeeds()+" seeds · "+formatBytes(state.downloadRate())+"/s");
                    if(state.isFinished()){
                        session.pause(); // stop seeding before copying to Music.
                        int copied=0;
                        for(String path:paths){
                            File input=new File(stage,path);
                            String root=stage.getCanonicalPath()+File.separator;
                            if(!input.getCanonicalPath().startsWith(root)||!input.isFile())continue;
                            if(!isAudio(input.getName()))continue;
                            writeToMusic(input);
                            copied++;
                        }
                        if(copied==0)throw new IOException("Torrent finished but no selected audio files were available.");
                        setStatus("Saved "+copied+" audio files","Music / Torrent Music — transfer stopped.");
                        runOnUiThread(()->progress.setProgress(100));
                        return;
                    }
                }else if(System.currentTimeMillis()-started>60000){
                    setStatus("Waiting for torrent metadata/peers…","No peers connected yet. You can stop the transfer.");
                }
                Thread.sleep(1000);
            }
            setStatus("Torrent stopped","Partial files remain in the app's torrent staging folder.");
        }catch(Throwable e){
            setStatus("Torrent failed or stopped",cancel.get()?"Stopped by user":shortError(e));
        }finally{
            if(session!=null){try{session.stop();}catch(Throwable ignored){}}
            torrentSession=null;
            if(torrentFile!=null)torrentFile.delete();
            downloading=false;
            runOnUiThread(()->{
                cancelButton.setEnabled(false);searchButton.setEnabled(true);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                refreshVpn();
            });
        }
    }
    private void writeToMusic(File file)throws IOException{
        String mime="audio/mpeg";
        String lower=file.getName().toLowerCase(Locale.ROOT);
        if(lower.endsWith(".flac"))mime="audio/flac";
        else if(lower.endsWith(".ogg")||lower.endsWith(".opus"))mime="audio/ogg";
        else if(lower.endsWith(".wav"))mime="audio/wav";
        else if(lower.endsWith(".m4a"))mime="audio/mp4";
        else if(lower.endsWith(".aac"))mime="audio/aac";
        ContentValues values=new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME,file.getName());
        values.put(MediaStore.Audio.Media.MIME_TYPE,mime);
        values.put(MediaStore.Audio.Media.RELATIVE_PATH,Environment.DIRECTORY_MUSIC+"/Torrent Music");
        values.put(MediaStore.Audio.Media.IS_PENDING,1);
        Uri dest=getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values);
        if(dest==null)throw new IOException("Cannot create Music file.");
        boolean okay=false;
        try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(dest)){
            if(out==null)throw new IOException("Cannot write Music file.");
            byte[] buf=new byte[65536];int n;
            while((n=in.read(buf))!=-1)out.write(buf,0,n);
            okay=true;
        }finally{if(!okay)getContentResolver().delete(dest,null,null);}
        values.clear();values.put(MediaStore.Audio.Media.IS_PENDING,0);
        getContentResolver().update(dest,values,null,null);
    }
    private static String readJson(String url)throws IOException{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(16000);
        c.setRequestProperty("User-Agent","URLtoMP3Android/3.0 (public-license music discovery)");
        try{
            if(c.getResponseCode()!=200)throw new IOException("Music catalog returned HTTP "+c.getResponseCode());
            try(InputStream stream=c.getInputStream()){
                byte[] data=readLimited(stream,3*1024*1024);
                return new String(data,java.nio.charset.StandardCharsets.UTF_8);
            }
        }finally{c.disconnect();}
    }
    private static byte[] readLimited(InputStream in,int limit)throws IOException{
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1){
            if(out.size()+n>limit)throw new IOException("Server response is too large");
            out.write(buf,0,n);
        }
        return out.toByteArray();
    }
    private static void downloadSmall(String url,File file,int maxSize)throws IOException{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        try{
            if(c.getResponseCode()!=200)throw new IOException("Torrent file unavailable (HTTP "+c.getResponseCode()+")");
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(file)){
                byte[] bytes=readLimited(in,maxSize);
                out.write(bytes);
            }
        }finally{c.disconnect();}
    }
    private static boolean isAudio(String name){
        String x=name.toLowerCase(Locale.ROOT);
        return x.endsWith(".mp3")||x.endsWith(".flac")||x.endsWith(".ogg")||
               x.endsWith(".opus")||x.endsWith(".wav")||x.endsWith(".m4a")||x.endsWith(".aac");
    }
    private static String enc(String s)throws java.io.UnsupportedEncodingException{
        return URLEncoder.encode(s,"UTF-8").replace("+","%20");
    }
    private static String encodePath(String path)throws IOException{
        String[] bits=path.replace('\\','/').split("/");
        StringBuilder sb=new StringBuilder();
        for(String part:bits){
            if(part.equals("..")||part.equals("."))throw new IOException("Unsafe torrent path");
            if(part.length()==0)continue;
            if(sb.length()>0)sb.append('/');
            sb.append(enc(part));
        }
        return sb.toString();
    }
    private static String formatBytes(long bytes){
        if(bytes<1024)return bytes+" B";
        if(bytes<1024*1024)return String.format(Locale.US,"%.1f KB",bytes/1024.0);
        if(bytes<1024L*1024*1024)return String.format(Locale.US,"%.1f MB",bytes/1048576.0);
        return String.format(Locale.US,"%.2f GB",bytes/1073741824.0);
    }
    private static String shortError(Throwable e){
        String s=e.getMessage();if(s==null)s=e.toString();
        return s.length()>320?s.substring(0,320):s;
    }
    private void setStatus(String a,String b){
        runOnUiThread(()->{status.setText(a);detail.setText(b);});
    }
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable round(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private Button button(String s,boolean important){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);
        b.setTextColor(important?BG:GREEN);b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackground(round(important?GREEN:0xff344350,10));return b;
    }
    private void add(LinearLayout p,View v,int height,int margin){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,height<0?height:dp(height));
        lp.topMargin=dp(margin);p.addView(v,lp);
    }
    @Override protected void onStop(){
        if(downloading)cancel.set(true);
        super.onStop();
    }
    @Override protected void onDestroy(){
        cancel.set(true);
        worker.shutdownNow();
        super.onDestroy();
    }
}
