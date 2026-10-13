package com.keith.ethernettools;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class FiberHealthReportTest {
    private static final String STACK="Switch/Stack Mac Address : aabb.ccdd.eeff\n"+
      "Switch# Role    Mac Address     Priority Version State\n"+
      "*1 Active      aabb.ccdd.eeff  15       V05     Ready\n"+
      " 2 Member      1122.3344.5566  1        V05     Ready\n";
    private static final String STANDALONE="Switch/Stack Mac Address : aabb.ccdd.eeff\n"+
      "Switch# Role Mac Address Priority Version State\n"+
      "*1 Active aabb.ccdd.eeff 15 V05 Ready\n";
    private static String optical(String port, String rx, String alarm, String warn){
        return "Port Temperature Voltage Current Tx Power Rx Power\n"+
          port+" 34.0 3.31 5.44 -2.30 "+rx+"\n"+
          "Rx Power Low Alarm Threshold: "+alarm+"\n"+
          "Rx Power Low Warning Threshold: "+warn+"\n"+
          "Tx Power Low Alarm Threshold: -12.0\n"+
          "Tx Power Low Warning Threshold: -10.0\n";
    }
    private static Map<String,String> seed(String port,String status,String rx){
        Map<String,String> in=new HashMap<>();
        in.put("show interfaces status","Port Name Status Vlan Duplex Speed Type\n"+
          port+" FIBER "+status+" trunk full 10G 10GBase-SR\n");
        in.put("show interfaces transceiver detail",optical(port,rx,"-17.0","-14.0"));
        return in;
    }
    @Test public void badRxPowerIsBadAndRedLabel(){
        Map<String,String> input=seed("Te1/1/1","connected","-19.6");
        FiberHealthReport.Result r=FiberHealthReport.analyze(input,"");
        assertEquals(FiberHealthReport.Level.BAD,r.overall);
        assertTrue(r.text.contains("BAD | Te1/1/1"));
        assertTrue(r.text.contains("ALARM: Rx -19.60 dBm"));
    }
    @Test public void warningRxPowerIsAmber(){
        Map<String,String> input=seed("Te1/1/1","connected","-15.0");
        FiberHealthReport.Result r=FiberHealthReport.analyze(input,"");
        assertEquals(FiberHealthReport.Level.WARNING,r.overall);
        assertTrue(r.text.contains("WARNING | Te1/1/1"));
    }
    @Test public void oneActiveOpticInStackIsYellowWarning(){
        Map<String,String> input=seed("Te1/1/1","connected","-9.0");
        input.put("show switch",STACK);
        FiberHealthReport.Result r=FiberHealthReport.analyze(input,"");
        assertEquals(2,r.stackMembers);
        assertEquals(1,r.activeOptics);
        assertTrue(r.oneFiberWarning);
        assertEquals(FiberHealthReport.Level.WARNING,r.overall);
        assertTrue(r.text.contains("WARNING: ONLY ONE ACTIVE FIBER LINK"));
    }
    @Test public void twoActiveOpticsNoOneFiberWarning(){
        Map<String,String> input=seed("Te1/1/1","connected","-9.0");
        input.put("show switch",STACK);
        input.put("show interfaces status",input.get("show interfaces status")+"\nTe2/1/1 FIBER connected trunk full 10G 10GBase-SR\n");
        input.put("show interfaces transceiver detail",input.get("show interfaces transceiver detail")+"\n"+optical("Te2/1/1","-10.0","-17.0","-14.0"));
        FiberHealthReport.Result r=FiberHealthReport.analyze(input,"");
        assertEquals(2,r.activeOptics);
        assertFalse(r.oneFiberWarning);
        assertEquals(FiberHealthReport.Level.GOOD,r.overall);
        assertTrue(r.text.contains("does not prove independent redundant paths"));
    }
    @Test public void standaloneOneFiberNotStackWarning(){
        Map<String,String> input=seed("Te1/1/1","connected","-9.0");
        input.put("show switch",STANDALONE);
        FiberHealthReport.Result r=FiberHealthReport.analyze(input,"");
        assertEquals(1,r.stackMembers);
        assertFalse(r.oneFiberWarning);
        assertEquals(FiberHealthReport.Level.GOOD,r.overall);
    }
    @Test public void noDomDataIsUnknownNotGood(){
        Map<String,String> input=new HashMap<>();
        input.put("show interfaces status","Gi1/0/1 Fiber connected trunk full 10G SFP\n");
        input.put("show interfaces transceiver detail","% Invalid input detected at '^' marker.");
        input.put("show switch",STACK);
        FiberHealthReport.Result r=FiberHealthReport.analyze(input,"");
        assertEquals(FiberHealthReport.Level.UNKNOWN,r.overall);
        assertTrue(r.text.contains("Optic inventory incomplete"));
        assertFalse(r.oneFiberWarning);
    }
    @Test public void disconnectedFiberWithDomReportsBad(){
        Map<String,String> input=seed("Te1/1/1","notconnect","-9.0");
        FiberHealthReport.Result r=FiberHealthReport.analyze(input,"");
        assertEquals(FiberHealthReport.Level.BAD,r.overall);
        assertTrue(r.text.contains("Optical interface is not passing traffic"));
    }
    @Test public void thresholdHeadingNotAnActiveAlarm(){
        Map<String,String> input=seed("Te1/1/1","connected","-9.0");
        FiberHealthReport.Result r=FiberHealthReport.analyze(input,"");
        assertEquals(FiberHealthReport.Level.GOOD,r.overall);
        assertFalse(r.text.contains("Transceiver reports an ACTIVE optical alarm"));
    }
}
