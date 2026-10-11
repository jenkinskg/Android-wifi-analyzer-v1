package com.keith.obd2scanner

import android.app.Activity
import android.app.AlertDialog
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class ImaHealthActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val obd get() = (application as ScannerApp).obd

    private lateinit var status: TextView
    private lateinit var output: TextView
    private val live = AtomicBoolean(false)
    private var lastReport = ""

    private val hybridLifePid = ObdPid(
        0x5B,
        "Hybrid Battery Pack Remaining Life",
        "%",
        1,
    ) { it[0] * 100.0 / 255.0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = Ui.page(this)
        root.addView(Ui.title(this, "HONDA CR-Z IMA HEALTH"), Ui.lp())
        root.addView(Ui.text(this,
            "2014 Honda CR-Z hybrid health screen. This uses only values the connected adapter/vehicle actually returns. " +
            "It does not invent Honda HDS data that a generic RLM327 cannot access."
        ), Ui.lp())

        status = Ui.text(this, "Adapter: not connected", 16f)
        output = Ui.text(this, "Connect the car, then run IMA HEALTH SCAN.", 15f)
        root.addView(status, Ui.lp())

        root.addView(Ui.button(this, "CONNECT BLUETOOTH OBD") {
            startActivityForResult(Intent(this, DevicePickerActivity::class.java), 81)
        }, Ui.lp())

        root.addView(Ui.button(this, "RUN IMD HEALTH SCAN") {
            runHealthScan()
        }, Ui.lp())

        root.addView(Ui.button(this, "START LIVE IMA WATCH") {
            startLive()
        }, Ui.lp())

        root.addView(Ui.button(this, "STOP LIVE WATCH") {
            live.set(false)
        }, Ui.lp())

        root.addView(Ui.button(this, "IMA FAN / COOLING STATUS") {
            showFanInfo()
        }, Ui.lp())

        root.addView(Ui.button(this, "SHARE IMA REPORT") {
            shareReport()
        }, Ui.lp())

        root.addView(Ui.button(this, "DISCONNECT") {
            live.set(false)
            obd.disconnect()
            refresh()
        }, Ui.lp())

        root.addView(output, Ui.lp())
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    @Deprecated("Deprecated API")
    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req != 81 || result != RESULT_OK) return
        val address = data?.getStringExtra("address") ?: return
        status.text = "Connecting to $address..."
        io.execute {
            val r = runCatching { obd.connectBluetooth(address) }
            main.post {
                if (r.isSuccess) {
                    refresh()
                    output.text = "Connected. Press RUN IMA HEALTH SCAN."
                } else {
                    status.text = "Connection failed"
                    output.text = "ERROR: ${r.exceptionOrNull()?.message ?: "Unknown error"}"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        live.set(false)
        super.onPause()
    }

    private fun refresh() {
        status.text = if (obd.connected) {
            "CONNECTED\n${obd.connectionType}\nAdapter: ${obd.adapterId}\nProtocol: ${obd.protocol}"
        } else {
            "Adapter: not connected"
        }
    }

    private fun runHealthScan() {
        if (!obd.connected) {
            Toast.makeText(this, "Connect the OBD adapter first", Toast.LENGTH_LONG).show()
            return
        }

        live.set(false)
        status.text = "Running IMA health scan..."
        output.text = "Reading VIN, 12 V supply, hybrid battery PID and trouble codes..."

        io.execute {
            val vin = runCatching { obd.getVin() }.getOrDefault("")
            val voltage = runCatching { obd.readPid(ObdPid.VOLTAGE) }.getOrNull()
            val rpm = runCatching { obd.readPid(ObdPid.RPM) }.getOrNull()
            val remainingLife = runCatching { obd.readPid(hybridLifePid) }.getOrNull()

            val current = runCatching { obd.dtcs("03") }.getOrDefault(emptyList())
            val pending = runCatching { obd.dtcs("07") }.getOrDefault(emptyList())
            val permanent = runCatching { obd.dtcs("0A") }.getOrDefault(emptyList())
            val all = (current + pending + permanent).map { it.uppercase() }.distinct()

            val fanFault = all.any { it == "P1448" || it == "P1449" || it == "P1634" }
            val deterioration = all.any { it == "P0A7F" }
            val hybridFaults = all.filter { code ->
                code.startsWith("P0A") || code.startsWith("P1") ||
                    code == "P1448" || code == "P1449" || code == "P1634" ||
                    code.startsWith("U01") || code.startsWith("U12")
            }

            val assessment = when {
                deterioration -> "BATTERY DETERIORATION FAULT DETECTED"
                fanFault -> "IMA COOLING / FAN FAULT DETECTED"
                hybridFaults.isNotEmpty() -> "HYBRID-RELATED DTCs DETECTED"
                remainingLife != null && remainingLife < 30.0 -> "LOW REPORTED HYBRID BATTERY REMAINING LIFE"
                remainingLife != null -> "NO STANDARD IMA FAULT FOUND; HYBRID-LIFE PID RESPONDED"
                else -> "NO STANDARD IMA FAULT FOUND; DEEP IMA DATA NOT EXPOSED BY THIS OBD LINK"
            }

            val report = buildString {
                appendLine("HONDA CR-Z IMA HEALTH REPORT")
                appendLine("Date: ${DateFormat.getDateTimeInstance().format(Date())}")
                appendLine("VIN: ${vin.ifBlank { "Not returned" }}")
                appendLine("Adapter: ${obd.adapterId}")
                appendLine("Connection: ${obd.connectionType}")
                appendLine("Protocol: ${obd.protocol}")
                appendLine()
                appendLine("12 V MODULE VOLTAGE: ${voltage?.let { ".2f V".format(it) } ?: "Not supported"}")
                appendLine("ENGINE RPM: ${rpm?.let { ".0f rpm".format(it) } ?: "Not supported"}")
                appendLine("HYBRID BATTERY REMAINING LIFE (SAE PID B): ${remainingLife?.let { ".1f %%".format(it) } ?: "Not supported by this vehicle/adapter"}")
                appendLine()
                appendLine("CURRENT DTCs: ${if (current.isEmpty()) "None" else current.joinToString(", ")}")
                appendLine("PENDING DTCs: ${if (pending.isEmpty()) "None" else pending.joinToString(", ")}")
                appendLine("PERMANENT DTCs: ${if (permanent.isEmpty()) "None" else permanent.joinToString(", ")}")
                appendLine()
                appendLine("IMA / HYBRID ASSESSMENT: $assessment")
                if (fanFault) {
                    appendLine("FAN STATUS: A fan/cooling-related fault code is present.")
                } else {
                    appendLine("FAN STATUS: No P1448/P1449/P1634 fan-related fault was returned.")
                    appendLine("NOTE: No fan DTC is not proof that the fan is physically spinning.")
                }
                if (deterioration) {
                    appendLine("BATTERY STATUS: P0A7F indicates battery-module deterioration.")
                }
                if (hybridFaults.isNotEmpty()) {
                    appendLine("HYBRID-RELATED CODES: ${hybridFaults.joinToString(", ")}")
                }
                appendLine()
                appendLine("DEEP HDS-LEVEL VALUES")
                appendLine("Battery pack voltage/current: not available through verified generic ELM327 commands in this build")
                appendLine("IMA battery temperatures: not available through verified generic ELM327 commands in this build")
                appendLine("Assist/regen current: not available through verified generic ELM327 commands in this build")
                appendLine("IPU fan RPM/duty: not available through verified generic ELM327 commands in this build")
                appendLine("Fan command test: disabled until the exact CR-Z MCM/HDS command is verified")
            }

            lastReport = report
            main.post {
                refresh()
                output.text = report
            }
        }
    }

    private fun startLive() {
        if (!obd.connected) {
            Toast.makeText(this, "Connect the OBD adapter first", Toast.LENGTH_LONG).show()
            return
        }
        if (!live.compareAndSet(false, true)) return

        output.text = "Live IMA watch started..."
        io.execute {
            while (live.get() && obd.connected) {
                val voltage = runCatching { obd.readPid(ObdPid.VOLTAGE) }.getOrNull()
                val rpm = runCatching { obd.readPid(ObdPid.RPM) }.getOrNull()
                val life = runCatching { obd.readPid(hybridLifePid) }.getOrNull()

                val text = buildString {
                    appendLine("LIVE IMA WATCH")
                    appendLine("12 V module voltage: ${voltage?.let { "..2f V".format(it) } ?: "--"}")
                    appendLine("Engine RPM: ${rpm?.let { "..0f".format(it) } ?: "--"}")
                    appendLine("Hybrid battery remaining life PID 5B: ${life?.let { "..1f %%".format(it) } ?: "not supported"}")
                    appendLine()
                    appendLine("Deep pack voltage/current, battery temperatures and IPU 6fan speed require Honda-specific MCM data access.")
                }
                main.post { output.text = text }
                Thread.sleep(1500)
            }
            live.set(false)
        }
    }

    private fun showFanInfo() {
        AlertDialog.Builder(this)
            .setTitle("IMA / IPU Fan")
            .setMessage(
                "This build checks for CR-Z fan/cooling-related trouble codes such as P1448, P1449 and P1634. " +
                "Honda HDS can perform an IPU fan drive test and read deeper MCM data, but the exact CR-Z command is not verified for a generic ELM327, so this app does not send a guessed fan-control command.\n\n" +
                "Run IMA HEALTH SCAN first. If a fan-related code appears, the report will flag it."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun shareReport() {
        if (lastReport.isBlank()) {
            Toast.makeText(this, "Run IMA HEALTH SCAN first", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Honda CR-Z IMA Health Report")
            putExtra(Intent.EXTRA_TEXT, lastReport)
        }, "Share IMA report"))
    }
}
