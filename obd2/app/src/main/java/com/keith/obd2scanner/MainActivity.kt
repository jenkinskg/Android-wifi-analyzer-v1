package com.keith.obd2scanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.*
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipInputStream
import kotlin.concurrent.withLock
import kotlin.math.abs
import org.json.JSONObject

enum class VehicleProfile(val key: String, val label: String) {
    HONDA("HONDA_CRZ_2014", "2014 Honda CR-Z"),
    VOLVO("VOLVO_XC60_2011", "2011 Volvo XC60");

    companion object {
        fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: HONDA
        fun fromVin(vin: String): VehicleProfile? {
            val v = vin.uppercase()
            if (v.length != 17) return null
            if (v.startsWith("JHMZF") && v[9] == 'E') return HONDA
            if ((v.startsWith("YV4") || v.startsWith("YV1")) && v[9] == 'B') return VOLVO
            return null
        }
    }
}

class ScannerApp : Application() {
    lateinit var obd: ObdManager
    override fun onCreate() { super.onCreate(); obd = ObdManager(this) }
}

object Ui {
    fun page(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(28, 36, 28, 36)
        setBackgroundColor(Color.rgb(245, 247, 250))
    }
    fun title(c: Context, s: String) = TextView(c).apply {
        text = s; textSize = 24f; setTextColor(Color.BLACK)
    }
    fun text(c: Context, s: String, z: Float = 15f) = TextView(c).apply {
        text = s; textSize = z; setPadding(0, 8, 0, 8)
    }
    fun button(c: Context, s: String, enabled: Boolean = true, f: () -> Unit) = Button(c).apply {
        text = s; isAllCaps = false; isEnabled = enabled; setOnClickListener { f() }
    }
    fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = 10 }
}

interface ObdTransport {
    val connected: Boolean
    val description: String
    fun transact(cmd: String, timeout: Long = 3500): String
    fun close()
}

class BluetoothSppTransport(private val context: Context) : ObdTransport {
    private var socket: BluetoothSocket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var deviceName = "Bluetooth"
    override val connected get() = socket?.isConnected == true
    override val description get() = deviceName

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        close()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: error("Bluetooth unavailable")
        if (!adapter.isEnabled) error("Turn Bluetooth on first")
        val d = adapter.getRemoteDevice(address)
        deviceName = "Bluetooth: ${d.name ?: address}"
        val s = d.createRfcommSocketToServiceRecord(
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        )
        s.connect()
        socket = s
        input = BufferedInputStream(s.inputStream)
        output = BufferedOutputStream(s.outputStream)
    }

    @Synchronized
    override fun transact(cmd: String, timeout: Long): String {
        if (!connected) error("Bluetooth OBD adapter not connected")
        val i = input ?: error("No input stream")
        val o = output ?: error("No output stream")
        while (i.available() > 0) i.read()
        o.write((cmd.trim() + "\r").toByteArray(Charsets.US_ASCII))
        o.flush()
        val end = System.currentTimeMillis() + timeout
        val sb = StringBuilder()
        while (System.currentTimeMillis() < end) {
            while (i.available() > 0) {
                val b = i.read()
                if (b < 0) break
                val c = b.toChar()
                if (c == '>') return sb.toString()
                sb.append(c)
            }
            Thread.sleep(15)
        }
        return sb.toString()
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null; output = null; socket = null
    }
}

class UsbSerialElmTransport(private val context: Context) : ObdTransport {
    private var port: UsbSerialPort? = null
    private var connection: android.hardware.usb.UsbDeviceConnection? = null
    private var label = "USB"
    override val connected get() = port != null && connection != null
    override val description get() = label

    fun connect(deviceId: Int): Int {
        close()
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            .firstOrNull { it.device.deviceId == deviceId }
            ?: error("No supported USB serial driver found")
        if (!manager.hasPermission(driver.device)) error("USB permission was not granted")
        val conn = manager.openDevice(driver.device) ?: error("Could not open USB device")
        val p = driver.ports.firstOrNull() ?: run { conn.close(); error("USB device has no serial port") }
        p.open(conn)
        val rates = listOf(38400, 115200, 9600, 57600)
        var selected = rates.first()
        var ok = false
        for (baud in rates) {
            runCatching {
                p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                runCatching { p.dtr = true }
                runCatching { p.rts = true }
                p.write("ATI\r".toByteArray(Charsets.US_ASCII), 1000)
                val buf = ByteArray(256)
                val n = p.read(buf, 700)
                val s = if (n > 0) String(buf, 0, n, Charsets.US_ASCII) else ""
                if (s.contains("ELM", true) || s.contains("STN", true) || s.contains(">")) {
                    selected = baud; ok = true
                }
            }
            if (ok) break
        }
        if (!ok) {
            p.setParameters(selected, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }
        connection = conn
        port = p
        label = "USB: VID %04X PID %04X @ %d".format(driver.device.vendorId, driver.device.productId, selected)
        return selected
    }

    @Synchronized
    override fun transact(cmd: String, timeout: Long): String {
        val p = port ?: error("USB OBD adapter not connected")
        val drain = ByteArray(512)
        runCatching { while (p.read(drain, 20) > 0) {} }
        p.write((cmd.trim() + "\r").toByteArray(Charsets.US_ASCII), 1500)
        val end = System.currentTimeMillis() + timeout
        val sb = StringBuilder()
        val buf = ByteArray(512)
        while (System.currentTimeMillis() < end) {
            val n = runCatching { p.read(buf, 100) }.getOrDefault(0)
            if (n > 0) {
                val s = String(buf, 0, n, Charsets.US_ASCII)
                val mark = s.indexOf('>')
                if (mark >= 0) {
                    sb.append(s.substring(0, mark))
                    return sb.toString()
                }
                sb.append(s)
            }
        }
        return sb.toString()
    }

    override fun close() {
        runCatching { port?.close() }
        runCatching { connection?.close() }
        port = null; connection = null
    }
}

data class ObdPid(
    val pid: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val decode: (IntArray) -> Double
) {
    val command get() = "01%02X".format(pid)
    companion object {
        val COMMON = listOf(
            ObdPid(0x0C, "Engine RPM", "rpm", 2) { (it[0] * 256 + it[1]) / 4.0 },
            ObdPid(0x0D, "Vehicle Speed", "mph", 1) { it[0] * 0.621371 },
            ObdPid(0x05, "Coolant Temp", "°F", 1) { (it[0] - 40) * 9.0 / 5.0 + 32.0 },
            ObdPid(0x04, "Engine Load", "%", 1) { it[0] * 100.0 / 255.0 },
            ObdPid(0x11, "Throttle Position", "%", 1) { it[0] * 100.0 / 255.0 },
            ObdPid(0x42, "Module Voltage", "V", 2) { (it[0] * 256 + it[1]) / 1000.0 },
            ObdPid(0x0B, "Manifold Pressure", "kPa", 1) { it[0].toDouble() },
            ObdPid(0x10, "MAF", "g/s", 2) { (it[0] * 256 + it[1]) / 100.0 }
        )
        val RPM = COMMON.first { it.pid == 0x0C }
        val VOLTAGE = COMMON.first { it.pid == 0x42 }
    }
}

data class ScanSnapshot(
    val time: Long,
    val profile: String,
    val vin: String,
    val current: List<String>,
    val pending: List<String>,
    val permanent: List<String>
) {
    fun encode() = listOf(
        time.toString(), profile, vin,
        current.joinToString(","), pending.joinToString(","), permanent.joinToString(",")
    ).joinToString("\n")

    companion object {
        fun decode(s: String?): ScanSnapshot? {
            if (s.isNullOrBlank()) return null
            val p = s.split("\n")
            if (p.size < 6) return null
            fun codes(x: String): List<String> = if (x.isBlank()) emptyList() else x.split(",").filter { it.isNotBlank() }
            return ScanSnapshot(
                p[0].toLongOrNull() ?: 0L, p[1], p[2], codes(p[3]), codes(p[4]), codes(p[5])
            )
        }
    }
}

object SnapshotStore {
    fun save(c: Context, s: ScanSnapshot) = c.getSharedPreferences("obd_scan", Context.MODE_PRIVATE)
        .edit().putString("baseline", s.encode()).apply()
    fun load(c: Context) = ScanSnapshot.decode(
        c.getSharedPreferences("obd_scan", Context.MODE_PRIVATE).getString("baseline", null)
    )
    fun compare(a: ScanSnapshot, b: ScanSnapshot): String {
        val x = (a.current + a.pending + a.permanent).toSet()
        val y = (b.current + b.pending + b.permanent).toSet()
        val n = (y - x).sorted(); val g = (x - y).sorted()
        return "VIN: ${b.vin.ifBlank { "Unknown" }}\n" +
            "NEW CODES: ${if (n.isEmpty()) "None" else n.joinToString()}\n" +
            "MISSING/RESOLVED: ${if (g.isEmpty()) "None" else g.joinToString()}"
    }
}

class ObdManager(private val context: Context) {
    private var transport: ObdTransport? = null
    private val lock = ReentrantLock()
    var adapterId = ""; private set
    var protocol = ""; private set
    var connectionType = ""; private set
    val connected get() = transport?.connected == true

    fun connectBluetooth(address: String): String = lock.withLock {
        disconnectInternal()
        val t = BluetoothSppTransport(context)
        t.connect(address)
        transport = t
        connectionType = t.description
        initialize()
        adapterId
    }

    fun connectUsb(deviceId: Int): String = lock.withLock {
        disconnectInternal()
        val t = UsbSerialElmTransport(context)
        t.connect(deviceId)
        transport = t
        connectionType = t.description
        initialize()
        adapterId
    }

    fun disconnect() = lock.withLock { disconnectInternal() }

    private fun disconnectInternal() {
        transport?.close(); transport = null
        adapterId = ""; protocol = ""; connectionType = ""
    }

    private fun initialize() {
        raw("ATZ", 5000); Thread.sleep(400)
        listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATST64").forEach { raw(it) }
        raw("ATSP0", 5000)
        adapterId = clean(raw("ATI"))
        raw("0100", 6000)
        protocol = clean(raw("ATDP"))
    }

    fun raw(c: String, timeout: Long = 3500): String = lock.withLock {
        (transport ?: error("OBD adapter not connected")).transact(c, timeout)
    }

    fun getVin(): String {
        val chars = mutableListOf<Int>()
        for (line in lines(raw("0902", 6000), "0902")) {
            val b = hex(line); val i = pair(b, 0x49, 0x02)
            if (i >= 0) {
                var s = i + 2
                if (s < b.size && b[s] in 1..9) s++
                for (n in s until b.size) if (b[n] in 0x20..0x7e) chars += b[n]
            }
        }
        val v = chars.map { it.toChar() }.joinToString("").trim()
        return if (v.length >= 17) v.take(17) else v
    }

    fun readPid(p: ObdPid): Double? {
        for (line in lines(raw(p.command), p.command)) {
            val b = hex(line)
            for (i in 0 until b.size - 1) {
                if (b[i] == 0x41 && b[i + 1] == p.pid) {
                    val s = i + 2
                    if (b.size >= s + p.bytes) return p.decode(IntArray(p.bytes) { x -> b[s + x] })
                }
            }
        }
        return null
    }

    fun dtcs(mode: String): List<String> {
        val r = when (mode.uppercase()) { "03" -> 0x43; "07" -> 0x47; "0A" -> 0x4A; else -> error("Bad mode") }
        val p = mutableListOf<Int>()
        for (line in lines(raw(mode, 5000), mode)) {
            val b = hex(line); val i = b.indexOf(r)
            if (i >= 0) p.addAll(b.drop(i + 1))
        }
        val out = mutableListOf<String>(); var i = 0
        while (i + 1 < p.size) {
            val a = p[i]; val b = p[i + 1]; i += 2
            if (a != 0 || b != 0) out += decodeDtc(a, b)
        }
        return out.distinct()
    }

    fun clear() = clean(raw("04", 5000))
    fun snapshot(p: VehicleProfile) = ScanSnapshot(
        System.currentTimeMillis(), p.key,
        runCatching { getVin() }.getOrDefault(""),
        runCatching { dtcs("03") }.getOrDefault(emptyList()),
        runCatching { dtcs("07") }.getOrDefault(emptyList()),
        runCatching { dtcs("0A") }.getOrDefault(emptyList())
    )

    private fun clean(s: String) = s.replace("\r", "\n").lines().map { it.trim() }
        .filter { it.isNotBlank() && !it.contains("SEARCHING", true) }.joinToString(" ")
    private fun lines(s: String, c: String) = s.replace("\r", "\n").lines().map { it.trim() }.filter {
        val n = it.replace(" ", "").uppercase()
        it.isNotBlank() && n != c.replace(" ", "").uppercase() &&
            !it.contains("SEARCHING", true) && !it.contains("NO DATA", true) && !it.contains("STOPPED", true)
    }
    private fun hex(line: String): List<Int> {
        val body = if (line.contains(":")) line.substringAfterLast(":") else line
        val h = body.uppercase().replace(Regex("[^0-9A-F]"), "")
        if (h.length < 2 || h.length % 2 != 0) return emptyList()
        return h.chunked(2).mapNotNull { it.toIntOrNull(16) }
    }
    private fun pair(v: List<Int>, a: Int, b: Int): Int {
        for (i in 0 until v.size - 1) if (v[i] == a && v[i + 1] == b) return i
        return -1
    }
    private fun decodeDtc(a: Int, b: Int): String {
        val p = when ((a and 0xC0) shr 6) { 0 -> 'P'; 1 -> 'C'; 2 -> 'B'; else -> 'U' }
        return "$p${(a and 0x30) shr 4}%X%X%X".format(a and 0x0F, (b and 0xF0) shr 4, b and 0x0F)
    }
}

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val r = Ui.page(this)
        r.addView(Ui.title(this, "OBD2 MULTI-VEHICLE SCANNER v0.3"), Ui.lp())
        r.addView(Ui.text(this, "Bluetooth + USB OBD/VCI connection. Tune Manager added with flash safety preflight."), Ui.lp())
        r.addView(Ui.button(this, "2014 HONDA CR-Z\nHonda / IMA") { open(VehicleProfile.HONDA, false) }, Ui.lp())
        r.addView(Ui.button(this, "2011 VOLVO XC60\nVolvo Diagnostics") { open(VehicleProfile.VOLVO, false) }, Ui.lp())
        r.addView(Ui.button(this, "AUTO-DETECT VEHICLE") { open(VehicleProfile.HONDA, true) }, Ui.lp())
        setContentView(ScrollView(this).apply { addView(r) })
    }
    private fun open(p: VehicleProfile, a: Boolean) = startActivity(
        Intent(this, DashboardActivity::class.java).putExtra("profile", p.key).putExtra("auto", a)
    )
}

class DevicePickerActivity : Activity() {
    private lateinit var root: LinearLayout
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); root = Ui.page(this)
        root.addView(Ui.title(this, "Paired Bluetooth OBD Devices"), Ui.lp())
        root.addView(Ui.text(this, "Pair the adapter in Android Bluetooth settings first if it is not listed."), Ui.lp())
        root.addView(Ui.button(this, "REFRESH") { perm() }, Ui.lp())
        setContentView(ScrollView(this).apply { addView(root) }); perm()
    }
    private fun perm() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 7) else load()
    }
    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == 7 && g.firstOrNull() == PackageManager.PERMISSION_GRANTED) load()
    }
    @SuppressLint("MissingPermission")
    private fun load() {
        val a = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val ds = a.bondedDevices.sortedBy { it.name ?: it.address }
        if (ds.isEmpty()) root.addView(Ui.text(this, "No paired devices found."), Ui.lp())
        for (d: BluetoothDevice in ds) root.addView(Ui.button(this, "${d.name ?: "Unknown"}\n${d.address}") {
            setResult(RESULT_OK, Intent().putExtra("address", d.address)); finish()
        }, Ui.lp())
    }
}

class UsbDevicePickerActivity : Activity() {
    private val actionPermission = "com.keith.obd2scanner.USB_PERMISSION"
    private lateinit var root: LinearLayout
    private lateinit var manager: UsbManager
    private var pendingId: Int? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != actionPermission) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && pendingId != null) {
                setResult(RESULT_OK, Intent().putExtra("deviceId", pendingId!!)); finish()
            } else Toast.makeText(this@UsbDevicePickerActivity, "USB permission denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        manager = getSystemService(Context.USB_SERVICE) as UsbManager
        root = Ui.page(this)
        root.addView(Ui.title(this, "USB OBD / VCI Devices"), Ui.lp())
        root.addView(Ui.text(this, "Use a USB-C cable or OTG adapter. Supported serial families include FTDI, CP210x, CH34x, PL2303 and CDC/ACM."), Ui.lp())
        root.addView(Ui.button(this, "REFRESH USB DEVICES") { load() }, Ui.lp())
        setContentView(ScrollView(this).apply { addView(root) })
        val filter = IntentFilter(actionPermission)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        load()
    }

    private fun load() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (drivers.isEmpty()) {
            root.addView(Ui.text(this, "No supported USB serial device detected."), Ui.lp()); return
        }
        for (d in drivers) {
            val dev = d.device
            val label = "VID %04X  PID %04X\n%s".format(dev.vendorId, dev.productId, dev.deviceName)
            root.addView(Ui.button(this, label) { choose(dev) }, Ui.lp())
        }
    }

    private fun choose(dev: UsbDevice) {
        if (manager.hasPermission(dev)) {
            setResult(RESULT_OK, Intent().putExtra("deviceId", dev.deviceId)); finish(); return
        }
        pendingId = dev.deviceId
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(actionPermission).setPackage(packageName), flags)
        manager.requestPermission(dev, pi)
    }

    override fun onDestroy() { runCatching { unregisterReceiver(receiver) }; super.onDestroy() }
}

class DashboardActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val obd get() = (application as ScannerApp).obd
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var profile: VehicleProfile
    private var auto = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        profile = VehicleProfile.fromKey(intent.getStringExtra("profile")); auto = intent.getBooleanExtra("auto", false)
        val r = Ui.page(this)
        r.addView(Ui.title(this, if (auto) "AUTO-DETECT" else profile.label), Ui.lp())
        status = Ui.text(this, "Adapter: not connected", 16f); info = Ui.text(this, "")
        r.addView(status, Ui.lp()); r.addView(info, Ui.lp())
        r.addView(Ui.button(this, "CONNECT BLUETOOTH OBD") {
            startActivityForResult(Intent(this, DevicePickerActivity::class.java), 20)
        }, Ui.lp())
        r.addView(Ui.button(this, "CONNECT USB OBD / VCI") {
            startActivityForResult(Intent(this, UsbDevicePickerActivity::class.java), 21)
        }, Ui.lp())
        r.addView(Ui.button(this, "READ VIN / VEHICLE INFO") { connected { readVin() } }, Ui.lp())
        r.addView(Ui.button(this, "FULL HEALTH SCAN") { connected { scan() } }, Ui.lp())
        r.addView(Ui.button(this, "READ / CLEAR TROUBLE CODES") { connected { startActivity(Intent(this, DtcActivity::class.java)) } }, Ui.lp())
        r.addView(Ui.button(this, "LIVE DATA") { connected { startActivity(Intent(this, LiveDataActivity::class.java)) } }, Ui.lp())
        r.addView(Ui.button(this, "TUNE MANAGER / ECU FLASH") {
            startActivity(Intent(this, TuneActivity::class.java).putExtra("profile", profile.key))
        }, Ui.lp())
        r.addView(Ui.button(this, if (profile == VehicleProfile.HONDA) "HONDA IMA / HYBRID BATTERY" else "VOLVO ELECTRICAL / MODULES") {
            startActivity(Intent(this, EnhancedActivity::class.java).putExtra("profile", profile.key))
        }, Ui.lp())
        r.addView(Ui.button(this, "BUTTON / SWITCH STATUS") {
            startActivity(Intent(this, EnhancedActivity::class.java).putExtra("profile", profile.key).putExtra("section", "inputs"))
        }, Ui.lp())
        r.addView(Ui.button(this, "ACTIVE TESTS / BIDIRECTIONAL") {
            startActivity(Intent(this, EnhancedActivity::class.java).putExtra("profile", profile.key).putExtra("section", "active"))
        }, Ui.lp())
        r.addView(Ui.button(this, "SAVE BASELINE SCAN") { connected { baseline() } }, Ui.lp())
        r.addView(Ui.button(this, "COMPARE TO BASELINE") { connected { compare() } }, Ui.lp())
        r.addView(Ui.button(this, "SHARE DIAGNOSTIC REPORT") { connected { share() } }, Ui.lp())
        r.addView(Ui.button(this, "DISCONNECT") { obd.disconnect(); refresh() }, Ui.lp())
        setContentView(ScrollView(this).apply { addView(r) }); refresh()
    }

    @Deprecated("deprecated")
    override fun onActivityResult(q: Int, res: Int, d: Intent?) {
        super.onActivityResult(q, res, d)
        if (res != RESULT_OK) return
        if (q == 20) {
            val a = d?.getStringExtra("address") ?: return
            status.text = "Connecting Bluetooth…"
            io.execute {
                val x = runCatching { obd.connectBluetooth(a) }
                main.post { if (x.isSuccess) { refresh(); if (auto) readVin() } else fail(x.exceptionOrNull()) }
            }
        } else if (q == 21) {
            val id = d?.getIntExtra("deviceId", -1) ?: -1
            if (id < 0) return
            status.text = "Connecting USB…"
            io.execute {
                val x = runCatching { obd.connectUsb(id) }
                main.post { if (x.isSuccess) { refresh(); if (auto) readVin() } else fail(x.exceptionOrNull()) }
            }
        }
    }

    private fun fail(e: Throwable?) { status.text = "Connection failed"; dialog("Connection failed", e?.message ?: "Unknown") }
    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() {
        status.text = if (obd.connected) "Adapter: CONNECTED — ${obd.adapterId}\n${obd.connectionType}\nProtocol: ${obd.protocol}" else "Adapter: not connected"
    }
    private fun connected(f: () -> Unit) { if (obd.connected) f() else Toast.makeText(this, "Connect an OBD adapter first", Toast.LENGTH_LONG).show() }
    private fun readVin() {
        status.text = "Reading VIN…"
        io.execute {
            val v = runCatching { obd.getVin() }.getOrDefault("")
            main.post { VehicleProfile.fromVin(v)?.let { if (auto) profile = it }; info.text = "VIN: ${v.ifBlank { "Not returned" }}\nProfile: ${profile.label}"; refresh() }
        }
    }
    private fun scan() { status.text = "Scanning…"; io.execute { val s = obd.snapshot(profile); main.post { refresh(); dialog("Health Scan", report(s) + "\nEnhanced all-module scanning requires verified Honda/Volvo commands and compatible VCI.") } } }
    private fun baseline() { io.execute { SnapshotStore.save(this, obd.snapshot(profile)); main.post { Toast.makeText(this, "Baseline saved", Toast.LENGTH_LONG).show() } } }
    private fun compare() { val old = SnapshotStore.load(this) ?: run { Toast.makeText(this, "Save a baseline first", Toast.LENGTH_LONG).show(); return }; io.execute { val now = obd.snapshot(profile); main.post { dialog("Before / After", SnapshotStore.compare(old, now)) } } }
    private fun share() { io.execute { val txt = report(obd.snapshot(profile)); main.post { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "OBD2 Report - ${profile.label}"); putExtra(Intent.EXTRA_TEXT, txt) }, "Share report")) } } }
    private fun report(s: ScanSnapshot) = buildString {
        appendLine("OBD2 DIAGNOSTIC REPORT"); appendLine("Vehicle: ${VehicleProfile.fromKey(s.profile).label}")
        appendLine("Date: ${DateFormat.getDateTimeInstance().format(Date(s.time))}"); appendLine("VIN: ${s.vin.ifBlank { "Not available" }}")
        appendLine("Adapter: ${obd.adapterId}"); appendLine("Connection: ${obd.connectionType}"); appendLine("Protocol: ${obd.protocol}")
        appendLine("Current: ${if (s.current.isEmpty()) "None" else s.current.joinToString()}")
        appendLine("Pending: ${if (s.pending.isEmpty()) "None" else s.pending.joinToString()}")
        appendLine("Permanent: ${if (s.permanent.isEmpty()) "None" else s.permanent.joinToString()}")
    }
    private fun dialog(t: String, m: String) = AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK", null).show()
}

data class TunePackage(
    val uri: Uri,
    val fileName: String,
    val bytes: ByteArray,
    val sha256: String,
    val manifest: JSONObject?,
    val payload: ByteArray?
) {
    val driver get() = manifest?.optString("flashDriver", "") ?: ""
    val targetProfile get() = manifest?.optString("vehicleProfile", "") ?: ""
    val targetVin get() = manifest?.optString("vin", "") ?: ""
    val calibrationId get() = manifest?.optString("calibrationId", "") ?: ""
}

object TuneLoader {
    fun load(c: Context, uri: Uri): TunePackage {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read tune file")
        require(bytes.size <= 32 * 1024 * 1024) { "Tune file is larger than 32 MB" }
        val name = queryName(c, uri)
        var manifest: JSONObject? = null
        var payload: ByteArray? = null
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when (e.name.lowercase()) {
                        "manifest.json" -> manifest = JSONObject(String(z.readBytes(), Charsets.UTF_8))
                        "payload.bin" -> payload = z.readBytes()
                    }
                    z.closeEntry()
                }
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return TunePackage(uri, name, bytes, hash, manifest, payload)
    }

    private fun queryName(c: Context, uri: Uri): String {
        var n = uri.lastPathSegment ?: "tune"
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) n = cur.getString(0)
        }
        return n
    }
}

class TuneActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val obd get() = (application as ScannerApp).obd
    private lateinit var profile: VehicleProfile
    private lateinit var status: TextView
    private lateinit var applyButton: Button
    private var tune: TunePackage? = null
    private var preflightPassed = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        profile = VehicleProfile.fromKey(intent.getStringExtra("profile"))
        val r = Ui.page(this)
        r.addView(Ui.title(this, "Tune Manager — ${profile.label}"), Ui.lp())
        r.addView(Ui.text(this, "Load a tune package, verify VIN/profile/hash and run the flash preflight before any write is allowed."), Ui.lp())
        status = Ui.text(this, "No tune loaded.", 15f); r.addView(status, Ui.lp())
        r.addView(Ui.button(this, "LOAD TUNE FILE / PACKAGE") { pickTune() }, Ui.lp())
        r.addView(Ui.button(this, "RUN FLASH PRE-FLIGHT") { preflight() }, Ui.lp())
        applyButton = Ui.button(this, "APPLY TUNE — REQUIRES VERIFIED FLASH DRIVER", false) { applyTune() }
        r.addView(applyButton, Ui.lp())
        r.addView(Ui.text(this,
            "Safety locks: engine must be stopped, system voltage must be adequate, VIN/profile must match, and the package must name a flash driver that this app has explicitly verified. Emissions-delete, airbag, brake, steering and immobilizer-disabling tunes are not supported."
        ), Ui.lp())
        setContentView(ScrollView(this).apply { addView(r) })
    }

    private fun pickTune() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, 60)
    }

    @Deprecated("deprecated")
    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req != 60 || result != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        status.text = "Loading tune…"; applyButton.isEnabled = false; preflightPassed = false
        io.execute {
            val x = runCatching { TuneLoader.load(this, uri) }
            main.post {
                if (x.isFailure) status.text = "Tune load error: ${x.exceptionOrNull()?.message}"
                else {
                    tune = x.getOrNull(); val t = tune!!
                    status.text = buildString {
                        appendLine("File: ${t.fileName}"); appendLine("Size: ${t.bytes.size} bytes"); appendLine("SHA-256: ${t.sha256}")
                        if (t.manifest == null) appendLine("Manifest: none — inspection only") else {
                            appendLine("Target profile: ${t.targetProfile.ifBlank { "not specified" }}")
                            appendLine("Target VIN: ${t.targetVin.ifBlank { "not specified" }}")
                            appendLine("Calibration ID: ${t.calibrationId.ifBlank { "not specified" }}")
                            appendLine("Flash driver: ${t.driver.ifBlank { "not specified" }}")
                            appendLine("Payload: ${t.payload?.size ?: 0} bytes")
                        }
                    }
                }
            }
        }
    }

    private fun preflight() {
        val t = tune ?: run { Toast.makeText(this, "Load a tune first", Toast.LENGTH_LONG).show(); return }
        if (!obd.connected) { Toast.makeText(this, "Connect the vehicle first", Toast.LENGTH_LONG).show(); return }
        status.text = "Running flash pre-flight…"; applyButton.isEnabled = false; preflightPassed = false
        io.execute {
            val result = runCatching {
                val vin = obd.getVin()
                val rpm = obd.readPid(ObdPid.RPM)
                val volts = obd.readPid(ObdPid.VOLTAGE)
                val problems = mutableListOf<String>()
                if (rpm == null) problems += "Could not verify engine RPM"
                else if (rpm > 50.0) problems += "Engine is running (${rpm.toInt()} rpm)"
                if (volts == null) problems += "Could not verify control-module voltage"
                else if (volts < 12.4) problems += "Voltage is too low for flashing (${"%.2f".format(volts)} V); use a stable battery support supply"
                if (t.manifest == null) problems += "No manifest.json in package"
                if (t.payload == null) problems += "No payload.bin in package"
                if (t.targetProfile.isNotBlank() && t.targetProfile != profile.key) problems += "Tune profile does not match ${profile.key}"
                if (t.targetVin.isNotBlank() && vin.isNotBlank() && !t.targetVin.equals(vin, true)) problems += "Tune VIN does not match vehicle VIN"
                if (t.driver.isBlank()) problems += "No flashDriver declared"
                Triple(vin, volts, problems)
            }
            main.post {
                if (result.isFailure) status.text = "Pre-flight error: ${result.exceptionOrNull()?.message}"
                else {
                    val (vin, volts, problems) = result.getOrThrow()
                    preflightPassed = problems.isEmpty()
                    val supported = tune?.driver in supportedFlashDrivers()
                    applyButton.isEnabled = preflightPassed && supported
                    status.text = buildString {
                        appendLine("VIN: ${vin.ifBlank { "not returned" }}")
                        appendLine("Voltage: ${volts?.let { "%.2f V".format(it) } ?: "unavailable"}")
                        if (problems.isEmpty()) appendLine("Pre-flight: PASS") else appendLine("Pre-flight: FAIL\n- ${problems.joinToString("\n- ")}")
                        if (!supported) appendLine("Flash driver '${t.driver}' is not installed/verified in this build. File is validated but APPLY remains locked.")
                    }
                }
            }
        }
    }

    private fun supportedFlashDrivers(): Set<String> = emptySet()

    private fun applyTune() {
        val t = tune ?: return
        if (!preflightPassed || t.driver !in supportedFlashDrivers()) return
        AlertDialog.Builder(this)
            .setTitle("Apply tune?")
            .setMessage("This will write ECU calibration data. Do not disconnect USB power or the vehicle battery support supply during programming.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("APPLY") { _, _ ->
                Toast.makeText(this, "No verified flash driver is enabled in this build.", Toast.LENGTH_LONG).show()
            }.show()
    }
}

class DtcActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor(); private val main = Handler(Looper.getMainLooper())
    private val obd get() = (application as ScannerApp).obd; private lateinit var out: TextView
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); val r = Ui.page(this); r.addView(Ui.title(this, "Trouble Codes"), Ui.lp())
        out = Ui.text(this, "Choose code type.", 17f); r.addView(out, Ui.lp())
        r.addView(Ui.button(this, "READ CURRENT CODES") { read("03", "Current") }, Ui.lp())
        r.addView(Ui.button(this, "READ PENDING CODES") { read("07", "Pending") }, Ui.lp())
        r.addView(Ui.button(this, "READ PERMANENT CODES") { read("0A", "Permanent") }, Ui.lp())
        r.addView(Ui.button(this, "CLEAR EMISSIONS DTCs / MIL") { clear() }, Ui.lp())
        setContentView(ScrollView(this).apply { addView(r) })
    }
    private fun read(m: String, l: String) { out.text = "Reading…"; io.execute { val x = runCatching { obd.dtcs(m) }; main.post { out.text = if (x.isSuccess) { val v = x.getOrDefault(emptyList()); "$l codes (${v.size})\n\n${if (v.isEmpty()) "None" else v.joinToString("\n")}" } else "Error: ${x.exceptionOrNull()?.message}" } } }
    private fun clear() { AlertDialog.Builder(this).setTitle("Clear codes?").setMessage("Record codes first. This sends standard OBD service 04 and may clear readiness/freeze-frame data.").setNegativeButton("Cancel", null).setPositiveButton("CLEAR") { _, _ -> io.execute { val x = runCatching { obd.clear() }; main.post { out.text = if (x.isSuccess) "Response: ${x.getOrNull()}\nRe-read codes to verify." else "Error: ${x.exceptionOrNull()?.message}" } } }.show() }
}

class LiveDataActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor(); private val main = Handler(Looper.getMainLooper())
    private val run = AtomicBoolean(false); private val obd get() = (application as ScannerApp).obd
    private val rows = linkedMapOf<ObdPid, TextView>()
    override fun onCreate(b: Bundle?) { super.onCreate(b); val r = Ui.page(this); r.addView(Ui.title(this, "Live OBD Data"), Ui.lp()); for (p in ObdPid.COMMON) { val t = Ui.text(this, "${p.name}: -- ${p.unit}", 17f); rows[p] = t; r.addView(t, Ui.lp()) }; r.addView(Ui.button(this, "START") { start() }, Ui.lp()); r.addView(Ui.button(this, "STOP") { run.set(false) }, Ui.lp()); setContentView(ScrollView(this).apply { addView(r) }) }
    override fun onResume() { super.onResume(); start() }; override fun onPause() { run.set(false); super.onPause() }
    private fun start() { if (!obd.connected || !run.compareAndSet(false, true)) return; io.execute { while (run.get() && obd.connected) { for ((p, t) in rows) { if (!run.get()) break; val v = runCatching { obd.readPid(p) }.getOrNull(); main.post { t.text = if (v == null) "${p.name}: -- ${p.unit}" else "${p.name}: ${if (abs(v) >= 100) "%.0f".format(v) else "%.1f".format(v)} ${p.unit}" } } }; run.set(false) } }
}

class EnhancedActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); val p = VehicleProfile.fromKey(intent.getStringExtra("profile")); val s = intent.getStringExtra("section") ?: "main"
        val r = Ui.page(this); r.addView(Ui.title(this, "${p.label} — ${if (s == "active") "Active Tests" else if (s == "inputs") "Inputs / Switches" else "Enhanced Diagnostics"}"), Ui.lp())
        val items = when {
            p == VehicleProfile.HONDA && s == "main" -> listOf("IMA State of Charge", "IMA Pack Voltage / Current", "Hybrid Battery Temperatures", "Assist / Regeneration", "IMA Battery Fan / Hybrid DTC Data")
            p == VehicleProfile.HONDA && s == "inputs" -> listOf("Brake / Clutch / Accelerator Switches", "Steering-Wheel Buttons", "Door / Hatch / Lock Inputs", "ECON / NORMAL / SPORT / S+ Inputs")
            p == VehicleProfile.HONDA -> listOf("IMA Battery Fan Test", "Cooling Fan Test", "Horn / Lighting / Locks", "Gauge / Indicator Test", "Window Actuation")
            p == VehicleProfile.VOLVO && s == "main" -> listOf("CEM / ECM / TCM / BCM / SRS / DIM / CCM", "DDM / PDM / REM / PAM / PSM / SAS", "Generator Load / Battery Current", "Transmission Temperature / AWD Data", "Module-specific VIDA-style DTCs and Parameters")
            p == VehicleProfile.VOLVO && s == "inputs" -> listOf("Door / Hood / Tailgate Status", "Window / Lock Button Status", "Brake / Steering / Pedal Inputs", "HVAC Buttons / Sensors", "Parking Sensor Inputs")
            else -> listOf("Cooling Fan Low / High", "Fuel Pump / EVAP Tests", "Door Locks / Horn / Lights", "Wipers / Washers / Rear Defroster", "Window Up / Down", "Climate Actuators", "Instrument / Gauge Test")
        }
        for (x in items) r.addView(Ui.button(this, "🔒 $x", false) {}, Ui.lp())
        r.addView(Ui.text(this, "Locked items require a verified model-specific command pack. The app does not transmit guessed active-test commands."), Ui.lp())
        setContentView(ScrollView(this).apply { addView(r) })
    }
}