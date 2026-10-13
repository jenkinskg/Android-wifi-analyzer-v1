package com.keith.ethernettools;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class CiscoPortParserTest {
    @Test public void macFormats(){
        assertEquals("aabb.ccdd.eeff",CiscoPortParser.dottedMac("aa:bb:cc:dd:ee:ff"));
        assertEquals("aabb.ccdd.eeff",CiscoPortParser.dottedMac("AA-BB-CC-DD-EE-FF"));
    }
    @Test public void macTableAndVlan(){
        String output="Vlan    Mac Address       Type        Ports\n"+
            "----    -----------       --------    -----\n"+
            " 120    aabb.ccdd.eeff    DYNAMIC     Gi1/0/24\n"+
            "  15    bbbb.2222.4444    DYNAMIC     Po1\n";
        List<CiscoPortParser.Match> result=CiscoPortParser.findMac(output,"AA:BB:CC:DD:EE:FF");
        assertEquals(1,result.size());assertEquals("120",result.get(0).vlan);assertEquals("Gi1/0/24",result.get(0).port);
    }
    @Test public void detectsLogicalUplink(){
        assertTrue(CiscoPortParser.isLogicalUplink("Po20"));
        assertTrue(CiscoPortParser.isLogicalUplink("Port-channel1"));
        assertFalse(CiscoPortParser.isLogicalUplink("Gi1/0/24"));
    }
    @Test public void detectsArpMac(){
        String result="Internet  10.120.1.22  24  aabb.ccdd.eeff ARPA Vlan120";
        assertEquals("aabb.ccdd.eeff",CiscoPortParser.arpMac(result,"10.120.1.22"));
    }
    @Test public void rejectsUnsafeInterface(){
        assertTrue(CiscoPortParser.validInterface("Gi1/0/24"));
        assertFalse(CiscoPortParser.validInterface("Gi1/0/24; reload"));
    }
    @Test public void redactsSecrets(){
        String report="switchport access vlan 20\npassword 7 abcdef\nsnmp-server community secret RO\n";
        String filtered=CiscoPortParser.safeReport(report);
        assertTrue(filtered.contains("switchport access vlan 20"));
        assertFalse(filtered.contains("abcdef"));
        assertFalse(filtered.contains("community secret"));
    }
}