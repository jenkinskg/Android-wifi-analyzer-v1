package com.keith.ethernettools;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal Telnet NVT client for an ERS 5520's ANSI menu. */
public final class NortelTelnet implements AutoCloseable {
    public interface Listener { void event(String line); }
    private final Listener listener;
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private final AtomicBoolean active=new AtomicBoolean(false);
    private Thread reader;
    private volatile String recent="";
    private final Object writeLock=new Object();
    public NortelTelnet(Listener listener){this.listener=listener;}
    private void log(String s){listener.event(s);}
    public void connect(String host,int port)throws Exception {
        socket=new Socket();socket.connect(new InetSocketAddress(host,port),6000);socket.setSoTimeout(600);
        input=socket.getInputStream();output=socket.getOutputStream();active.set(true);
        log("Connected to "+host+":"+port+" (Telnet is unencrypted)");
        reader=new Thread(this::receiveLoop,"nortel-telnet-reader"); reader.setDaemon(true);reader.start();
    }
    private void receiveLoop(){
        ByteArrayOutputStream printable=new ByteArrayOutputStream();
        try {
            while(active.get()){
                int b;
                try {b=input.read();}catch(SocketTimeoutException e){flush(printable);continue;}
                if(b<0)break;
                if(b==255){flush(printable);handleIac();continue;}
                printable.write(b);if(b==10 || printable.size()>2048)flush(printable);
            }
        }catch(Exception e){if(active.get())log("Connection ended: "+e.getMessage());}
        finally {flush(printable);active.set(false); log("Telnet session closed");}
    }
    private void flush(ByteArrayOutputStream buf){
        if(buf.size()==0)return;
        String s=new String(buf.toByteArray(),StandardCharsets.ISO_8859_1);buf.reset();
        String safe=s.replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]", "")
            .replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "");
        if(!safe.trim().isEmpty())log("RX "+safe);
        recent=(recent+safe).replace('\r',' ');if(recent.length()>3500)recent=recent.substring(recent.length()-3500);
    }
    private void handleIac()throws IOException{
        int verb=input.read();if(verb<0)return;
        if(verb==255)return;
        if(verb==250){int previous=0;while(true){int n=input.read();if(n<0)break;if(previous==255&&n==240)break;previous=n;}return;}
        if(verb>=251&&verb<=254){int opt=input.read();if(opt<0)return;
            byte response=(byte)((verb==251||verb==252)?254:252);
            synchronized(writeLock){output.write(new byte[]{(byte)255,response,(byte)opt});output.flush();}
        }
    }
    public void type(String s)throws IOException{ synchronized(writeLock){output.write(s.getBytes(StandardCharsets.ISO_8859_1));output.flush();} }
    public void enter()throws IOException{ type("\r"); }
    public void ctrlY()throws IOException{type("\u0019");log("TX Ctrl+Y");}
    public void menuCli()throws IOException{type("c\r");log("TX C (Command Line Interface)");}
    public void submitUser(String username)throws IOException{type(username+"\r");log("TX username: "+username);}
    public void submitPassword(String password)throws IOException{type(password+"\r");log("TX password: [REDACTED]");}
    public void command(String command)throws IOException{
        if(!command.matches("[\\x20-\\x7e]{1,250}"))throw new IOException("Unsupported command characters");
        type(command+"\r");log("TX command: "+command);
    }
    public boolean connected(){return active.get();}
    public String recent(){return recent;}
    public void close(){active.set(false);try{if(socket!=null)socket.close();}catch(IOException ignored){}}
}
