package com.keith.ethernettools;

import java.util.*;
import java.util.regex.*;
/** Cisco IOS/IOS-XE read-only parser. Never asserts a MAC-table uplink is a terminal access port. */
public final class CiscoPortParser {
    private CiscoPortParser() {}
    private static final Pattern MAC = Pattern.compile("(?i)(?<![0-9a-f])(?:[0-9a-f]{4}\\.){2}[0-9a-f]{4}(?![0-9a-f])|(?<![0-9a-f])[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}(?![0-9a-f])");
    private static final Pattern IPV4 = Pattern.compile("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
    private static final Pattern PORT = Pattern.compile("(?i)^(?:Gi|GigabitEthernet|Te|TenGigabitEthernet|Fa|FastEthernet|Eth|Ethernet|Twe|TwentyFiveGigE|Fo|FortyGigabitEthernet|Hu|HundredGigE|Po|Port-channel)[0-9][0-9A-Za-z/._:-]*$");
    private static final Pattern ARP = Pattern.compile("(?i)\\bInternet\\s+([0-9.]+)\\s+\\S+\\s+([0-9a-f:.\\-]+)\\s+");
    public static String macNormalize(String value) {
        String v=value==null?"":value.replaceAll("[^0-9a-fA-F]","").toLowerCase(Locale.US);
        if(!v.matches("[0-9a-f]{12}"))throw new IllegalArgumentException("Enter a valid 12-hex-digit MAC address");
        return v;
    }
    public static String dottedMac(String value) {
        String m=macNormalize(value);
        return m.substring(0,4)+"."+m.substring(4,8)+"."+m.substring(8,12);
    }
    public static boolean validInterface(String port) {
        return port!=null&&PORT.matcher(port.trim()).matches()&&port.length()<60;
    }
    public static boolean isLogicalUplink(String port) {
        return port!=null&&port.matches("(?i)(?:Po|Port-channel)[0-9].*");
    }
    public static boolean validIp(String ip) {
        if(ip==null||!IPV4.matcher(ip.trim()).matches())return false;
        String[] octets=ip.trim().split("\\.");
        for(String o:octets){try{int x=Integer.parseInt(o);if(x>255||x<0)return false;}catch(Exception ex){return false;}}
        return true;
    }
    public static String arpMac(String output, String ip) {
        if(!validIp(ip))throw new IllegalArgumentException("Invalid target IPv4 address");
        Matcher m=ARP.matcher(output==null?"":output);
        while(m.find())if(m.group(1).equals(ip)){
            try{return dottedMac(m.group(2));}catch(IllegalArgumentException ignored){}
        }
        return "";
    }
    public static final class Match {
        public final String vlan, mac, type, port;
        public Match(String vlan,String mac,String type,String port){
            this.vlan=vlan;this.mac=mac;this.type=type;this.port=port;
        }
        public String toString(){return "VLAN "+vlan+"  MAC "+mac+"  Type "+type+"  Port "+port;}
    }
    /** Accepts common Cisco output: VLAN MAC TYPE PORT; also optional leading '*'. */
    public static List<Match> findMac(String output,String targetMac) {
        String target=macNormalize(targetMac);
        ArrayList<Match> found=new ArrayList<>();
        if(output==null)return found;
        for(String line:output.split("\\r?\\n")){
            Matcher m=MAC.matcher(line);
            if(!m.find())continue;
            String candidate;
            try{candidate=macNormalize(m.group());}catch(IllegalArgumentException e){continue;}
            if(!candidate.equals(target))continue;
            String before=line.substring(0,m.start()).trim().replaceFirst("^\\*\\s*","");
            String after=line.substring(m.end()).trim();
            String[] left=before.split("\\s+"),right=after.split("\\s+");
            if(left.length<1||right.length<2)continue;
            String vlan=left[left.length-1],type=right[0],port=right[right.length-1];
            if(!vlan.matches("(?i)(?:[0-9]+|all)")||!type.matches("(?i)(?:dynamic|static|secure|self|system|drop|learn|permanent)"))continue;
            if(port.matches("(?i)(?:CPU|Router|Switch|Drop|All|N/A)"))continue;
            Match entry=new Match(vlan,dottedMac(target),type,port);
            boolean duplicate=false;
            for(Match previous:found)if(previous.port.equalsIgnoreCase(port)&&previous.vlan.equals(vlan)){duplicate=true;break;}
            if(!duplicate)found.add(entry);
        }
        return found;
    }
    public static String safeReport(String output){
        if(output==null)return "";
        String ansi=output.replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]","");
        StringBuilder b=new StringBuilder();
        for(String line:ansi.split("\\r?\\n",-1)){
            if(line.matches("(?i).*\\b(?:password|secret|snmp-server community|authentication-key|key-string|pre-shared-key)\\b.*")){
                b.append("[REDACTED POTENTIALLY SENSITIVE LINE]\n");
            }else b.append(line).append('\n');
        }
        return b.toString();
    }
}
