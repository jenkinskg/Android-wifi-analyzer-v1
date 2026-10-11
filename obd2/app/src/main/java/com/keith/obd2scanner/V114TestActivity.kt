package com.keith.obd2scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class V114TestActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var output: TextView
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.page(this)
        root.addView(Ui.title(this, "XTOOL V114 INTERFACE TEST"), Ui.lp())
        root.addView(Ui.text(this,
            "For the XTOOL IP900BT V114. This test identifies USB hardware and the V114 Wi-Fi link. " +
            "It does not send diagnostic or programming commands to the vehicle."
        ), Ui.lp())

        status = Ui.text(this, "Ready.", 16f)
        output = Ui.text(this, "Connect the V114 by USB, or connect Android Wi-Fi to the V114 hotspot, then run the tests.", 14f)
        root.addView(status, Ui.lp())

        root.addView(Ui.button(this, "1. INSPECT USB V114") { inspectUsb() }, Ui.lp())
        root.addView(Ui.button(this, "2. TEST V114 WI-FI / NETWORK") { inspectNetwork() }, Ui.lp())
        root.addView(Ui.button(this, "3. RUN COMPLETE V114 TEST") { completeTest() }, Ui.lp())
        root.addView(Ui.button(this, "EXPORT REPORT AS TXT") { exportReport() }, Ui.lp())
        root.addView(output, Ui.lp())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun inspectUsb() {
        status.text = "Inspecting USB devices..."
        io.execute {
            val text = usbReport()
            report = header() + text
            main.post {
                status.text = "USB inspection complete."
                output.text = report
            }
        }
    }

    private fun inspectNetwork() {
        status.text = "Testing current network..."
        io.execute {
            val text = networkReport()
            report = header() + text
            main.post {
                status.text = "Network test complete."
                output.text = report
            }
        }
    }

    private fun completeTest() {
        status.text = "Running complete V114 test..."
        output.text = "Checking USB and current Wi-Fi/network. This can take about 10 seconds."
        io.execute {
            val text = header() + usbReport() + "\n" + networkReport()
            report = text
            main.post {
                status.text = "V114 test complete."
                output.text = report
            }
        }
    }

    private fun header(): String = buildString {
        appendLine("XTOOL V114 INTERFACE TEST")
        appendLine("Date: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
        appendLine("Purpose: identify the V114 transport for native Android support")
        appendLine()
    }

    private fun usbReport(): String {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.sortedWith(compareBy({ it.vendorId }, { it.productId }))
        val serialDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val sb = StringBuilder()
        sb.appendLine("=== USB HARDWARE ===")

        if (devices.isEmpty()) {
            sb.appendLine("No USB devices detected.")
            sb.appendLine("If testing USB, connect V114 to the Android device with a data-capable USB-C/OTG cable.")
            return sb.toString()
        }

        for ((index, dev) in devices.withIndex()) {
            val hexVid = "%04X".format(dev.vendorId)
            val hexPid = "%04X".format(dev.productId)
            sb.appendLine("DEVICE " + (index + 1))
            sb.appendLine("Path: " + dev.deviceName)
            sb.appendLine("VID: 0x" + hexVid + "  PID: 0x" + hexPid)
            sb.appendLine("Device class/subclass/protocol: " + dev.deviceClass + "/" + dev.deviceSubclass + "/" + dev.deviceProtocol)
            sb.appendLine("Interfaces: " + dev.interfaceCount)
            sb.appendLine("USB permission: " + manager.hasPermission(dev))

            val driver = serialDrivers.firstOrNull { it.device.deviceId == dev.deviceId }
            sb.appendLine("usb-serial driver: " + (driver?.javaClass?.simpleName ?: "NONE / proprietary or non-serial USB"))

            for (i in 0 until dev.interfaceCount) {
                val intf = dev.getInterface(i)
                sb.appendLine("  Interface #" + i +
                    " class=" + intf.interfaceClass +
                    " subclass=" + intf.interfaceSubclass +
                    " protocol=" + intf.interfaceProtocol +
                    " endpoints=" + intf.endpointCount)
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                        else -> ep.type.toString()
                    }
                    sb.appendLine("    EP 0x" + "%02X".format(ep.address) +
                        " " + dir + " " + type + " maxPacket=" + ep.maxPacketSize)
                }
            }
            sb.appendLine()
        }

        sb.appendLine("NOTE: If the V114 appears with no generic serial driver, that is useful evidence that it uses an XTOOL-specific USB protocol.")
        return sb.toString()
    }

    private fun networkReport(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val sb = StringBuilder()
        sb.appendLine("=== CURRENT NETWORK / V114 WI-FI ===")

        if (network == null) {
            sb.appendLine("No active Android network.")
            sb.appendLine("Connect Android Wi-Fi to the V114 hotspot and rerun.")
            return sb.toString()
        }

        val caps = cm.getNetworkCapabilities(network)
        val lp = cm.getLinkProperties(network)
        sb.appendLine("Interface: " + (lp?.interfaceName ?: "unknown"))
        sb.appendLine("Transport Wi-Fi: " + (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true))
        sb.appendLine("Transport Ethernet: " + (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true))
        sb.appendLine("Local addresses:")
        lp?.linkAddresses?.forEach { sb.appendLine("  " + it.toString()) }
        sb.appendLine("DNS:")
        lp?.dnsServers?.forEach { sb.appendLine("  " + it.hostAddress) }

        val gateway = lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway as? Inet4Address

        if (gateway == null) {
            sb.appendLine("IPv4 gateway: not found.")
            sb.appendLine("Cannot safely identify a V114 network target from this connection.")
            return sb.toString()
        }

        val host = gateway.hostAddress ?: return sb.toString()
        sb.appendLine("IPv4 gateway / candidate VCI: " + host)
        sb.appendLine()
        sb.appendLine("TCP PORT PROBE on " + host)

        val ports = listOf(80, 443, 2000, 3000, 5000, 5555, 6801, 8000, 8080, 8888, 13400, 35000, 60000)
        var openCount = 0
        for (port in ports) {
            val open = isTcpOpen(host, port, 350)
            if (open) openCount++
            sb.appendLine("  " + port + ": " + if (open) "OPEN" else "closed/no response")
        }

        sb.appendLine()
        sb.appendLine("DoIP standard TCP port 13400: " + if (isTcpOpen(host, 13400, 500)) "OPEN" else "not open")
        sb.appendLine("Open tested ports: " + openCount)
        sb.appendLine()
        sb.appendLine("Interpretation:")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            sb.appendLine("- Current active network is not Wi-Fi. Connect directly to the V114 hotspot before judging V114 results.")
        } else {
            sb.appendLine("- Android is on Wi-Fi and the gateway above is the first candidate for the V114.")
            sb.appendLine("- Any OPEN port is a strong lead for the XTOOL tablet-to-VCI protocol.")
        }
        sb.appendLine("- This test only opens and closes TCP sockets. It does not send vehicle diagnostic commands.")
        return sb.toString()
    }

    private fun isTcpOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun exportReport() {
        if (report.isBlank()) {
            Toast.makeText(this, "Run a V114 test first", Toast.LENGTH_LONG).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "XTOOL_V114_TEST_" + stamp + ".txt")
        }, 114)
    }

    @Deprecated("Deprecated API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 114 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.openOutputStream(uri, "w")!!.bufferedWriter().use { it.write(report) }
        }.onSuccess {
            Toast.makeText(this, "V114 test report exported", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "Export failed: " + it.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
