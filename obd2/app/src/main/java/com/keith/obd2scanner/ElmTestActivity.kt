package com.keith.obd2scanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class ElmTestActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val obd get() = (application as ScannerApp).obd

    private lateinit var status: TextView
    private lateinit var results: TextView
    private var lastReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = Ui.page(this)
        root.addView(Ui.title(this, "ELM327 INTERFACE TEST"), Ui.lp())
        root.addView(Ui.text(this,
            "Use this with the Bluetooth ELM327 plugged into the vehicle. " +
            "The test is read-only except for normal ELM setup commands."
        ), Ui.lp())

        status = Ui.text(this, "Adapter: not connected", 16f)
        results = Ui.text(this, "No test has been run yet.", 15f)
        root.addView(status, Ui.lp())

        root.addView(Ui.button(this, "1. CONNECT BLUETOOTH ELM327") {
            startActivityForResult(Intent(this, DevicePickerActivity::class.java), 71)
        }, Ui.lp())

        root.addView(Ui.button(this, "2. RUN COMPLETE ELM327 TEST") {
            runTest()
        }, Ui.lp())

        root.addView(Ui.button(this, "SHARE TEST REPORT") {
            if (lastReport.isBlank()) {
                Toast.makeText(this, "Run the test first", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "ELM327 Interface Test")
                    putExtra(Intent.EXTRA_TEXT, lastReport)
                }, "Share ELM327 report"))
            }
        }, Ui.lp())

        root.addView(Ui.button(this, "DISCONNECT") {
            obd.disconnect()
            refresh()
        }, Ui.lp())

        root.addView(results, Ui.lp())
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    @Deprecated("Deprecated API")
    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req != 71 || result != RESULT_OK) return
        val address = data?.getStringExtra("address") ?: return
        status.text = "Connecting to $address..."
        results.text = "Opening Bluetooth SPP connection..."
        io.execute {
            val r = runCatching { obd.connectBluetooth(address) }
            main.post {
                if (r.isSuccess) {
                    refresh()
                    results.text = "Connected. Press RUN COMPLETE ELM327 TEST."
                } else {
                    status.text = "Connection failed"
                    results.text = "ERROR: ${r.exceptionOrNull()?.message ?: "Unknown error"}"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.text = if (obd.connected) {
            "CONNECTED\n${obd.connectionType}\nAdapter: ${obd.adapterId}\nProtocol: ${obd.protocol}"
        } else {
            "Adapter: not connected"
        }
    }

    private data class TestItem(
        val command: String,
        val purpose: String,
        val timeout: Long = 4000,
        val pauseAfterMs: Long = 0,
        val evaluator: (String) -> Boolean
    )

    private fun runTest() {
        if (!obd.connected) {
            Toast.makeText(this, "Connect the ELM327 first", Toast.LENGTH_LONG).show()
            return
        }

        status.text = "Running ELM327 test..."
        results.text = "Testing adapter. Keep ignition ON.\n"
        lastReport = ""

        io.execute {
            val tests = listOf(
                TestItem("ATZ", "Reset adapter", 6000, 800) { goodAt(it) },
                TestItem("ATI", "Adapter identification") { r ->
                    goodAt(r) && (r.contains("ELM", true) || r.contains("STN", true) || r.isNotBlank())
                },
                TestItem("ATE0", "Echo off") { okReply(it) },
                TestItem("ATL0", "Linefeeds off") { okReply(it) },
                TestItem("ATS0", "Spaces off") { okReply(it) },
                TestItem("ATH0", "Headers off") { okReply(it) },
                TestItem("ATAT1", "Adaptive timing") { okReply(it) },
                TestItem("ATSP0", "Automatic protocol selection", 5000) { okReply(it) },
                TestItem("0100", "ECU / OBD-II communication", 7000) { normalized(it).contains("4100") },
                TestItem("ATDP", "Detected vehicle protocol") { r -> goodAt(r) && !r.contains("?") },
                TestItem("ATRV", "Vehicle / adapter supply voltage") { r ->
                    Regex("""\d{1,2}(\.\d+)?\s*V""", RegexOption.IGNORE_CASE).containsMatchIn(r)
                },
                TestItem("010C", "Engine RPM PID") { normalized(it).contains("410C") },
                TestItem("010D", "Vehicle speed PID") { normalized(it).contains("410D") },
                TestItem("0105", "Coolant temperature PID") { normalized(it).contains("4105") },
                TestItem("0902", "VIN PID", 7000) { normalized(it).contains("4902") }
            )

            val report = StringBuilder()
            report.appendLine("ELM327 INTERFACE TEST")
            report.appendLine("Date: ${DateFormat.getDateTimeInstance().format(Date())}")
            report.appendLine("Connection: ${obd.connectionType}")
            report.appendLine()

            var pass = 0
            var fail = 0
            var adapterId = ""
            var protocol = ""
            var voltage = ""
            var vinRaw = ""

            for ((index, t) in tests.withIndex()) {
                main.post {
                    results.text = "Running ${index + 1}/${tests.size}: ${t.command} — ${t.purpose}"
                }

                val rawResult = runCatching { obd.raw(t.command, t.timeout) }
                val raw = rawResult.getOrElse { "ERROR: ${it.message}" }.trim()
                if (t.pauseAfterMs > 0) Thread.sleep(t.pauseAfterMs)

                val passed = rawResult.isSuccess && runCatching { t.evaluator(raw) }.getOrDefault(false)
                if (passed) pass++ else fail++

                if (t.command == "ATI") adapterId = cleanOneLine(raw)
                if (t.command == "ATDP") protocol = cleanOneLine(raw)
                if (t.command == "ATRV") voltage = cleanOneLine(raw)
                if (t.command == "0902") vinRaw = cleanOneLine(raw)

                report.appendLine("${if (passed) "PASS" else "FAIL"}  ${t.command}  ${t.purpose}")
                report.appendLine("      ${cleanOneLine(raw).ifBlank { "<no response>" }}")
            }

            runCatching { obd.raw("ATE0") }
            runCatching { obd.raw("ATL0") }
            runCatching { obd.raw("ATS0") }
            runCatching { obd.raw("ATH0") }
            runCatching { obd.raw("ATSP0", 5000) }

            report.appendLine()
            report.appendLine("SUMMARY: $pass passed / $fail failed")
            report.appendLine("Adapter ID: ${adapterId.ifBlank { "Not identified" }}")
            report.appendLine("Protocol: ${protocol.ifBlank { "Not detected" }}")
            report.appendLine("Voltage: ${voltage.ifBlank { "Not returned" }}")
            report.appendLine("VIN response: ${vinRaw.ifBlank { "Not returned" }}")
            report.appendLine()
            if (report.toString().contains("PASS  0100")) {
                report.appendLine("Vehicle ECU communication succeeded.")
            } else {
                report.appendLine("No standard OBD-II ECU response was confirmed.")
            }
            report.appendLine(
                "This test does not prove the adapter is safe for ECU flashing. " +
                "ELM327 adapters are intended here for diagnostics/read-only testing."
            )

            lastReport = report.toString()
            main.post {
                refresh()
                results.text = lastReport
            }
        }
    }

    private fun goodAt(r: String): Boolean {
        val x = r.trim()
        return x.isNotBlank() &&
            !x.contains("ERROR", true) &&
            !x.contains("UNABLE TO CONNECT", true) &&
            !x.contains("NO DATA", true)
    }

    private fun okReply(r: String): Boolean {
        val x = r.uppercase()
        return x.contains("OK") || (goodAt(r) && !x.contains("?"))
    }

    private fun normalized(r: String) =
        r.uppercase().replace(Regex("[^0-9A-Z]"), "")

    private fun cleanOneLine(r: String) =
        r.replace("\r", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
}
