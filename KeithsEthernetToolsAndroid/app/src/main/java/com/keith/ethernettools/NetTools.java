package com.keith.ethernettools;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class NetTools {
    private NetTools() {}
    public static String resolve(String host) throws Exception { return InetAddress.getByName(host.trim()).getHostAddress(); }
    public static String reverse(String ip) {
        try { String s=InetAddress.getByName(ip).getCanonicalHostName(); return s.equals(ip)?"(no PTR)":s; }
        catch(Exception e) { return "(no PTR)"; }
    }
    public static String tcpTest(String host,int port,int timeoutMs) {
        try (Socket s=new Socket()) {
            long start=System.nanoTime(); s.connect(new InetSocketAddress(host,port),timeoutMs);
            return String.format(Locale.US,"OPEN %s:%d %.1f ms",host,port,(System.nanoTime()-start)/1e6);
        } catch(Exception e) { return "CLOSED/UNREACHABLE " + host + ":" + port + " (" + e.getMessage() + ")"; }
    }
    public static String ping(String ip) {
        try {
            Process p=new ProcessBuilder("ping","-c","1","-W","2",ip).redirectErrorStream(true).start();
            boolean done=p.waitFor(4,TimeUnit.SECONDS);
            if(!done){p.destroyForcibly(); return "TIMEOUT";}
            String o=readUpTo(p.getInputStream(),8192);
            if(p.exitValue()==0) {
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("time[=<]([0-9.]+)").matcher(o);
                return "UP"+(m.find()?" "+m.group(1)+" ms":"");
            }
            return o.toLowerCase(Locale.ROOT).contains("permission denied")?"ICMP BLOCKED":"DOWN/TIMEOUT";
        } catch(Exception e) {return "PING UNAVAILABLE: "+e.getMessage();}
    }
    public static String trace(String host, int hops) {
        StringBuilder result=new StringBuilder("Traceroute to "+host+" (ICMP TTL)\n");
        for(int ttl=1;ttl<=hops;ttl++) {
            try {
                Process p=new ProcessBuilder("ping","-c","1","-W","2","-t",Integer.toString(ttl),host).redirectErrorStream(true).start();
                if(!p.waitFor(4,TimeUnit.SECONDS)){p.destroyForcibly();result.append(ttl).append("  * timeout\n");continue;}
                String out=readUpTo(p.getInputStream(),8192);
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:From |from |bytes from )([0-9a-fA-F:.]+)").matcher(out);
                String addr=m.find()?m.group(1):"*";
                java.util.regex.Matcher tm=java.util.regex.Pattern.compile("time[=<]([0-9.]+)").matcher(out);
                result.append(ttl).append("  ").append(addr).append(tm.find()?"  "+tm.group(1)+" ms":"").append("\n");
                if(p.exitValue()==0)break;
                if(out.contains("not permitted")||out.contains("Permission denied")) {result.append("TTL command unavailable on this Android device\n");break;}
            }catch(Exception e){result.append("Trace unsupported: ").append(e.getMessage()).append("\n");break;}
        }
        return result.toString();
    }
    public static String readUpTo(InputStream in,int max) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[2048];int total=0,n;
        while(total<max && (n=in.read(buf,0,Math.min(buf.length,max-total)))!=-1){b.write(buf,0,n);total+=n;}
        return b.toString("UTF-8");
    }
}
