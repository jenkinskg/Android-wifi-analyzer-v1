package com.keith.ethernettools;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class CiscoVlanReportTest {
    private static final String STATUSES=
        "Port      Name               Status       Vlan       Duplex  Speed Type\n"+
        "Gi1/0/24  ReceptionAP        connected    120        a-full  a-1000 10/100/1000BaseTX\n"+
        "Gi1/0/25  Voice Phone        connected    210        a-full  a-1000 10/100/1000BaseTX\n"+
        "Te1/1/1   Core Uplink        connected    trunk      full    10G 10GBase-LR\n"+
        "Gi1/0/26  Down Host          notconnect   120        auto    auto 10/100/1000BaseTX\n";
    private static final String SWITCHPORTS=
        "Name: Gi1/0/24\n"+
        "Switchport: Enabled\n"+
        "Administrative Mode: static access\n"+
        "Operational Mode: static access\n"+
        "Access Mode VLAN: 120 (DATA_AP)\n"+
        "Trunking Native Mode VLAN: 1 (default)\n"+
        "Voice VLAN: 220 (VOICE)\n"+
        "Trunking VLANs Enabled: ALL\n"+
        "Name: Te1/1/1\n"+
        "Switchport: Enabled\n"+
        "Administrative Mode: trunk\n"+
        "Operational Mode: trunk\n"+
        "Access Mode VLAN: 1 (default)\n"+
        "Trunking Native Mode VLAN: 99 (MANAGEMENT)\n"+
        "Voice VLAN: none\n"+
        "Trunking VLANs Enabled: 99,120,210,220\n";
    private static final String VLANS=
        "VLAN Name                             Status    Ports\n"+
        "---- -------------------------------- --------- -------------------------------\n"+
        "1    default                          active    Gi1/0/1\n"+
        "120  DATA_AP                          active    Gi1/0/24, Gi1/0/26\n"+
        "210  VOICE_OTHER                      active    Gi1/0/25\n"+
        "220  VOICE                            active\n";
    @Test public void allPortsShowStatusAccessVoiceAndTrunk(){
        Map<String,String> input=new HashMap<>();
        input.put("show interfaces status",STATUSES);
        input.put("show interfaces switchport",SWITCHPORTS);
        input.put("show vlan brief",VLANS);
        input.put("show interfaces trunk","Port Mode Encapsulation Status Native vlan\nTe1/1/1 on 802.1q trunking 99\n");
        String report=CiscoVlanReport.summarizeAll(input);
        assertTrue(report.contains("Gi1/0/24 | connected | 120 (DATA_AP) | 220 (VOICE)"));
        assertTrue(report.contains("Te1/1/1 | connected | 1 (default) | none | 99 (MANAGEMENT) | trunk | 99,120,210,220"));
        assertTrue(report.contains("Gi1/0/26 | notconnect | 120 | (not reported)"));
        assertTrue(report.contains("VLAN 120 | DATA_AP | active | ports: Gi1/0/24, Gi1/0/26"));
        assertTrue(report.contains("VLANs listed: 4"));
        assertTrue(report.contains("Ports found: 4"));
    }
    @Test public void selectedPortShowsClearVoiceAndData(){
        Map<String,String> input=new HashMap<>();
        input.put("show interfaces status", STATUSES);
        input.put("show interfaces Gi1/0/24 switchport",SWITCHPORTS.substring(0,SWITCHPORTS.indexOf("Name: Te1/1/1")));
        input.put("show running-config interface Gi1/0/24",
                  "interface GigabitEthernet1/0/24\n switchport access vlan 120\n switchport voice vlan 220\n");
        String result=CiscoVlanReport.summarizePort("Gi1/0/24",input);
        assertTrue(result.contains("Interface:          Gi1/0/24"));
        assertTrue(result.contains("Link:               connected"));
        assertTrue(result.contains("DATA / ACCESS VLAN: 120 (DATA_AP)"));
        assertTrue(result.contains("VOICE VLAN:         220 (VOICE)"));
    }
    @Test public void runningConfigFallbackOnlyWhenSwitchportAbsent(){
        Map<String,String> input=new HashMap<>();
        input.put("show running-config interface Gi1/0/8",
            "interface GigabitEthernet1/0/8\n switchport mode trunk\n switchport trunk native vlan 50\n switchport trunk allowed vlan 20,30,50\n");
        String report=CiscoVlanReport.summarizePort("Gi1/0/8",input);
        assertTrue(report.contains("TRUNK NATIVE VLAN:  50"));
        assertTrue(report.contains("TRUNK ALLOWED:      20,30,50"));
        assertTrue(report.contains("trunk (configured; operational mode unavailable)"));
        assertTrue(report.contains("VOICE VLAN:         (not reported)"));
    }
    @Test public void unknownVlanIsNotInvented(){
        String report=CiscoVlanReport.summarizePort("Gi1/0/2",Collections.emptyMap());
        assertTrue(report.contains("DATA / ACCESS VLAN: (not reported)"));
        assertTrue(report.contains("VLAN not reported:"));
        assertFalse(report.contains("DATA / ACCESS VLAN: 1"));
    }
    @Test public void vendorPortNamesNormalized(){
        assertEquals("Gi2/0/30",CiscoVlanReport.normalizePort("GigabitEthernet2/0/30"));
        assertEquals("Te1/1/1",CiscoVlanReport.normalizePort("TenGigabitEthernet1/1/1"));
    }
    @Test public void noVlanDirectoryDoesNotClaimNoneExist(){
        String report=CiscoVlanReport.summarizeAll(Collections.emptyMap());
        assertTrue(report.contains("VLAN list not reported"));
        assertTrue(report.contains("No parseable port data"));
    }
}
