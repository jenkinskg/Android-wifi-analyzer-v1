package com.jenkinskg.urltomp3;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.ffmpeg.FFmpeg;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.json.JSONArray;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;

public class MainActivity extends Activity {
    private final int back=Color.rgb(12,18,25), panel=Color.rgb(28,39,50), accent=Color.rgb(63,247,170);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private EditText urlField;
    private Spinner quality;
    private ProgressBar bar;
    private Button convertBtn,cancelBtn,shareBtn,playBtn,searchBtn,updateBtn,selectAllBtn,convertSelectedBtn;
    private EditText searchField;
    private LinearLayout searchResults;
    private final ArrayList<VideoChoice> choices=new ArrayList<>();
    private volatile boolean searching=false;
    private boolean selectAllNext=true;
    private static final class VideoChoice {
        final String url, title;
        final CheckBox check;
        VideoChoice(String url,String title,CheckBox check){this.url=url;this.title=title;this.check=check;}
    }
    private TextView status,detail;
    private volatile boolean running=false;
    private volatile String jobId=null;
    private Uri lastSaved=null;

    public void onCreate(Bundle state) {
        super.onCreate(state);
        drawUI();
        if(Intent.ACTION_SEND.equals(getIntent().getAction())){
            String link=getIntent().getStringExtra(Intent.EXTRA_TEXT);
            if(link!=null)urlField.setText(findLink(link));
        }
        executor.execute(()->{
            try{
                YoutubeDL.getInstance().init(getApplicationContext());
                FFmpeg.getInstance().init(getApplicationContext());
                // Try to get a current extractor automatically; failures leave bundled yt-dlp usable.
                try {
                    update("Checking yt-dlp updates…","Connecting to the official yt-dlp release server");
                    YoutubeDL.getInstance().updateYoutubeDL(getApplicationContext(), YoutubeDL.UpdateChannel.STABLE.INSTANCE);
                    update("Ready","Downloader updated. Search for music or paste a link.");
                } catch(Exception updateError) {
                    update("Ready","Auto-update unavailable. Tap UPDATE DOWNLOADER to retry.");
                }
            }catch(Exception e){update("Engine error",err(e));}
        });
    }
    private void drawUI() {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);scroll.setBackgroundColor(back);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);
        root.setPadding(dp(20),dp(25),dp(20),dp(25));
        scroll.addView(root);setContentView(scroll);
        root.addView(label("AUDIO CONVERTER / ANDROID",12,accent,true));
        TextView head=label("URL → MP3",34,Color.WHITE,true);
        head.setPadding(0,dp(8),0,dp(8));root.addView(head);
        TextView intro=label("Convert supported video links into real MP3 audio files. No Termux required.",15,0xffb5c6ce,false);
        intro.setPadding(0,0,0,dp(18));root.addView(intro);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(14),dp(15),dp(14),dp(15));
        box.setBackground(bg(panel,16));root.addView(box);
        box.addView(label("PASTE VIDEO URL",12,0xffb5c6ce,true));
        urlField=new EditText(this);urlField.setTextSize(15);urlField.setTextColor(Color.WHITE);
        urlField.setHintTextColor(0xffacb7c5);
        urlField.setHint("https://youtu.be/...");urlField.setMinLines(2);urlField.setMaxLines(4);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        urlField.setBackground(bg(0xff354452,10));urlField.setPadding(dp(12),dp(10),dp(12),dp(10));
        add(box,urlField,76,10);
        Button paste=button("PASTE LINK",false);add(box,paste,45,10);
        paste.setOnClickListener(v->{
            ClipboardManager cb=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data=cb.getPrimaryClip();
            if(data!=null&&data.getItemCount()>0)urlField.setText(findLink(data.getItemAt(0).coerceToText(this).toString()));
            else Toast.makeText(this,"Clipboard empty",Toast.LENGTH_SHORT).show();
        });
        TextView sh=label("SEARCH YOUTUBE",13,accent,true);
        sh.setPadding(0,dp(16),0,dp(5));box.addView(sh);
        searchField=new EditText(this);
        searchField.setTextSize(15);searchField.setTextColor(Color.WHITE);
        searchField.setHintTextColor(0xffa8b8c7);
        searchField.setSingleLine(true);
        searchField.setHint("Band, artist or song name");
        searchField.setBackground(bg(0xff354452,10));
        searchField.setPadding(dp(12),dp(10),dp(12),dp(10));add(box,searchField,52,6);
        searchBtn=button("SEARCH FOR SONGS",false);add(box,searchBtn,48,8);
        searchBtn.setOnClickListener(v->searchVideos());
        searchResults=new LinearLayout(this);
        searchResults.setOrientation(1);
        box.addView(searchResults);
        LinearLayout rowBtns=new LinearLayout(this);rowBtns.setOrientation(LinearLayout.HORIZONTAL);
        selectAllBtn=button("SELECT ALL",false);
        rowBtns.addView(selectAllBtn,new LinearLayout.LayoutParams(0,dp(48),1));
        selectAllBtn.setOnClickListener(v->{
            for(VideoChoice c:choices)c.check.setChecked(selectAllNext);
            selectAllNext=!selectAllNext;
            selectAllBtn.setText(selectAllNext?"SELECT ALL":"DESELECT ALL");
        });
        convertSelectedBtn=button("CONVERT SELECTED",true);
        LinearLayout.LayoutParams cbp=new LinearLayout.LayoutParams(0,dp(48),1.5f);
        cbp.leftMargin=dp(8);rowBtns.addView(convertSelectedBtn,cbp);
        convertSelectedBtn.setOnClickListener(v->startSelected());
        add(box,rowBtns,52,10);rowBtns.setVisibility(View.GONE);
        rowBtns.setTag("results-buttons");
        TextView qual=label("MP3 QUALITY",12,0xffb5c6ce,true);qual.setPadding(0,dp(16),0,0);box.addView(qual);
        quality=new Spinner(this);
        String[] choices={"128 kbps","192 kbps","320 kbps"};
        quality.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,choices));
        quality.setSelection(1);add(box,quality,51,3);
        convertBtn=button("CONVERT TO MP3",true);add(box,convertBtn,58,10);
        convertBtn.setOnClickListener(v->startConvert());
        updateBtn=button("UPDATE DOWNLOADER",false);add(box,updateBtn,43,8);
        updateBtn.setOnClickListener(v->updateDownloader());
        cancelBtn=button("CANCEL",false);add(box,cancelBtn,45,8);
        cancelBtn.setEnabled(false);
        cancelBtn.setOnClickListener(v->{
            cancelled.set(true);String id=jobId;
            if(id!=null)new Thread(()->{
                try{YoutubeDL.getInstance().destroyProcessById(id);}catch(Exception ignored){}
            },"cancel").start();
            update("Cancelling","Stopping current conversion…");
        });
        bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);bar.setProgressTintList(ColorStateList.valueOf(accent));add(root,bar,10,22);
        status=label("Starting converter…",19,Color.WHITE,true);add(root,status,40,7);
        detail=label("MP3s save to Music / URL to MP3",13,0xffafc0cb,false);
        root.addView(detail);
        playBtn=button("PLAY LAST MP3",false);add(root,playBtn,47,20);
        playBtn.setEnabled(false);playBtn.setOnClickListener(v->{
            if(lastSaved==null)return;
            Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(lastSaved,"audio/mpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try{startActivity(i);}catch(Exception e){Toast.makeText(this,"No audio player installed",Toast.LENGTH_SHORT).show();}
        });
        shareBtn=button("SHARE LAST MP3",false);add(root,shareBtn,47,8);
        shareBtn.setEnabled(false);shareBtn.setOnClickListener(v->{
            if(lastSaved==null)return;
            Intent i=new Intent(Intent.ACTION_SEND);i.setType("audio/mpeg");
            i.putExtra(Intent.EXTRA_STREAM,lastSaved);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,"Share MP3"));
        });
        TextView foot=label("Search results come from YouTube. Download content only if you have permission. YouTube may restrict some tracks.",12,0xff9aaab9,false);
        foot.setPadding(0,dp(22),0,0);root.addView(foot);
    }
    private void updateDownloader(){
        if(running||searching){Toast.makeText(this,"Finish current task first",Toast.LENGTH_SHORT).show();return;}
        updateBtn.setEnabled(false);
        update("Updating downloader…","Checking the official yt-dlp stable release");
        executor.execute(()->{
            try{
                YoutubeDL.getInstance().init(getApplicationContext());
                YoutubeDL.UpdateStatus result=YoutubeDL.getInstance().updateYoutubeDL(
                    getApplicationContext(),YoutubeDL.UpdateChannel.STABLE.INSTANCE);
                update("Downloader ready",result==YoutubeDL.UpdateStatus.DONE?
                    "Updated successfully. Retry the song.":"Already up to date. Retry your download.");
            }catch(Exception e){update("Update failed",shortError(e));}
            finally{main.post(()->updateBtn.setEnabled(true));}
        });
    }
    private void searchVideos(){
        if(running||searching)return;
        String query=searchField.getText().toString().trim();
        if(query.length()<2){searchField.setError("Enter a band or song");return;}
        searching=true;searchBtn.setEnabled(false);convertSelectedBtn.setEnabled(false);
        searchResults.removeAllViews();choices.clear();
        update("Searching YouTube…","Looking for: "+query);
        executor.execute(()->{
            try{
                YoutubeDL.getInstance().init(getApplicationContext());
                YoutubeDLRequest req=new YoutubeDLRequest("ytsearch15:"+query);
                req.addOption("--flat-playlist");
                req.addOption("--dump-single-json");
                req.addOption("--skip-download");
                req.addOption("--no-warnings");
                String response=YoutubeDL.getInstance().execute(req,null,null).getOut();
                JSONObject json=new JSONObject(response.trim());
                JSONArray entries=json.optJSONArray("entries");
                ArrayList<String[]> found=new ArrayList<>();
                if(entries!=null)for(int i=0;i<entries.length();i++){
                    JSONObject item=entries.optJSONObject(i);
                    if(item==null)continue;
                    String id=item.optString("id","");
                    String url=item.optString("webpage_url","");
                    if(id.matches("[a-zA-Z0-9_-]{11}"))url="https://www.youtube.com/watch?v="+id;
                    if(!url.startsWith("https://"))continue;
                    String title=item.optString("title","Untitled video");
                    String creator=item.optString("channel",item.optString("uploader",""));
                    found.add(new String[]{url,title,creator});
                }
                main.post(()->showSearchResults(found));
                update(found.isEmpty()?"No matching videos":"Search complete",
                    found.size()+" matches. Check the songs you want, then tap CONVERT SELECTED.");
            }catch(Exception e){
                update("Search failed",shortError(e)+" — Try UPDATE DOWNLOADER, then search again.");
            }finally{
                searching=false;
                main.post(()->{searchBtn.setEnabled(true);convertSelectedBtn.setEnabled(true);});
            }
        });
    }
    private void showSearchResults(ArrayList<String[]> found){
        searchResults.removeAllViews();choices.clear();selectAllNext=true;
        selectAllBtn.setText("SELECT ALL");
        for(String[] entry:found){
            CheckBox ck=new CheckBox(this);
            ck.setButtonTintList(ColorStateList.valueOf(accent));
            ck.setTextColor(Color.WHITE);ck.setTextSize(14);
            ck.setPadding(dp(2),dp(9),dp(2),dp(9));
            String creator=entry[2].isEmpty()?"":"\n"+entry[2];
            ck.setText(entry[1]+creator);
            searchResults.addView(ck,new LinearLayout.LayoutParams(-1,-2));
            choices.add(new VideoChoice(entry[0],entry[1],ck));
        }
        View b=((View)searchResults.getParent()).findViewWithTag("results-buttons");
        if(b!=null)b.setVisibility(found.isEmpty()?View.GONE:View.VISIBLE);
    }
    private void startSelected(){
        if(running||searching)return;
        ArrayList<String> urls=new ArrayList<>();
        for(VideoChoice c:choices)if(c.check.isChecked())urls.add(c.url);
        if(urls.isEmpty()){
            Toast.makeText(this,"Check at least one song",Toast.LENGTH_LONG).show();
            return;
        }
        startBatch(urls);
    }
    private void startConvert(){
        if(running||searching)return;
        String url=findLink(urlField.getText().toString());
        if(!url.startsWith("https://")&&!url.startsWith("http://")){
            urlField.setError("Enter a complete URL");return;
        }
        ArrayList<String> urls=new ArrayList<>();urls.add(url);
        startBatch(urls);
    }
    private void startBatch(ArrayList<String> urls){
        if(running||searching)return;
        running=true;cancelled.set(false);lastSaved=null;
        bar.setProgress(0);convertBtn.setEnabled(false);
        convertSelectedBtn.setEnabled(false);searchBtn.setEnabled(false);
        updateBtn.setEnabled(false);cancelBtn.setEnabled(true);
        playBtn.setEnabled(false);shareBtn.setEnabled(false);
        String rate=new String[]{"128K","192K","320K"}[quality.getSelectedItemPosition()];
        executor.execute(()->doConvertBatch(urls,rate));
    }
    private void doConvertBatch(ArrayList<String> urls,String rate){
        int success=0;int failed=0;String lastProblem="";
        try{
            YoutubeDL.getInstance().init(getApplicationContext());
            FFmpeg.getInstance().init(getApplicationContext());
            for(int i=0;i<urls.size();i++){
                if(cancelled.get())break;
                final int number=i+1;
                File working=null;
                try{
                    update("Converting "+number+" of "+urls.size(),"Requesting the audio stream…");
                    main.post(()->bar.setProgress(0));
                    File root=getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                    if(root==null)throw new IOException("Storage not available");
                    working=new File(root,"working-"+UUID.randomUUID());
                    if(!working.mkdirs())throw new IOException("Cannot create temp folder");
                    jobId="mp3-"+UUID.randomUUID();
                    YoutubeDLRequest req=new YoutubeDLRequest(urls.get(i));
                    req.addOption("--no-playlist");
                    req.addOption("--extract-audio");
                    req.addOption("--audio-format","mp3");
                    req.addOption("--audio-quality",rate);
                    req.addOption("-f","bestaudio/best");
                    req.addOption("--no-warnings");
                    req.addOption("--no-mtime");
                    req.addOption("-o",new File(working,"%(title).100B-%(id)s.%(ext)s").getAbsolutePath());
                    YoutubeDL.getInstance().execute(req,jobId,new Function3<Float,Long,String,Unit>(){
                        @Override public Unit invoke(Float percent,Long eta,String line){
                            if(percent!=null)main.post(()->bar.setProgress(Math.min(96,Math.max(0,percent.intValue()))));
                            if(line!=null&&!line.isEmpty()){
                                final String msg=line.length()>180?line.substring(0,180):line;
                                main.post(()->{if(!cancelled.get())detail.setText(number+"/"+urls.size()+" • "+msg);});
                            }
                            return Unit.INSTANCE;
                        }
                    });
                    if(cancelled.get())break;
                    File[] mp3s=working.listFiles((dir,name)->name.toLowerCase(Locale.ROOT).endsWith(".mp3"));
                    if(mp3s==null||mp3s.length==0)throw new IOException("No MP3 was generated");
                    update("Saving "+number+" of "+urls.size(),mp3s[0].getName());
                    Uri saved=save(mp3s[0]);lastSaved=saved;
                    success++;
                    final String filename=mp3s[0].getName();
                    main.post(()->{
                        playBtn.setEnabled(true);shareBtn.setEnabled(true);
                        detail.setText("Saved: "+filename);
                    });
                }catch(Exception e){
                    if(cancelled.get())break;
                    failed++;lastProblem=shortError(e);
                    update("Failed: "+number+" of "+urls.size(),lastProblem);
                }finally{
                    jobId=null;
                    if(working!=null)remove(working);
                }
            }
            String summary=success+" MP3 saved, "+failed+" failed";
            if(cancelled.get())update("Conversion cancelled",summary);
            else if(failed>0)update("Some conversions failed",summary+"\n"+lastProblem);
            else update("Conversion complete",summary+" — Music / URL to MP3");
            if(success>0)main.post(()->bar.setProgress(100));
        }finally{
            running=false;
            main.post(()->{
                convertBtn.setEnabled(true);convertSelectedBtn.setEnabled(true);
                searchBtn.setEnabled(true);updateBtn.setEnabled(true);
                cancelBtn.setEnabled(false);
            });
        }
    }
    private String shortError(Exception e){
        String msg=err(e);
        if(msg.contains("HTTP Error 403")||msg.contains("403: Forbidden"))
            return "YouTube blocked this audio stream (HTTP 403). Tap UPDATE DOWNLOADER and retry. Some videos may remain restricted.";
        if(msg.contains("Sign in")||msg.contains("login required"))
            return "This video requires sign-in or is restricted.";
        if(msg.length()>450)return msg.substring(msg.length()-450);
        return msg;
    }
    private Uri save(File file)throws IOException{
        ContentValues val=new ContentValues();
        val.put(MediaStore.Audio.Media.DISPLAY_NAME,file.getName());
        val.put(MediaStore.Audio.Media.MIME_TYPE,"audio/mpeg");
        val.put(MediaStore.Audio.Media.RELATIVE_PATH,Environment.DIRECTORY_MUSIC+"/URL to MP3");
        val.put(MediaStore.Audio.Media.IS_PENDING,1);
        Uri target=getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,val);
        if(target==null)throw new IOException("Cannot create media file");
        boolean copied=false;
        try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(target)){
            if(out==null)throw new IOException("Media output unavailable");
            byte[] b=new byte[65536];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
            copied=true;
        }finally{if(!copied)getContentResolver().delete(target,null,null);}
        val.clear();val.put(MediaStore.Audio.Media.IS_PENDING,0);
        getContentResolver().update(target,val,null,null);
        return target;
    }
    private static void remove(File file){
        File[] entries=file.listFiles();if(entries!=null)for(File f:entries)remove(f);
        file.delete();
    }
    private String findLink(String raw){
        for(String part:raw.replace('\n',' ').split("\\s+"))
            if(part.startsWith("https://")||part.startsWith("http://"))return part;
        return raw.trim();
    }
    private void update(String title,String description){
        main.post(()->{if(status!=null)status.setText(title);if(detail!=null)detail.setText(description);});
    }
    private static String err(Exception e){return e.getMessage()!=null?e.getMessage():e.toString();}
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private GradientDrawable bg(int col,int round){GradientDrawable d=new GradientDrawable();d.setColor(col);d.setCornerRadius(dp(round));return d;}
    private TextView label(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);
        t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private Button button(String value,boolean primary){
        Button b=new Button(this);b.setAllCaps(false);b.setText(value);b.setTextSize(15);
        b.setTextColor(primary?back:accent);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackground(bg(primary?accent:0xff354452,10));return b;
    }
    private void add(LinearLayout parent,View item,int height,int margin){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(height));lp.topMargin=dp(margin);parent.addView(item,lp);
    }
}
