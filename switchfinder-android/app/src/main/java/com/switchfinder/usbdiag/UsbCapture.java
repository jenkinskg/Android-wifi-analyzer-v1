package com.switchfinder.usbdiag;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Experimental AX88179A USB bulk-in capture.
 *
 * Only targets ASIX 0B95:1790, never sends packets or configures the switch.
 * Claims the USB interface for the duration of the capture, potentially
 * detaching Android's Ethernet driver. After reading the ASIX controller
 * registers, attempts to enable receive and multicast acceptance on the
 * USB adapter only. May temporarily disrupt Ethernet; user confirms in UI.
 * On completion, restores saved register values where possible.
 *
 * Frame extraction follows the ax88179_rx_fixup() USB framing used by Linux:
 * one or more frames, padded to eight bytes, per-frame 4-byte metadata,
 * then a 4-byte trailer (packet count + metadata offset).
 */
public final class UsbCapture {
    public interface Listener {
        void onUpdate(String status, boolean done);
    }

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private static final long DURATION_MS = 70000;
    private final Stats stats = new Stats();

    public void stop() { stopped.set(true); }

    public void capture(UsbManager manager, Listener listener) {
        StringBuilder report = new StringBuilder();
        report.append("===== SWITCHFINDER V6 AX88179A RECEIVE TEST =====\n");
        report.append("Hardware: ASIX AX88179A (0B95:1790)\n");
        report.append("Duration: 70 seconds, unless stopped.\n");
        report.append("Receive-only AX88179A PHY+MAC-path test; no switch credentials.\n");
        report.append("WARNING: claiming USB may disable Android eth0.\n\n");

        UsbDeviceConnection conn = null;
        UsbInterface intf = null;
        boolean claimed = false;
        boolean forced = false;
        int savedRx = -1, savedMedium = -1, savedClock = -1, savedPower = -1;
        boolean phyPowerStarted = false;
        boolean rxChanged=false, mediumChanged=false, clkChanged=false, powerChanged=false;

        try {
            UsbDevice target = null;
            for (UsbDevice candidate : manager.getDeviceList().values()) {
                if (candidate.getVendorId() == 0x0B95 &&
                    candidate.getProductId() == 0x1790) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                report.append("ASIX adapter NOT PRESENT.\n");
                return;
            }
            if (!"AX88179A".equalsIgnoreCase(target.getProductName())) {
                report.append("Unsupported ASIX model: ").append(target.getProductName()).append("\n");
                return;
            }
            report.append("Chip type AX88179A: confirmed\n");
            if (!manager.hasPermission(target)) {
                report.append("USB permission not granted. Request USB access first.\n");
                return;
            }

            conn = manager.openDevice(target);
            if (conn == null) {
                report.append("openDevice FAILED.\n");
                return;
            }
            report.append("openDevice: SUCCESS\n");

            UsbEndpoint bulkIn = null;
            for (int i = 0; i < target.getInterfaceCount() && bulkIn == null; i++) {
                UsbInterface candidate = target.getInterface(i);
                for (int j = 0; j < candidate.getEndpointCount(); j++) {
                    UsbEndpoint ep = candidate.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        intf = candidate;
                        bulkIn = ep;
                        break;
                    }
                }
            }
            if (bulkIn == null) {
                report.append("Bulk IN endpoint NOT FOUND.\n");
                return;
            }
            report.append(String.format(Locale.US,
                    "USB interface: %d, bulk-in endpoint: 0x%02X\n",
                    intf.getId(), bulkIn.getAddress()));

            savedRx = readReg(conn, 0x0B, 2);
            savedMedium = readReg(conn, 0x22, 2);
            savedClock = readReg(conn, 0x33, 1);
            savedPower = readReg(conn, 0x26, 2);
            report.append("Before claim registers: RX_CTL=").append(regText(savedRx))
                  .append(" MEDIUM=").append(regText(savedMedium))
                  .append(" CLOCK=").append(regText(savedClock))
                  .append(" PHY_POWER=").append(regText(savedPower)).append("\n");

            try {
                claimed = conn.claimInterface(intf, false);
                if (claimed) report.append("claimInterface(force=false): SUCCESS\n");
                else report.append("claimInterface(force=false): denied; trying force=true\n");
            } catch (Exception e) {
                report.append("Non-forced claim: ").append(e).append("\n");
            }

            if (!claimed) {
                forced = true;
                claimed = conn.claimInterface(intf, true);
                report.append("claimInterface(force=true): ")
                      .append(claimed ? "SUCCESS" : "FAILED").append("\n");
            }
            if (!claimed) {
                report.append("Cannot read USB endpoints without a claim.\n");
                return;
            }
            if (forced) {
                report.append("Kernel Ethernet driver may be detached.\n");
                report.append("Unplug/replug the hub to restore eth0 if needed.\n");
            }

            // Read controls again now that Android's kernel driver may be detached.
            int rx = readReg(conn, 0x0B, 2);
            int medium = readReg(conn, 0x22, 2);
            int clock = readReg(conn, 0x33, 1);
            int power = readReg(conn, 0x26, 2);
            report.append("After claim registers: RX_CTL=").append(regText(rx))
                  .append(" MEDIUM=").append(regText(medium))
                  .append(" CLOCK=").append(regText(clock))
                  .append(" PHY_POWER=").append(regText(power)).append("\n");

            // AX88179A (2026 chip) differs from the older AX88179.
            // Its driver powers the PHY using USB vendor request 0x31,
            // not legacy MAC register 0x26. It also gates the RX MAC path.
            if (rx < 0 || medium < 0 || stopped.get()) {
                report.append("Missing controller state, or stopped; aborting.\n");
                return;
            }
            int phyOn = conn.controlTransfer(0x40, 0x31, 0, 0,
                      new byte[]{0x02}, 0, 1, 1000);
            report.append("AX88179A PHY power-up: ").append(phyOn).append("\n");
            if (phyOn != 1) return;
            phyPowerStarted = true;
            Thread.sleep(500);
            int phyStatus = readPhyBmsr(conn);
            int usbStatus = readReg(conn,0x02,1);
            report.append("PHY MII_BMSR: ").append(regText(phyStatus))
                  .append(" Link: ").append(phyStatus>=0 && (phyStatus&4)!=0)
                  .append(" USB PHY speed: ").append(regText(usbStatus)).append("\n");

            // AX88179A link-up setup uses RX_STATUS_CDC, RX_DATA_CDC_CNT
            // and MAC_PATH in addition to the legacy receive controls.
            if (!writeRequired(conn,report,"Stop MAC_PATH",0xB7,new byte[]{0})) return;
            if (!writeRequired(conn,report,"Stop RX_CTL",0x0B,new byte[]{0,0})) return;
            if (!writeRequired(conn,report,"MAC_RX_STATUS_CDC",0x6D,
                    new byte[]{(byte)0x78,0x70,0})) return;
            if (!writeRequired(conn,report,"MAC_RX_DATA_CDC_CNT",0xC0,
                    new byte[]{0x40})) return;
            byte[] bulk=(usbStatus>=0 && (usbStatus&4)!=0)
                ? new byte[]{0x05,0x7B,0,0x17,0x0F}
                : new byte[]{0x05,(byte)0xC0,0x02,0x06,0x0F};
            if (!writeRequired(conn,report,"AX88179A bulk-in",0x2E,bulk)) return;
            if (!writeRequired(conn,report,"BFM_DATA",0x0E,new byte[]{0})) return;
            // No IP alignment on AX88179A. Accept all multicast (CDP/LLDP).
            int newRx = 0x0100 | 0x0080 | 0x0020 | 0x0002 | 0x0008;
            if (!writeRequired(conn,report,"RX_CTL start",0x0B,
                    new byte[]{(byte)newRx,(byte)(newRx>>8)})) return;
            if (!writeRequired(conn,report,"MEDIUM receive",0x22,
                    new byte[]{0x33,0x01})) return;
            if (!writeRequired(conn,report,"MAC_PATH RX+TX-ready",0xB7,
                    new byte[]{0x03})) return;
            Thread.sleep(350);
            report.append("After AX88179A initialization: RX_CTL=")
                  .append(regText(readReg(conn,0x0B,2)))
                  .append(" MEDIUM=").append(regText(readReg(conn,0x22,2)))
                  .append(" MAC_PATH=").append(regText(readReg(conn,0xB7,1)))
                  .append("\n");
            // Changes are to ASIX runtime registers only; no Ethernet packets
            // are transmitted by this app. Reads will time out if no RX frames arrive.
            byte[] data = new byte[32768];
            long start = android.os.SystemClock.elapsedRealtime();
            long nextStatus = start;
            long lastFrames = 0;

            while (!stopped.get()) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - start >= DURATION_MS) break;

                int n = conn.bulkTransfer(bulkIn, data, data.length, 1100);
                stats.bulkReads++;
                if (n > 0) {
                    stats.bulkBytes += n;
                    stats.successfulBulkReads++;
                    if (stats.hexSample == null) stats.hexSample = hex(data, 0, Math.min(n, 48));
                    parseTransfer(data, n);
                } else {
                    stats.emptyOrFailedBulkReads++;
                    // A kernel detach can cause immediate errors; don't busy-loop.
                    if (n < 0) {
                        try { Thread.sleep(100); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                    }
                }
                now = android.os.SystemClock.elapsedRealtime();
                if (now >= nextStatus || stats.frames != lastFrames) {
                    int secs = (int)((now-start)/1000);
                    listener.onUpdate(report.toString()+stats.summary()+
                            "\nRunning: "+secs+" / 70 seconds\n", false);
                    nextStatus = now + 5000;
                    lastFrames = stats.frames;
                }
            }
            report.append(stopped.get() ? "\nStopped by user.\n" : "\nCapture duration completed.\n");
        } catch (Throwable error) {
            report.append("\nCapture error: ").append(error.getClass().getSimpleName())
                  .append(": ").append(error.getMessage()).append("\n");
        } finally {
            if (conn != null) {
                if (phyPowerStarted) {
                    int down=conn.controlTransfer(0x40,0x31,0,0,
                                   new byte[]{0},0,1,900);
                    report.append("AX88179A PHY power-down: ").append(down).append("\n");
                }
                if (claimed) {
                    // Best-effort return to settings from before the forced claim.
                    // This does not guarantee Android will rebind its network driver.
                    if (rxChanged && savedRx >= 0)
                        report.append("Restore RX_CTL: ").append(writeReg(conn,0x0B,2,savedRx)).append("\n");
                    if (mediumChanged && savedMedium >= 0)
                        report.append("Restore MEDIUM: ").append(writeReg(conn,0x22,2,savedMedium)).append("\n");
                    if (powerChanged && savedPower >= 0)
                        report.append("Restore PHY_POWER: ").append(writeReg(conn,0x26,2,savedPower)).append("\n");
                    if (clkChanged && savedClock >= 0)
                        report.append("Restore CLOCK: ").append(writeReg(conn,0x33,1,savedClock)).append("\n");
                }
                if (claimed && intf != null) {
                    try {
                        report.append("releaseInterface: ")
                              .append(conn.releaseInterface(intf) ? "SUCCESS" : "FAILED")
                              .append("\n");
                    } catch (Throwable e) {
                        report.append("releaseInterface error: ").append(e).append("\n");
                    }
                }
                try { conn.close(); report.append("USB connection closed.\n"); }
                catch (Throwable ignored) {}
            }
            report.append(stats.summary());
            if (stats.successfulBulkReads == 0) {
                report.append("\nNO RAW USB DATA RECEIVED.\n");
                report.append("This does NOT prove CDP/LLDP is disabled on the switch.\n");
                report.append("The kernel driver may have stopped receiving when detached.\n");
            } else if (stats.validFrames == 0) {
                report.append("\nUSB data received, but no AX88179 frames decoded.\n");
            } else if (stats.cdp == 0 && stats.lldp == 0) {
                report.append("\nEthernet frames decoded, but no CDP or LLDP observed.\n");
                report.append("Capture filters or switch advertisement configuration may matter.\n");
            }
            report.append("\nIf Ethernet is missing, unplug/replug the USB hub.\n");
            listener.onUpdate(report.toString(), true);
        }
    }

    private static int readPhyBmsr(UsbDeviceConnection c) {
        byte[] bytes=new byte[2];
        int n=c.controlTransfer(0xC0,0x02,0x03,0x01,bytes,0,2,1000);
        return n==2 ? ((bytes[0]&255)|((bytes[1]&255)<<8)) : -1;
    }
    private static boolean writeRequired(UsbDeviceConnection c,StringBuilder report,
                       String label,int register,byte[] bytes) {
        int n=c.controlTransfer(0x40,0x01,register,bytes.length,bytes,0,bytes.length,1000);
        report.append(label).append(": ").append(n==bytes.length?"OK":"FAILED "+n).append("\n");
        return n==bytes.length;
    }
    private static int readReg(UsbDeviceConnection c, int register, int size) {
        byte[] b = new byte[size];
        int n = c.controlTransfer(0xC0, 0x01, register, size, b, 0, size, 1000);
        if (n != size) return -1;
        return size == 1 ? (b[0]&0xff) : ((b[0]&0xff)|((b[1]&0xff)<<8));
    }

    private static int writeReg(UsbDeviceConnection c, int register, int size, int value) {
        byte[] b = size==1 ? new byte[]{(byte)(value&255)}
            : new byte[]{(byte)(value&255),(byte)((value>>8)&255)};
        return c.controlTransfer(0x40, 0x01, register, size, b, 0, size, 1000);
    }
    private static String regText(int value) {
        return value<0 ? "READ-FAILED" : String.format(Locale.US,"0x%04X",value);
    }

    private void parseTransfer(byte[] data, int size) {
        // AX88179A (AX179A-family) uses 8-byte descriptors AND trailer.
        // The previous V5 decoder used AX88179's 4-byte descriptors.
        if(size < 24) { stats.invalidTransfers++; return; }
        long trailer = (little32(data,size-8)&0xffffffffL) |
                      ((little32(data,size-4)&0xffffffffL)<<32);
        int count = (int)(trailer & 0x1fff);
        int headerOffset = (int)((trailer >>> 13)&0x7ffff);
        if(count<1 || count>256 || headerOffset<0 ||
           (long)headerOffset + count*8L != size-8) {
            stats.invalidTransfers++; return;
        }
        stats.framedTransfers++;
        int off=0;
        for(int i=0;i<count;i++){
            int descOff=headerOffset+i*8;
            long meta = (little32(data,descOff)&0xffffffffL) |
                        ((little32(data,descOff+4)&0xffffffffL)<<32);
            int packetLength=(int)((meta>>>16)&0x7fff);
            int padded=(packetLength+7)&~7;
            if(packetLength<14 || off+padded>headerOffset){
                stats.frameErrors++;break;
            }
            // AX179A descriptor: BIT(11)=RX_OK; BIT(31)=DROP.
            if((meta & (1L<<31))==0 && (meta & (1L<<11))!=0)
                parseEthernet(data,off,packetLength);
            else
                stats.frameErrors++;
            off+=padded;
        }
    }

    private void parseEthernet(byte[] b, int off, int length) {
        stats.frames++;
        if (length < 14) return;
        stats.validFrames++;
        int pos = off+12;
        int etype = u16be(b,pos);
        pos += 2;
        // An optional 802.1Q or QinQ header may precede the LLDP ethertype.
        for (int i=0; i<2 && (etype==0x8100 || etype==0x88A8); i++) {
            if (pos+4 > off+length) return;
            etype = u16be(b,pos+2);
            pos+=4;
        }
        if (etype == 0x88CC && b[off]==1 && (b[off+1]&255)==0x80 &&
            (b[off+2]&255)==0xc2) {
            stats.lldp++;
            decodeLldp(b,pos,off+length);
        } else if (matches(b,off,new int[]{1,0,12,204,204,204})) {
            // CDP is LLC/SNAP (AA AA 03 00 00 0C 20 00), not ethertype.
            for (int i=off+14; i+8 <= Math.min(off+length,off+40); i++) {
                if (matches(b,i,new int[]{0xAA,0xAA,3,0,0,12,0x20,0})) {
                    stats.cdp++;
                    decodeCdp(b,i+8+4,off+length);
                    break;
                }
            }
        }
    }

    private void decodeLldp(byte[] b, int pos, int end) {
        String name="",port="",mgmt="",vlan="";
        while (pos+2<=end) {
            int hdr=u16be(b,pos); pos+=2;
            int type=hdr>>>9, len=hdr&511;
            if (pos+len>end) break;
            if (type==0) break;
            if (type==2 && len>1) port=ascii(b,pos+1,len-1);
            else if (type==5) name=ascii(b,pos,len);
            else if (type==8 && len>=7) {
                int alen=b[pos]&255;
                if (alen==5 && (b[pos+1]&255)==1 && pos+6<=end)
                    mgmt=ip(b,pos+2);
            } else if (type==127 && len>=6 &&
                matches(b,pos,new int[]{0,128,194,1})) {
                vlan=Integer.toString(u16be(b,pos+4));
            }
            pos+=len;
        }
        stats.noteDiscovery("LLDP",name,port,mgmt,vlan,"");
    }

    private void decodeCdp(byte[] b, int pos, int end) {
        String name="",port="",mgmt="",vlan="",platform="";
        while (pos+4<=end) {
            int type=u16be(b,pos), len=u16be(b,pos+2);
            if (len<4 || pos+len>end) break;
            int v=pos+4, n=len-4;
            if (type==1) name=ascii(b,v,n);
            else if (type==3) port=ascii(b,v,n);
            else if (type==6) platform=ascii(b,v,n);
            else if (type==10 && n>=2) vlan=Integer.toString(u16be(b,v));
            else if (type==2) {
                // IPv4 address protocol marker: 01 01 CC 00 04 followed by address
                for (int i=v;i+9<=v+n;i++) {
                    if (matches(b,i,new int[]{1,1,204,0,4})) {
                        mgmt=ip(b,i+5);
                        break;
                    }
                }
            }
            pos+=len;
        }
        stats.noteDiscovery("CDP",name,port,mgmt,vlan,platform);
    }

    private static int little32(byte[] b,int o) {
        return (b[o]&255)|((b[o+1]&255)<<8)|((b[o+2]&255)<<16)|((b[o+3]&255)<<24);
    }
    private static int u16be(byte[] b,int o) {
        return ((b[o]&255)<<8)|(b[o+1]&255);
    }
    private static String ascii(byte[] b,int o,int n) {
        return new String(b,o,n,StandardCharsets.UTF_8).trim();
    }
    private static String ip(byte[] b,int o) {
        return String.format(Locale.US,"%d.%d.%d.%d",
            b[o]&255,b[o+1]&255,b[o+2]&255,b[o+3]&255);
    }
    private static boolean matches(byte[] b,int off,int[] expected) {
        if (off<0||off+expected.length>b.length) return false;
        for(int i=0;i<expected.length;i++)
            if ((b[off+i]&255)!=expected[i]) return false;
        return true;
    }
    private static String hex(byte[] b,int start,int n) {
        StringBuilder s=new StringBuilder();
        for(int i=start;i<start+n;i++) s.append(String.format(Locale.US,"%02X ",b[i]&255));
        return s.toString();
    }
    private static final class Stats {
        int bulkReads,successfulBulkReads,emptyOrFailedBulkReads,bulkBytes,invalidTransfers;
        int framedTransfers,frames,validFrames,frameErrors,cdp,lldp;
        String hexSample, discovery="";
        void noteDiscovery(String proto,String name,String port,String ip,String vlan,String platform) {
            String value=proto+"  switch="+(name.isEmpty()?"Not advertised":name)+
                "  port="+(port.isEmpty()?"Not advertised":port)+
                "  mgmt="+(ip.isEmpty()?"Not advertised":ip)+
                "  vlan="+(vlan.isEmpty()?"Not advertised":vlan)+
                (platform.isEmpty()?"":"  model="+platform);
            if (!discovery.contains(value)) {
                discovery += "\n"+value+"\n";
            }
        }
        String summary() {
            return "\n===== PACKET COUNTERS =====\n"+
              "USB reads: "+bulkReads+"\n"+
              "Successful USB reads: "+successfulBulkReads+"\n"+
              "USB bytes: "+bulkBytes+"\n"+
              "Framed USB transfers: "+framedTransfers+"\n"+
              "Ethernet frames: "+validFrames+"\n"+
              "Invalid USB framing: "+invalidTransfers+"\n"+
              "Frame errors: "+frameErrors+"\n"+
              "CDP frames: "+cdp+"\n"+
              "LLDP frames: "+lldp+"\n"+
              "First USB data: "+(hexSample==null?"None":hexSample)+"\n"+
              (discovery.isEmpty()?"Switch/Port: NOT IDENTIFIED\n":discovery);
        }
    }
}
