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
    private Button convertBtn,cancelBtn,shareBtn,playBtn;
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
                update("Ready","Paste a link, then tap Convert.");
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
        TextView qual=label("MP3 QUALITY",12,0xffb5c6ce,true);qual.setPadding(0,dp(16),0,0);box.addView(qual);
        quality=new Spinner(this);
        String[] choices={"128 kbps","192 kbps","320 kbps"};
        quality.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,choices));
        quality.setSelection(1);add(box,quality,51,3);
        convertBtn=button("CONVERT TO MP3",true);add(box,convertBtn,58,10);
        convertBtn.setOnClickListener(v->startConvert());
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
        TextView foot=label("Only download audio you own or have permission to save. Some sites may block downloads.",12,0xff9aaab9,false);
        foot.setPadding(0,dp(22),0,0);root.addView(foot);
    }
    private void startConvert(){
        if(running)return;
        String url=findLink(urlField.getText().toString());
        if(!url.startsWith("https://")&&!url.startsWith("http://")){
            urlField.setError("Enter a complete URL");return;
        }
        running=true;cancelled.set(false);lastSaved=null;
        bar.setProgress(0);convertBtn.setEnabled(false);cancelBtn.setEnabled(true);
        playBtn.setEnabled(false);shareBtn.setEnabled(false);
        String rate=new String[]{"128K","192K","320K"}[quality.getSelectedItemPosition()];
        update("Downloading audio…",url);
        executor.execute(()->doConvert(url,rate));
    }
    private void doConvert(String url,String rate){
        File working=null;
        try{
            YoutubeDL.getInstance().init(getApplicationContext());
            FFmpeg.getInstance().init(getApplicationContext());
            File root=getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if(root==null)throw new IOException("Storage not available");
            working=new File(root,"working-"+UUID.randomUUID());
            if(!working.mkdirs())throw new IOException("Cannot create temp folder");
            jobId="mp3-"+UUID.randomUUID();
            YoutubeDLRequest req=new YoutubeDLRequest(url);
            req.addOption("--no-playlist");
            req.addOption("--extract-audio");
            req.addOption("--audio-format","mp3");
            req.addOption("--audio-quality",rate);
            req.addOption("-f","bestaudio/best");
            req.addOption("-o",new File(working,"%(title).100B-%(id)s.%(ext)s").getAbsolutePath());
            YoutubeDL.getInstance().execute(req,jobId,new Function3<Float,Long,String,Unit>(){
                @Override public Unit invoke(Float percent,Long eta,String line){
                    if(percent!=null)main.post(()->bar.setProgress(Math.min(94,Math.max(0,percent.intValue()))));
                    if(line!=null&&!line.isEmpty()){
                        final String txt=line.length()>240?line.substring(0,240):line;
                        main.post(()->{if(!cancelled.get())detail.setText(txt);});
                    }
                    return Unit.INSTANCE;
                }
            });
            if(cancelled.get())throw new IOException("Cancelled");
            File[] mp3s=working.listFiles((dir,name)->name.toLowerCase(Locale.ROOT).endsWith(".mp3"));
            if(mp3s==null||mp3s.length==0)throw new IOException("No MP3 was generated for this link");
            update("Saving MP3…",mp3s[0].getName());
            final String filename=mp3s[0].getName();
            Uri saved=save(mp3s[0]);lastSaved=saved;
            main.post(()->{
                bar.setProgress(100);
                update("MP3 saved!","Music / URL to MP3 / "+filename);
                playBtn.setEnabled(true);shareBtn.setEnabled(true);
                Toast.makeText(this,"MP3 saved in Music",Toast.LENGTH_LONG).show();
            });
        }catch(Exception e){update("Conversion failed",cancelled.get()?"Cancelled":err(e));}
        finally{
            jobId=null;
            if(working!=null)remove(working);
            running=false;
            main.post(()->{convertBtn.setEnabled(true);cancelBtn.setEnabled(false);});
        }
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
