package com.keith.ethernettools;

import java.util.*;
import java.util.regex.*;

/**
 * Read-only Cisco IOS/IOS-XE optical-health interpretation.
 * Never judges "good" or "bad" from a standalone dBm reading without DOM thresholds.
 * Stack fiber count means active optical uplinks seen by the inventory,
 * NOT StackWise/cascade cable health or verified path redundancy.
 */
public final class FiberHealthReport {
    private FiberHealthReport() {}

    public enum Level { BAD, WARNING, GOOD, UNKNOWN }
    public static final class Port {
        public final String name;
        public String link="not reported";
        public Double txDbm,rxDbm;
        public Double rxLowAlarm,rxLowWarn,rxHighAlarm,rxHighWarn;
        public Double txLowAlarm,txLowWarn,txHighAlarm,txHighWarn;
        public boolean explicitAlarm=false,explicitWarning=false,hasOptic=false;
        public boolean adminDown=false;
        public final ArrayList<String> messages=new ArrayList<>();
        public Level level=Level.UNKNOWN;
        Port(String n){name=n;}
    }
    public static final class Result {
        public final LinkedHashMap<String,Port> ports=new LinkedHashMap<>();
        public int stackMembers=0, activeOptics=0;
        public boolean stackKnown=false,opticInventoryComplete=false;
        public boolean oneFiberWarning=false;
        public Level overall=Level.UNKNOWN;
        public String text="";
    }
    private static final Pattern START=Pattern.compile(
        "(?i)^\\s*((?:Gi|GigabitEthernet|Te|TenGigabitEthernet|Twe|TwentyFiveGigE|Fo|FortyGigabitEthernet|Hu|HundredGigE|Eth|Ethernet|Fa|FastEthernet|Po|Port-channel)[0-9][0-9A-Za-z/._:-]*)\\b");
    private static final Pattern VALUE=Pattern.compile("(?i)(?<![a-z])(?:Rx|Receive|Tx|Transmit)(?:\\s+(?:Optical|Output|Input))?\\s+Power\\s*(?::|=)\\s*(-?\\d+(?:\\.\\d+)?)\\s*dBm");
    private static final Pattern RX=Pattern.compile("(?i)\\b(?:Rx|Receive)(?:\\s+Optical)?\\s+Power\\s*(?::|=)\\s*(-?\\d+(?:\\.\\d+)?)\\s*dBm");
    private static final Pattern TX=Pattern.compile("(?i)\\b(?:Tx|Transmit)(?:\\s+(?:Optical|Output))?\\s+Power\\s*(?::|=)\\s*(-?\\d+(?:\\.\\d+)?)\\s*dBm");
    private static final Pattern TABLE_ROW=Pattern.compile(
        "(?i)^\\s*((?:Gi|Te|Twe|Fo|Hu|Eth|Fa|GigabitEthernet|TenGigabitEthernet|TwentyFiveGigE|FortyGigabitEthernet|HundredGigE|Ethernet|FastEthernet)[0-9][0-9A-Za-z/._:-]*)\\s+(.+)$");
    private static final Pattern NUMBER=Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern THRESHOLD=Pattern.compile("(?i)\\b(Rx|Tx)(?:\\s+(?:Optical|Output|Input))?\\s+Power\\s+(High|Low)\\s+(Alarm|Warn(?:ing)?)\\s+Threshold\\s*(?::|=)\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern STACK_LINE=Pattern.compile("(?i)^\\s*\\*?\\s*(\\d+)\\s+(?:Active|Standby|Member|Ready|Provisioned|Master)\\b");
    private static final Pattern STACK_LINE_OTHER=Pattern.compile("(?i)^\\s*\\*?\\s*(\\d+)\\s+[0-9a-f]{4}\\.[0-9a-f]{4}\\.[0-9a-f]{4}\\s+\\d+\\s+\\S+\\s+(?:Ready|Active|Standby|Member)\\b");
    private static final String[] QUERY_COMMANDS={
        "show interfaces status",
        "show interfaces transceiver detail",
        "show interfaces transceiver",
        "show interfaces counters errors",
        "show switch",
        "show switch stack-ports summary",
        "show etherchannel summary"
    };
    public static List<String> commands(){return Arrays.asList(QUERY_COMMANDS);}
    private static String key(String p){return CiscoVlanReport.normalizePort(p).toLowerCase(Locale.US);}
    private static Port port(Result r,String value){
        String name=CiscoVlanReport.normalizePort(value), k=key(name);
        Port p=r.ports.get(k);if(p==null){p=new Port(name);r.ports.put(k,p);}
        return p;
    }
    private static Double number(String value){
        try{return Double.parseDouble(value);}catch(Exception ex){return null;}
    }
    private static boolean error(String output){
        if(output==null||output.trim().isEmpty())return true;
        String o=output.toLowerCase(Locale.US);
        return o.contains("% invalid input")||o.contains("% ambiguous command")||
            o.contains("% unrecognized command")||o.contains("error:")&&o.length()<300||
            o.contains("command not supported")||o.contains("not supported on this interface");
    }
    private static void parseStatus(Result r,String output){
        if(error(output))return;
        for(CiscoVlanReport.PortDetails d:CiscoVlanReport.parseStatuses(output).values()){
            Port p=port(r,d.name);p.link=d.link;
            p.adminDown="disabled".equalsIgnoreCase(d.link);
            if(d.link.equalsIgnoreCase("err-disabled"))p.messages.add("Interface err-disabled");
        }
    }
    private static void parseTransceiver(Result r,String output){
        if(error(output))return;
        Port current=null;
        boolean tableHasOpticalPower=false;
        // Most Cisco tables have Tx Power and Rx Power as their final columns.
        for(String line:output.split("\\r?\\n")){
            Matcher start=START.matcher(line);
            if(start.find()){
                String interfaceName=start.group(1);
                if(CiscoPortParser.validInterface(interfaceName)){
                    current=port(r,interfaceName);
                    // A named row alone does not prove optical DOM is present.
                    Matcher tbl=TABLE_ROW.matcher(line);
                    if(tbl.matches()){
                        String tail=tbl.group(2);
                        if(tableHasOpticalPower){
                            ArrayList<Double> values=new ArrayList<>();
                            Matcher n=NUMBER.matcher(tail);
                            while(n.find())values.add(number(n.group()));
                            // common DOM table format: temperature, voltage, current, Tx, Rx
                            if(values.size()>=5){
                                current.txDbm=values.get(values.size()-2);
                                current.rxDbm=values.get(values.size()-1);
                                current.hasOptic=true;
                            }
                        }
                    }
                }
            }
            String lower=line.toLowerCase(Locale.US);
            if(lower.contains("port")&&lower.contains("tx power")&&lower.contains("rx power"))
                tableHasOpticalPower=true;
            if(current==null)continue;
            Matcher rx=RX.matcher(line),tx=TX.matcher(line);
            if(rx.find()){current.rxDbm=number(rx.group(1));current.hasOptic=true;}
            if(tx.find()){current.txDbm=number(tx.group(1));current.hasOptic=true;}
            Matcher th=THRESHOLD.matcher(line);
            while(th.find()){
                Double v=number(th.group(4));
                boolean isRx=th.group(1).equalsIgnoreCase("Rx");
                boolean high=th.group(2).equalsIgnoreCase("High");
                boolean alarm=th.group(3).equalsIgnoreCase("Alarm");
                if(isRx){
                    if(high){if(alarm)current.rxHighAlarm=v;else current.rxHighWarn=v;}
                    else {if(alarm)current.rxLowAlarm=v;else current.rxLowWarn=v;}
                }else{
                    if(high){if(alarm)current.txHighAlarm=v;else current.txHighWarn=v;}
                    else {if(alarm)current.txLowAlarm=v;else current.txLowWarn=v;}
                }
                current.hasOptic=true;
            }
            if(lower.matches(".*\\b(?:rx|tx)\\s+power\\s+(?:high|low)\\s+alarm\\s*[:=]\\s*(?:on|active|yes|true|asserted).*"))
                current.explicitAlarm=true;
            if(lower.matches(".*\\b(?:rx|tx)\\s+power\\s+(?:high|low)\\s+warn(?:ing)?\\s*[:=]\\s*(?:on|active|yes|true|asserted).*"))
                current.explicitWarning=true;
            if(lower.matches(".*(?:optical|transceiver).*(?:not present|unsupported|not supported).*"))
                current.messages.add("Optical DOM reading unavailable");
        }
        // If an optical row format is unrecognized, do not invent measurements.
    }
    private static int parseStackMembers(String output){
        if(error(output))return 0;
        HashSet<Integer> units=new HashSet<>();
        boolean table=false;
        for(String line:output.split("\\r?\\n")){
            String v=line.trim().toLowerCase(Locale.US);
            if(v.contains("switch#")||v.contains("role")&&v.contains("state"))table=true;
            Matcher m=STACK_LINE.matcher(line),n=STACK_LINE_OTHER.matcher(line);
            if(m.find())units.add(Integer.valueOf(m.group(1)));
            else if(n.find())units.add(Integer.valueOf(n.group(1)));
            else if(table&&line.matches("\\s*\\*?\\s*\\d+\\s+.*\\bReady\\s*$")){
                Matcher num=Pattern.compile("^\\s*\\*?\\s*(\\d+)").matcher(line);if(num.find())units.add(Integer.valueOf(num.group(1)));
            }
        }
        return units.size();
    }
    private static void checkReading(Port p,String label,Double value,Double lowAlarm,Double lowWarn,Double highWarn,Double highAlarm){
        if(value==null)return;
        String reading=label+" "+String.format(Locale.US,"%.2f dBm",value);
        if(lowAlarm!=null&&value<=lowAlarm || highAlarm!=null&&value>=highAlarm){
            p.messages.add("ALARM: "+reading+" exceeds module alarm threshold");
            p.level=Level.BAD;
        }else if(lowWarn!=null&&value<=lowWarn||highWarn!=null&&value>=highWarn){
            p.messages.add("WARNING: "+reading+" exceeds module warning threshold");
            if(p.level!=Level.BAD)p.level=Level.WARNING;
        }else if(lowAlarm!=null||lowWarn!=null||highAlarm!=null||highWarn!=null){
            p.messages.add(reading+" within available module thresholds");
            if(p.level==Level.UNKNOWN)p.level=Level.GOOD;
        }else{
            p.messages.add(reading+" (thresholds not supplied)");
        }
    }
    private static void evaluate(Port p){
        if(p.explicitAlarm){p.level=Level.BAD;p.messages.add("Transceiver reports an ACTIVE optical alarm");}
        else if(p.explicitWarning){p.level=Level.WARNING;p.messages.add("Transceiver reports an ACTIVE optical warning");}
        checkReading(p,"Rx",p.rxDbm,p.rxLowAlarm,p.rxLowWarn,p.rxHighWarn,p.rxHighAlarm);
        checkReading(p,"Tx",p.txDbm,p.txLowAlarm,p.txLowWarn,p.txHighWarn,p.txHighAlarm);
        // Down/err-disabled for a proven optical interface is bad unless deliberately admin disabled.
        if(p.hasOptic&&!p.adminDown&&(
                p.link.equalsIgnoreCase("notconnect")||p.link.equalsIgnoreCase("err-disabled")||
                p.link.equalsIgnoreCase("inactive")||p.link.equalsIgnoreCase("suspended"))){
            p.messages.add("Optical interface is not passing traffic ("+p.link+")");
            p.level=Level.BAD;
        }
        if(p.adminDown){
            p.messages.add("Administratively disabled; link health not tested");
            if(p.level==Level.UNKNOWN)p.level=Level.UNKNOWN;
        }
        if(p.hasOptic&&p.level==Level.UNKNOWN){
            p.messages.add("No usable optic alarm thresholds; health cannot be verified");
        }
    }
    private static int severity(Level l){return l==Level.BAD?3:l==Level.WARNING?2:l==Level.GOOD?1:0;}
    public static Result analyze(Map<String,String> outputs,String requestedPort){
        Result result=new Result();
        parseStatus(result,outputs.get("show interfaces status"));
        String optics=outputs.get("show interfaces transceiver detail");
        parseTransceiver(result,optics);
        if(result.ports.values().stream().noneMatch(p->p.hasOptic))
            parseTransceiver(result,outputs.get("show interfaces transceiver"));
        // A failed transceiver query cannot prove there are no other fiber uplinks.
        result.opticInventoryComplete=!error(optics);
        String stack=outputs.get("show switch");
        result.stackMembers=parseStackMembers(stack);
        result.stackKnown=!error(stack)&&result.stackMembers>0;
        // Active optic count includes only ports with DOM evidence AND status connected.
        for(Port p:result.ports.values()){
            evaluate(p);
            if(p.hasOptic&&p.link.equalsIgnoreCase("connected"))result.activeOptics++;
        }
        result.oneFiberWarning=result.stackKnown&&result.stackMembers>1&&result.activeOptics==1;
        if(result.oneFiberWarning)result.overall=Level.WARNING;
        for(Port p:result.ports.values()){
            if(p.hasOptic&&severity(p.level)>severity(result.overall))result.overall=p.level;
        }
        String focus=requestedPort==null?"":requestedPort.trim();
        StringBuilder b=new StringBuilder();
        b.append("FIBER LINK HEALTH — CISCO\n");
        b.append("Status: ").append(result.overall).append("\n");
        b.append("Stack members reported: ").append(result.stackKnown?result.stackMembers:"not available").append("\n");
        b.append("Active optical ports observed: ").append(result.activeOptics).append("\n");
        if(result.oneFiberWarning){
            b.append("WARNING: ONLY ONE ACTIVE FIBER LINK SEEN ON THIS STACK.\n");
            b.append("This may reduce redundancy. Verify the expected fiber/uplink design and stack cabling separately.\n");
            if(!result.opticInventoryComplete)b.append("WARNING: The transceiver inventory is incomplete; the count may be an underestimate.\n");
        }else if(!result.stackKnown){
            b.append("Stack status: unavailable. Cannot confirm number of stack members or fiber redundancy.\n");
        }else if(result.stackMembers==1){
            b.append("One stack member detected; one-fiber multi-member warning does not apply.\n");
        }else if(result.activeOptics==0){
            b.append("No active optical links confirmed. Optic queries may be unsupported or fiber links absent.\n");
        }else{
            b.append("Multiple active optical ports observed; this does not prove independent redundant paths.\n");
        }
        if(!result.opticInventoryComplete)b.append("Optic inventory incomplete: health and link counts are provisional.\n");
        b.append("\nPORT HEALTH (BAD = red, WARNING = amber, GOOD = green)\n");
        int shown=0;
        for(Port p:result.ports.values()){
            if(!p.hasOptic)continue;
            if(!focus.isEmpty()&&!key(p.name).equals(key(focus)))continue;
            shown++;
            b.append(p.level).append(" | ").append(p.name).append(" | Link ").append(p.link)
              .append(" | Tx ").append(p.txDbm==null?"unknown":String.format(Locale.US,"%.2f dBm",p.txDbm))
              .append(" | Rx ").append(p.rxDbm==null?"unknown":String.format(Locale.US,"%.2f dBm",p.rxDbm))
              .append('\n');
            for(String m:p.messages)b.append("  - ").append(m).append('\n');
        }
        if(shown==0){
            b.append("UNKNOWN | No optical DOM readings for ").append(focus.isEmpty()?"this switch":"port "+focus)
             .append("; check raw transceiver output and switch support.\n");
        }
        b.append("\nNotes: Optical power requires supported transceiver DOM. dBm by itself is not a failure indicator. ");
        b.append("Stack links/cascade/StackWise cables are not counted as fiber uplinks, and one active fiber does not automatically prove a fault.\n");
        result.text=b.toString();
        return result;
    }
}
