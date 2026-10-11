package com.keith.obd2scanner

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CanSnifferActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val logLock = Any()

    private var socket: BluetoothSocket? = null
    private var input: BufferedInputStream? = null
    private var outputStream: BufferedOutputStream? = null
    private var writer: BufferedWriter? = null
    private var logFile: File? = null
    private var deviceLabel = ""
    private var frameCount = 0L
    private var rawLineCount = 0L
    private var lastUi = 0L
    private val preview = ArrayDeque<String>()

    private lateinit var status: TextView
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.page(this)
        root.addView(Ui.title(this, "CR-Z IMA CAN SNIFFER"), Ui.lp())
        root.addView(Ui.text(this,
            "Use an OBD-II Y-splitter: XTOOL IP900BT on one side and the ELM327 on the other. " +
            "This screen configures the ELM for passive CAN Monitor All capture on the CR-Z 11-bit/500 kbps bus."
        ), Ui.lp())

        status = Ui.text(this, "Not connected", 16f)
        view = Ui.text(this, "No capture yet.", 13f)
        root.addView(status, Ui.lp())

        root.addView(Ui.button(this, "1. SELECT / CONNECT ELM327") {
            startActivityForResult(Intent(this, DevicePickerActivity::class.java), 90)
        }, Ui.lp())

        root.addView(Ui.button(this, "2. START PASSIVE CAN SNIFF") { startCapture() }, Ui.lp())
        root.addView(Ui.button(this, "MARK: IMA BATTERY SCREEN") { mark("IMA BATTERY SCREEN") }, Ui.lp())
        root.addView(Ui.button(this, "MARK: BATTERY TEMPERATURES") { mark("BATTERY TEMPERATURES") }, Ui.lp())
        root.addView(Ui.button(this, "MARK: IMA FAN / COOLING") { mark("IMA FAN / COOLING") }, Ui.lp())
        root.addView(Ui.button(this, "MARK: ASSIST / REGEN") { mark("ASSIST / REGEN") }, Ui.lp())
        root.addView(Ui.button(this, "MARK: XTOOL SCREEN CHANGED") { mark("XTOOL SCREEN CHANGED") }, Ui.lp())
        root.addView(Ui.button(this, "3. STOP CAPTURE") { stopCapture(false) }, Ui.lp())
        root.addView(Ui.button(this, "4. EXPORT LOG AS TXT") { exportLog() }, Ui.lp())
        root.addView(Ui.button(this, "DISCONNECT ELM327") { stopCapture(true) }, Ui.lp())

        root.addView(Ui.text(this,
            "Tip: start the sniffer first. Then open one XTOOL IMA value at a time and press the matching MARK button. " +
            "Hold each XTOOOL screen for about 10 seconds so repeated requests are easy to identify."
       ), Ui.lp())
        root.addView(view, Ui.lp())
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    @Deprecated("Deprecated API")
    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        when {
            req == 90 && result == RESULT_OK -> {
                val address = data?.getStringExtra("address") ?: return
                connect(address)
            }
            req == 92 && result == RESULT_OK -> {
                val uri = data?.data ?: return
                val src = logFile ?: return
                runCatching {
                    contentResolver.openOutputStream(uri, "w")!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                }.onSuccess {
                    Toast.makeText(this, "TXT log exported", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun connect(address: String) {
        stopCapture(true)
        status.text = "Connecting to $address..."
        io.execute {
            val r = runCatching {
                val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: error("Bluetooth unavailable")
                if (!adapter.isEnabled) error("Turn Bluetooth on first")
                val dev = adapter.getRemoteDevice(address)
                val s = dev.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                )
                s.connect()
                socket = s
                input = BufferedInputStream(s.inputStream)
                outputStream = BufferedOutputStream(s.outputStream)
                deviceLabel = "${dev.name ?: "ELM327"} ($address)"

                sendAt("ATZ", 5500)
                Thread.sleep(500)
                sendAt("ATE0")
                sendAt("ATL1")
                sendAt("ATS1")
                sendAt("ATH1")
                sendAt("ATCAF0")
                sendAt("ATAL")
                sendAt("ATSP6", 4500)
                val id = sendAt("ATI")
                val dp = sendAt("ATDP")
                Pair(id, dp)
            }
            main.post {
                if (r.isSuccess) {
                    val (id, dp) = r.getOrThrow()
                    status.text = "CONNECTED: $deviceLabel\n${clean(id)}\n${clean(dp)}\nReady to sniff."
                    view.text = "ELM configured for Honda CR-Z CAN 11/500. Press START PASSIVE CAN SNIFF."
                } else {
                    status.text = "Connection failed"
                    view.text = r.exceptionOrNull()?.message ?: "Unknown error"
                    closeSocket()
                }
            }
        }
    }

    private fun startCapture() {
        if (socket?.isConnected != true || input == null || outputStream == null) {
            Toast.makeText(this, "Connect the ELM327 first", Toast.LENGTH_LONG).show()
            return
        }
        if (!running.compareAndSet(false, true)) return

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        logFile = File(filesDir, "CRZ_IMA_CAN_$stamp.txt")
        writer = BufferedWriter(FileWriter(logFile!!, false))
        frameCount = 0
        rawLineCount = 0
        preview.clear()
        appendLog("# CR-Z IMA CAN SNIFFER")
        appendLog("# Started: ${timeLong()}")
        appendLog("# ELM: $deviceLabel")
        appendLog("# Protocol: ISO 15765-4 CAN 11-bit 500 kbps (ATSP6)")
        appendLog("# Passive monitor: ATMA")
        appendLog("# XTOOL should be on the other side of an OBD-II Y-splitter")
        appendLog("# Format: timestamp | raw ELM CAN line")
        appendLog("#")

        status.text = "CAPTURING... Frames: 0"
        view.text = "Starting ATMA passive monitor..."

        io.execute {
            try {
                drainInput()
                val out = outputStream ?: error("No Bluetooth output stream")
                out.write("ATMA\r".toByteArray(Charsets.US_ASCII))
                out.flush()
                val inp = input ?: error("No Bluetooth input stream")
                val line = StringBuilder()
                while (running.get() && socket?.isConnected == true) {
                    val b = inp.read()
                    if (b < 0) break
                    val ch = b.toChar()
                    if (ch == '\r' || ch == '\n' || ch == '>') {
                        if (line.isNotEmpty()) {
                            handleRawLine(line.toString())
                            line.setLength(0)
                        }
                    } else if (ch.code in 32..126) {
                        line.append(ch)
                        if (line.length > 512) {
                            handleRawLine(line.toString())
                            line.setLength(0)
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) appendLog("${timeShort()} | ERROR | ${e.message}")
            } finally {
                running.set(false)
                synchronized(logLock) { writer?.flush() }
                main.post { refresh() }
            }
        }
    }

    private fun handleRawLine(raw: String) {
        val line = raw.trim()
        if (line.isBlank()) return
        if (line.equals("ATMA", true)) return
        if (line.equals("OK", true)) return
        if (line.startsWith("SEARCHING", true)) return

        rawLineCount++
        if (looksLikeCanFrame(line)) frameCount++
        val logged = "${timeShort()} | $line"
        appendLog(logged)
        preview.addLast(logged)
        while (preview.size > 24) preview.removeFirst()

        val now = System.currentTimeMillis()
        if (now - lastUi >= 350) {
            lastUi = now
            val text = preview.joinToString("\n")
            main.post {
                status.text = "CAPTURING... CAN frames: $frameCount   raw lines: $rawLineCount"
                view.text = text
            }
        }
    }

    private fun mark(label: String) {
        if (!running.get()) {
            Toast.makeText(this, "Start capture first", Toast.LENGTH_SHORT).show()
            return
        }
        val line = "${timeShort()} | ### MARK: $label ###"
        appendLog(line)
        preview.addLast(line)
        while (preview.size > 24) preview.removeFirst()
        view.text = preview.joinToString("\n")
        Toast.makeText(this, "Marked: $label", Toast.LENGTH_SHORT).show()
    }

    private fun stopCapture(disconnect: Boolean) {
        val wasRunning = running.getAndSet(false)
        if (wasRunning) {
            runCatching {
                outputStream?.write("\r".toByteArray(Charsets.US_ASCII))
                outputStream?.flush()
            }
            appendLog("${timeShort()} | ### CAPTURE STOPPED ###")
        }
        synchronized(logLock) {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }
        if (disconnect) closeSocket()
        refresh()
    }

    private fun exportLog() {
        stopCapture(false)
        val f = logFile
        if (f == null || !f.exists() || f.length() == 0L) {
            Toast.makeText(this, "Run a capture first", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, f.name)
        }, 92)
    }

    private fun refresh() {
        val connected = socket?.isConnected == true
        status.text = when {
            running.get() -> "CAPTURING... CAN frames: $frameCount   raw lines: $rawLineCount"
            connected -> "CONNECTED: $deviceLabel\nCapture stopped. Frames: $frameCount"
            else -> "Not connected"
        }
        val f = logFile
        if (!running.get() && f != null && f.exists()) {
            view.text = "Capture file: ${f.name}\nFrames: $frameCount\nRaw lines: $rawLineCount\n\nPress EXPORT LOG AS TXT to save it where you can send it."
        }
    }

    private fun appendLog(s: String) {
        synchronized(logLock) {
            writer?.apply {
                write(s)
                newLine()
                flush()
            }
        }
    }

    private fun looksLikeCanFrame(s: String): Boolean {
        val first = s.trim().substringBefore(' ').uppercase(Locale.US)
        return first.matches(Regex("[0-9A-F]{3}")) || first.matches(Regex("[0-9A-F]{8}"))
    }

    private fun sendAt(cmd: String, timeout: Long = 2500): String {
        val inp = input ?: error("No input")
        val out = outputStream ?: error("No output")
        drainInput()
        out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()
        val end = System.currentTimeMillis() + timeout
        val sb = StringBuilder()
        while (System.currentTimeMillis() < end) {
            while (inp.available() > 0) {
                val b = inp.read()
                if (b < 0) break
                val c = b.toChar()
                if (c == '>') return sb.toString()
                sb.append(c)
            }
            Thread.sleep(12)
        }
        return sb.toString()
    }

    private fun drainInput() {
        val inp = input ?: return
        while (inp.available() > 0) inp.read()
    }

    private fun closeSocket() {
        running.set(false)
        synchronized(logLock) {
            runCatching { writer?.close() }
            writer = null
        }
        runCatching { input?.close() }
        runCatching { outputStream?.close() }
        runCatching { socket?.close() }
        input = null
        outputStream = null
        socket = null
    }

    private fun clean(s: String) = s.replace("\r", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
    private fun timeShort() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    private fun timeLong() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    override fun onDestroy() {
        stopCapture(true)
        io.shutdownNow()
        super.onDestroy()
    }
}
