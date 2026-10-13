package com.keith.ethernettools;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only interactive SSH for Cisco IOS/IOS-XE. Host key is explicitly confirmed and remembered. */
public final class CiscoSsh implements AutoCloseable {
    public interface Listener {void event(String message);}
    private final Activity activity;
    private final Listener listener;
    private Session session;
    private ChannelShell shell;
    private InputStream in;
    private OutputStream out;
    private volatile boolean closed=false;
    private static final int MAX_OUTPUT=1500000;
    public CiscoSsh(Activity activity,Listener listener){this.activity=activity;this.listener=listener;}
    private void log(String s){if(listener!=null)listener.event(s);}
    public void connect(String host,String username,String password,String enable) throws Exception {
        if(host==null||!host.trim().matches("[A-Za-z0-9._:-]{1,250}"))throw new IllegalArgumentException("Enter a valid switch IP or hostname");
        if(username==null||username.trim().isEmpty()||password==null||password.isEmpty())throw new IllegalArgumentException("Cisco username and password required");
        JSch jsch=new JSch();
        File knownHosts=new File(activity.getFilesDir(),"cisco_known_hosts");
        if(!knownHosts.exists()&&!knownHosts.createNewFile())throw new IOException("Cannot create trusted-host file");
        jsch.setKnownHosts(knownHosts.getAbsolutePath());
        session=jsch.getSession(username.trim(),host.trim(),22);
        session.setPassword(password);
        session.setUserInfo(new TrustPrompt(password));
        Properties cfg=new Properties();
        cfg.setProperty("StrictHostKeyChecking","ask");
        cfg.setProperty("PreferredAuthentications","password,keyboard-interactive");
        session.setConfig(cfg);
        log("SSH connecting to "+host+":22");
        session.connect(10000);
        shell=(ChannelShell)session.openChannel("shell");
        shell.setPty(true);
        shell.setPtyType("vt100");
        shell.setPtySize(180,50,1200,900);
        in=shell.getInputStream();out=shell.getOutputStream();
        shell.connect(8000);
        log("SSH authenticated; interactive Cisco CLI connected");
        String banner=readUntilQuiet(5500,900);
        if(banner.matches("(?is).*press (?:return|enter) to get started.*")){
            send("\r");banner+=readUntilQuiet(4000,800);
        }
        if(banner.trim().endsWith(">")){
            if(enable!=null&&!enable.isEmpty()){
                send("enable\r");
                String response=readUntilQuiet(3500,650);
                if(response.toLowerCase(Locale.ROOT).contains("password"))send(enable+"\r");
                else log("Enable command returned no password prompt");
                banner=readUntilQuiet(3500,650);
            }else log("Switch appears to be in user EXEC mode (>). Privileged configuration queries may be denied.");
        }
        String r=run("terminal length 0",5000);
        if(r.toLowerCase(Locale.ROOT).matches("(?s).*(?:invalid input|unknown command).*"))log("Warning: terminal length 0 unsupported; paging may affect longer reports");
        run("terminal width 180",4000);
    }
    private void send(String value)throws IOException {
        if(closed||shell==null||!shell.isConnected())throw new IOException("SSH session closed");
        out.write(value.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
    private String readUntilQuiet(int maxMillis,int quietMillis)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];
        long start=System.currentTimeMillis(),last=start;
        boolean received=false,truncated=false;
        while(!closed&&shell!=null&&shell.isConnected()&&System.currentTimeMillis()-start<maxMillis){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("SSH operation stopped");
            int available=in.available();
            if(available>0){
                int n=in.read(buffer,0,Math.min(available,buffer.length));
                if(n<0)break;
                if(n>0){
                    received=true;last=System.currentTimeMillis();
                    if(bytes.size()+n<=MAX_OUTPUT){bytes.write(buffer,0,n);}
                    else {truncated=true;break;}
                    String tail=new String(buffer,0,n,StandardCharsets.ISO_8859_1);
                    if(tail.contains("--More--")||tail.contains("-- More --"))send(" ");
                }
            }else if(received&&System.currentTimeMillis()-last>quietMillis)break;
            else Thread.sleep(70);
        }
        String outText=bytes.toString("ISO-8859-1").replace("\u0008","")
            .replace("--More--","").replace("-- More --","");
        if(truncated)outText+="\n[OUTPUT TRUNCATED: exceeded 1.5 MB per command]\n";
        return outText;
    }
    public String run(String command,int timeout)throws Exception{
        if(command==null||!command.matches("[\\x20-\\x7e]{1,250}"))throw new IllegalArgumentException("Invalid command");
        log("Cisco CLI: "+command);
        send(command+"\r");
        return CiscoPortParser.safeReport(readUntilQuiet(timeout,1000));
    }
    public Map<String,String> commands(List<String> commands)throws Exception{
        LinkedHashMap<String,String> result=new LinkedHashMap<>();
        for(String command:commands){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Operation stopped");
            try {result.put(command,run(command,14500));}
            catch(Exception e){result.put(command,"ERROR: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        }
        return result;
    }
    @Override public void close(){
        closed=true;
        if(shell!=null)try{shell.disconnect();}catch(Exception ignored){}
        if(session!=null)try{session.disconnect();}catch(Exception ignored){}
    }
    private final class TrustPrompt implements UserInfo, UIKeyboardInteractive{
        private final String password;
        TrustPrompt(String password){this.password=password;}
        @Override public String getPassword(){return password;}
        @Override public String getPassphrase(){return null;}
        @Override public boolean promptPassword(String m){return true;}
        @Override public boolean promptPassphrase(String m){return false;}
        @Override public void showMessage(String m){log("SSH notice: "+m);}
        @Override public boolean promptYesNo(String message){
            if(message!=null&&(message.toLowerCase(Locale.ROOT).contains("has changed")||message.toLowerCase(Locale.ROOT).contains("identification has changed"))){
                log("SSH host key changed: connection rejected. Verify the switch identity before changing trusted keys.");
                return false;
            }
            CountDownLatch latch=new CountDownLatch(1);
            AtomicBoolean accepted=new AtomicBoolean(false);
            new Handler(Looper.getMainLooper()).post(()->{
                if(activity.isFinishing()||activity.isDestroyed()){latch.countDown();return;}
                new AlertDialog.Builder(activity)
                    .setTitle("Verify Cisco SSH Host Key")
                    .setMessage((message==null?"Unknown SSH host key":message)+"\n\nAccept only if this fingerprint belongs to your switch. It will be remembered.")
                    .setPositiveButton("Trust this switch",(d,w)->{accepted.set(true);latch.countDown();})
                    .setNegativeButton("Cancel",(d,w)->latch.countDown())
                    .setOnCancelListener(d->latch.countDown())
                    .show();
            });
            try{latch.await(90,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
            return accepted.get();
        }
        @Override public String[] promptKeyboardInteractive(String destination,String name,String instruction,String[] prompt,boolean[] echo){
            String[] responses=new String[prompt.length];
            for(int i=0;i<prompt.length;i++){
                String p=prompt[i].toLowerCase(Locale.ROOT);
                if(p.contains("password"))responses[i]=password;
                else return null;
            }
            return responses;
        }
    }
}
