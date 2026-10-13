package com.keith.ethernettools;

import java.util.*;
import java.util.regex.*;

/**
 * Converts Cisco IOS/IOS-XE SSH responses into visible VLAN summaries.
 * If a field is not advertised, say "(not reported)" instead of guessing.
 */
public final class CiscoVlanReport {
    private CiscoVlanReport(){}
    public static final String UNAVAILABLE="(not reported)";
    private static final Pattern SWITCHPORT_START = Pattern.compile("(?im)^\\s*Name:\\s*(\\S+)\\s*$");
    private static final Pattern VLAN_ROW = Pattern.compile("^\\s*(\\d{1,4})\\s+(\\S+)\\s+(active|act/lshut|suspended|shutdown|act/unsup)\\b\\s*(.*)$",Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_ROW=Pattern.compile(
        "(?i)^\\s*((?:Gi|GigabitEthernet|Fa|FastEthernet|Te|TenGigabitEthernet|Eth|Ethernet|Po|Port-channel|Twe|TwentyFiveGigE|Fo|FortyGigabitEthernet|Hu|HundredGigE)[0-9][0-9A-Za-z/._:-]*)\\s+.*?\\b(connected|notconnect|err-disabled|disabled|inactive|sfpAbsent|monitoring|suspended|notpresent)\\s+(\\S+)");
    public static final class PortDetails {
        public String name,link=UNAVAILABLE,statusVlan=UNAVAILABLE,adminMode=UNAVAILABLE,mode=UNAVAILABLE;
        public String access=UNAVAILABLE,voice=UNAVAILABLE,nativeVlan=UNAVAILABLE,trunkAllowed=UNAVAILABLE,activeTrunkVlans=UNAVAILABLE;
        public String configAccess=UNAVAILABLE,configVoice=UNAVAILABLE,configNative=UNAVAILABLE,configAllowed=UNAVAILABLE;
        public PortDetails(String name){this.name=name;}
        public String accessValue(){return !access.equals(UNAVAILABLE)?access:configAccess;}
        public String voiceValue(){return !voice.equals(UNAVAILABLE)?voice:configVoice;}
        public String nativeValue(){return !nativeVlan.equals(UNAVAILABLE)?nativeVlan:configNative;}
        public String allowedValue(){return !trunkAllowed.equals(UNAVAILABLE)?trunkAllowed:configAllowed;}
    }
    public static String normalizePort(String value){
        if(value==null)return "";
        return value.trim().replaceFirst("(?i)^GigabitEthernet","Gi").replaceFirst("(?i)^FastEthernet","Fa")
            .replaceFirst("(?i)^TenGigabitEthernet","Te").replaceFirst("(?i)^Port-channel","Po")
            .replaceFirst("(?i)^TwentyFiveGigE","Twe").replaceFirst("(?i)^FortyGigabitEthernet","Fo")
            .replaceFirst("(?i)^HundredGigE","Hu").replaceFirst("(?i)^Ethernet","Eth");
    }
    private static String extract(String text,String field){
        if(text==null)return UNAVAILABLE;
        Matcher matcher=Pattern.compile("(?im)^\\s*"+Pattern.quote(field)+"\\s*:\\s*(.*?)\\s*$").matcher(text);
        return matcher.find()&&!matcher.group(1).trim().isEmpty()?matcher.group(1).trim():UNAVAILABLE;
    }
    private static void fillSwitchport(PortDetails d,String text){
        d.adminMode=extract(text,"Administrative Mode");
        d.mode=extract(text,"Operational Mode");
        d.access=extract(text,"Access Mode VLAN");
        d.voice=extract(text,"Voice VLAN");
        d.nativeVlan=extract(text,"Trunking Native Mode VLAN");
        d.trunkAllowed=extract(text,"Trunking VLANs Enabled");
        d.activeTrunkVlans=extract(text,"Trunking VLANs Active");
        // IOS-XE variants sometimes write "Trunking VLANs Active" on a later line.
    }
    private static void fillRunning(PortDetails d,String text){
        if(text==null)return;
        d.configAccess=extractCommand(text,"switchport access vlan");
        d.configVoice=extractCommand(text,"switchport voice vlan");
        d.configNative=extractCommand(text,"switchport trunk native vlan");
        d.configAllowed=extractCommand(text,"switchport trunk allowed vlan");
        if(d.mode.equals(UNAVAILABLE)){
            String configured=extractCommand(text,"switchport mode");
            if(!configured.equals(UNAVAILABLE))d.mode=configured+" (configured; operational mode unavailable)";
        }
    }
    private static String extractCommand(String text,String command){
        Matcher m=Pattern.compile("(?im)^\\s*"+Pattern.quote(command)+"\\s+([^\\r\\n]+)").matcher(text);
        return m.find()?m.group(1).trim():UNAVAILABLE;
    }
    public static LinkedHashMap<String,PortDetails> parseSwitchports(String output){
        LinkedHashMap<String,PortDetails> details=new LinkedHashMap<>();
        if(output==null)return details;
        Matcher matcher=SWITCHPORT_START.matcher(output);
        ArrayList<Integer> starts=new ArrayList<>();
        ArrayList<String> names=new ArrayList<>();
        while(matcher.find()){starts.add(matcher.start());names.add(normalizePort(matcher.group(1)));}
        for(int i=0;i<starts.size();i++){
            String name=names.get(i);
            String block=output.substring(starts.get(i),i+1<starts.size()?starts.get(i+1):output.length());
            PortDetails d=new PortDetails(name);
            fillSwitchport(d,block);
            details.put(name.toLowerCase(Locale.US),d);
        }
        return details;
    }
    public static LinkedHashMap<String,PortDetails> parseStatuses(String output){
        LinkedHashMap<String,PortDetails> details=new LinkedHashMap<>();
        if(output==null)return details;
        for(String line:output.split("\\r?\\n")){
            Matcher m=STATUS_ROW.matcher(line);
            if(!m.find())continue;
            PortDetails d=new PortDetails(normalizePort(m.group(1)));
            d.link=m.group(2);d.statusVlan=m.group(3);
            details.put(d.name.toLowerCase(Locale.US),d);
        }
        return details;
    }
    private static LinkedHashMap<String,PortDetails> merge(String status,String switchport){
        LinkedHashMap<String,PortDetails> all=parseStatuses(status);
        LinkedHashMap<String,PortDetails> switchports=parseSwitchports(switchport);
        for(Map.Entry<String,PortDetails> e:switchports.entrySet()){
            PortDetails prev=all.get(e.getKey()),incoming=e.getValue();
            if(prev!=null){incoming.link=prev.link;incoming.statusVlan=prev.statusVlan;}
            all.put(e.getKey(),incoming);
        }
        return all;
    }
    private static String display(String s){
        return s==null||s.trim().isEmpty()?UNAVAILABLE:s.trim();
    }
    public static String summarizePort(String iface,Map<String,String> results){
        String name=normalizePort(iface),key=name.toLowerCase(Locale.US);
        String status=results.get("show interfaces status");
        String switchport=results.get("show interfaces "+iface+" switchport");
        String running=results.get("show running-config interface "+iface);
        LinkedHashMap<String,PortDetails> all=merge(status,switchport);
        PortDetails d=all.get(key);
        if(d==null)d=new PortDetails(name);
        fillRunning(d,running);
        // IOS output can prefix alternate port names, but a matching normalized switchport
        // is required before claiming that its VLAN information belongs to this port.
        StringBuilder b=new StringBuilder();
        b.append("\n========== PORT VLAN SUMMARY ==========\n");
        b.append("Interface:          ").append(name).append('\n');
        b.append("Link:               ").append(display(d.link)).append('\n');
        b.append("Status VLAN:        ").append(display(d.statusVlan)).append('\n');
        b.append("Admin mode:         ").append(display(d.adminMode)).append('\n');
        b.append("Operational mode:   ").append(display(d.mode)).append('\n');
        b.append("DATA / ACCESS VLAN: ").append(display(d.accessValue())).append('\n');
        b.append("VOICE VLAN:         ").append(display(d.voiceValue())).append('\n');
        b.append("TRUNK NATIVE VLAN:  ").append(display(d.nativeValue())).append('\n');
        b.append("TRUNK ALLOWED:      ").append(display(d.allowedValue())).append('\n');
        b.append("TRUNK ACTIVE:       ").append(display(d.activeTrunkVlans)).append('\n');
        b.append("=======================================\n");
        if(d.accessValue().equals(UNAVAILABLE)&&d.statusVlan.equals(UNAVAILABLE)){
            b.append("VLAN not reported: check 'show interfaces ").append(iface).append(" switchport' below for privilege/unsupported-command errors.\n");
        }
        if(d.voiceValue().equals(UNAVAILABLE))b.append("Voice VLAN not reported by switch. Do not assume it is disabled.\n");
        if(d.statusVlan.equalsIgnoreCase("trunk"))b.append("This is a TRUNK; its VLANs are not just the single Status VLAN column.\n");
        return b.toString();
    }
    public static String summarizeAll(Map<String,String> results){
        String status=results.get("show interfaces status");
        String switchport=results.get("show interfaces switchport");
        LinkedHashMap<String,PortDetails> details=merge(status,switchport);
        StringBuilder b=new StringBuilder();
        b.append("\n========== SWITCH PORT / VLAN CHART ==========\n");
        b.append("PORT | LINK | DATA/ACCESS | VOICE | NATIVE | MODE | ALLOWED TRUNK VLANS\n");
        b.append("---------------------------------------------------------------------\n");
        int count=0;
        for(PortDetails d:details.values()){
            String access=d.accessValue().equals(UNAVAILABLE)?d.statusVlan:d.accessValue();
            b.append(d.name).append(" | ").append(d.link).append(" | ").append(access).append(" | ")
              .append(d.voiceValue()).append(" | ").append(d.nativeValue()).append(" | ")
              .append(d.mode).append(" | ").append(d.allowedValue()).append('\n');
            count++;
        }
        b.append("---------------------------------------------------------------------\n");
        b.append("Ports found: ").append(count).append('\n');
        if(count==0)b.append("No parseable port data. Review the raw 'show interfaces status' and 'show interfaces switchport' responses below.\n");
        if(switchport==null||switchport.isEmpty()||switchport.toLowerCase(Locale.US).contains("invalid input"))
            b.append("Per-port voice/native/trunk details unavailable from this CLI. Try SELECTED PORT to narrow down the problem.\n");
        b.append("\n========== VLAN DIRECTORY ==========\n");
        String vlanOutput=results.get("show vlan brief");
        int vlans=0;
        if(vlanOutput!=null){
            StringBuilder record=null;
            for(String line:vlanOutput.split("\\r?\\n")){
                Matcher m=VLAN_ROW.matcher(line);
                if(m.find()){
                    if(record!=null)b.append(record).append('\n');
                    record=new StringBuilder("VLAN ").append(m.group(1)).append(" | ")
                       .append(m.group(2)).append(" | ").append(m.group(3))
                       .append(" | ports: ").append(m.group(4).trim());
                    vlans++;
                }else if(record!=null&&line.matches("^\\s{4,}\\S+.*")&&!line.toLowerCase(Locale.US).contains("vlan")){
                    String more=line.trim();
                    if(more.matches("(?i)^(?:Gi|Fa|Te|Eth|Po|Twe|Fo|Hu|GigabitEthernet)[0-9].*"))
                        record.append(", ").append(more);
                }
            }
            if(record!=null)b.append(record).append('\n');
        }
        if(vlans==0)b.append("VLAN list not reported. See raw 'show vlan brief' response for errors.\n");
        b.append("VLANs listed: ").append(vlans).append("\n");
        String trunk=results.get("show interfaces trunk");
        b.append("\n========== TRUNK VLAN DETAILS ==========\n");
        if(trunk!=null&&!trunk.trim().isEmpty()&&!trunk.toLowerCase(Locale.US).contains("invalid input")){
            b.append(trunk.length()>16000?trunk.substring(0,16000)+"\n[Full trunk output below]\n":trunk);
        }else b.append("No trunk output available (may be unsupported, or no trunks configured).\n");
        b.append("\nUnknown fields are (not reported), never assumed to be VLAN 1.\n");
        return b.toString();
    }
}
