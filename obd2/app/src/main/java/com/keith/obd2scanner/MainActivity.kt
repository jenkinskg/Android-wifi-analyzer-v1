package com.keith.obd2scanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

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
        orientation = LinearLayout.VERTICAL; setPadding(28,36,28,36); setBackgroundColor(Color.rgb(245,247,250))
    }
    fun title(c: Context, s: String) = TextView(c).apply { text=s; textSize=24f; setTextColor(Color.BLACK) }
    fun text(c: Context, s: String, z: Float=15f) = TextView(c).apply { text=s; textSize=z; setPadding(0,8,0,8) }
    fun button(c: Context, s: String, enabled: Boolean=true, f:()->Unit) = Button(c).apply {
        text=s; isAllCaps=false; isEnabled=enabled; setOnClickListener { f() }
    }
    fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=10 }
}

class BluetoothSppTransport(private val context: Context) {
    private var socket: BluetoothSocket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    val connected get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        close()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: error("Bluetooth unavailable")
        if (!adapter.isEnabled) error("Turn Bluetooth on first")
        val d = adapter.getRemoteDevice(address)
        val s = d.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
        s.connect(); socket=s; input=BufferedInputStream(s.inputStream); output=BufferedOutputStream(s.outputStream)
    }

    @Synchronized fun transact(cmd: String, timeout: Long=3500): String {
        if (!connected) error("OBD adapter not connected")
        val i=input ?: error("No input"); val o=output ?: error("No output")
        while (i.available()>0) i.read()
        o.write((cmd.trim()+"\r").toByteArray(Charsets.US_ASCII)); o.flush()
        val end=System.currentTimeMillis()+timeout; val sb=StringBuilder()
        while (System.currentTimeMillis()<end) {
            while (i.available()>0) { val b=i.read(); if (b<0) break; val c=b.toChar(); if (c=='>') return sb.toString(); sb.append(c) }
            Thread.sleep(15)
        }
        return sb.toString()
    }
    fun close() { runCatching{input?.close()}; runCatching{output?.close()}; runCatching{socket?.close()}; input=null; output=null; socket=null }
}

data class ObdPid(val pid:Int,val name:String,val unit:String,val bytes:Int,val decode:(IntArray)->Double) {
    val command get()="01%02X".format(pid)
    companion object { val COMMON=listOf(
        ObdPid(0x0C,"Engine RPM","rpm",2){(it[0]*256+it[1])/4.0},
        ObdPid(0x0D,"Vehicle Speed","mph",1){it[0]*0.621371},
        ObdPid(0x05,"Coolant Temp","°F",1){(it[0]-40)*9.0/5.0+32.0},
        ObdPid(0x04,"Engine Load","%",1){it[0]*100.0/255.0},
        ObdPid(0x11,"Throttle Position","%",1){it[0]*100.0/255.0},
        ObdPid(0x42,"Module Voltage","V",2){(it[0]*256+it[1])/1000.0},
        ObdPid(0x0B,"Manifold Pressure","kPa",1){it[0].toDouble()},
        ObdPid(0x10,"MAF","g/s",2){(it[0]*256+it[1])/100.0}
    ) }
}

data class ScanSnapshot(val time:Long,val profile:String,val vin:String,val current:List<String>,val pending:List<String>,val permanent:List<String>) {
    fun encode()=listOf(time.toString(),profile,vin,current.joinToString(","),pending.joinToString(","),permanent.joinToString(",")).joinToString("\n")
    companion object { fun decode(s:String?):ScanSnapshot? { if(s.isNullOrBlank())return null; val p=s.split("\n"); if(p.size<6)return null; fun c(x:String)=if(x.isBlank()) emptyList() else x.split(","); return ScanSnapshot(p[0].toLongOrNull()?:0,p[1],p[2],c(p[3]),c(p[4]),c(p[5])) } }
}

object SnapshotStore {
    fun save(c:Context,s:ScanSnapshot)=c.getSharedPreferences("obd_scan",Context.MODE_PRIVATE).edit().putString("baseline",s.encode()).apply()
    fun load(c:Context)=ScanSnapshot.decode(c.getSharedPreferences("obd_scan",Context.MODE_PRIVATE).getString("baseline",null))
    fun compare(a:ScanSnapshot,b:ScanSnapshot):String { val x=(a.current+a.pending+a.permanent).toSet(); val y=(b.current+b.pending+b.permanent).toSet(); val n=(y-x).sorted(); val g=(x-y).sorted(); return "VIN: ${b.vin.ifBlank{"Unknown"}}\nNEW CODES: ${if(n.isEmpty())"None" else n.joinToString()}\nMISSING/RESOLVED: ${if(g.isEmpty())"None" else g.joinToString()}" }
}

class ObdManager(context:Context) {
    private val t=BluetoothSppTransport(context); private val lock=ReentrantLock()
    var adapterId=""; private set
    var protocol=""; private set
    val connected get()=t.connected
    fun connect(a:String):String=lock.withLock { t.connect(a); init(); adapterId }
    fun disconnect()=lock.withLock { t.close(); adapterId=""; protocol="" }
    private fun init(){ raw("ATZ",5000); Thread.sleep(400); listOf("ATE0","ATL0","ATS0","ATH0","ATAT1","ATST64").forEach{raw(it)}; raw("ATSP0",5000); adapterId=clean(raw("ATI")); raw("0100",6000); protocol=clean(raw("ATDP")) }
    fun raw(c:String,timeout:Long=3500)=lock.withLock { t.transact(c,timeout) }
    fun getVin():String { val chars=mutableListOf<Int>(); for(line in lines(raw("0902",6000),"0902")){ val b=hex(line); val i=pair(b,0x49,0x02); if(i>=0){ var s=i+2; if(s<b.size && b[s] in 1..9)s++; for(n in s until b.size) if(b[n] in 0x20..0x7e) chars+=b[n] } }; val v=chars.map{it.toChar()}.joinToString("").trim(); return if(v.length>=17)v.take(17) else v }
    fun readPid(p:ObdPid):Double? { for(line in lines(raw(p.command),p.command)){ val b=hex(line); for(i in 0 until b.size-1) if(b[i]==0x41 && b[i+1]==p.pid){ val s=i+2; if(b.size>=s+p.bytes) return p.decode(IntArray(p.bytes){x->b[s+x]}) } }; return null }
    fun dtcs(mode:String):List<String>{ val r=when(mode.uppercase()){ "03"->0x43;"07"->0x47;"0A"->0x4A;else->error("Bad mode")}; val p=mutableListOf<Int>(); for(line in lines(raw(mode,5000),mode)){ val b=hex(line); val i=b.indexOf(r); if(i>=0)p.addAll(b.drop(i+1)) }; val out=mutableListOf<String>(); var i=0; while(i+1<p.size){val a=p[i];val b=p[i+1];i+=2;if(a!=0||b!=0)out+=decodeDtc(a,b)};return out.distinct() }
    fun clear()=clean(raw("04",5000))
    fun snapshot(p:VehicleProfile)=ScanSnapshot(System.currentTimeMillis(),p.key,runCatching{getVin()}.getOrDefault(""),runCatching{dtcs("03")}.getOrDefault(emptyList()),runCatching{dtcs("07")}.getOrDefault(emptyList()),runCatching{dtcs("0A")}.getOrDefault(emptyList()))
    private fun clean(s:String)=s.replace("\r","\n").lines().map{it.trim()}.filter{it.isNotBlank()&&!it.contains("SEARCHING",true)}.joinToString(" ")
    private fun lines(s:String,c:String)=s.replace("\r","\n").lines().map{it.trim()}.filter{val n=it.replace(" ","").uppercase();it.isNotBlank()&&n!=c.replace(" ","").uppercase()&&!it.contains("SEARCHING",true)&&!it.contains("NO DATA",true)&&!it.contains("STOPPED",true)}
    private fun hex(line:String):List<Int>{val body=if(line.contains(":"))line.substringAfterLast(":")else line;val h=body.uppercase().replace(Regex("[^0-9A-F]"),"");if(h.length<2||h.length%2!=0)return emptyList();return h.chunked(2).mapNotNull{it.toIntOrNull(16)}}
    private fun pair(v:List<Int>,a:Int,b:Int):Int{for(i in 0 until v.size-1)if(v[i]==a&&v[i+1]==b)return i;return -1}
    private fun decodeDtc(a:Int,b:Int):String{val p=when((a and 0xC0) shr 6){0->'P';1->'C';2->'B';else->'U'};return "$p${(a and 0x30) shr 4}%X%X%X".format(a and 0x0F,(b and 0xF0) shr 4,b and 0x0F)}
}

class MainActivity:Activity(){
    override fun onCreate(b:Bundle?){super.onCreate(b);val r=Ui.page(this);r.addView(Ui.title(this,"OBD2 MULTI-VEHICLE SCANNER"),Ui.lp());r.addView(Ui.text(this,"Choose vehicle or use VIN auto-detect."),Ui.lp());r.addView(Ui.button(this,"2014 HONDA CR-Z\nHonda / IMA"){open(VehicleProfile.HONDA,false)},Ui.lp());r.addView(Ui.button(this,"2011 VOLVO XC60\nVolvo Diagnostics"){open(VehicleProfile.VOLVO,false)},Ui.lp());r.addView(Ui.button(this,"AUTO-DETECT VEHICLE"){open(VehicleProfile.HONDA,true)},Ui.lp());r.addView(Ui.text(this,"Generic OBD-II is active. Honda IMA, Volvo all-module and bidirectional controls are locked until exact model-specific commands are verified."),Ui.lp());setContentView(ScrollView(this).apply{addView(r)})}
    private fun open(p:VehicleProfile,a:Boolean)=startActivity(Intent(this,DashboardActivity::class.java).putExtra("profile",p.key).putExtra("auto",a))
}

class DevicePickerActivity:Activity(){
    private lateinit var root:LinearLayout
    override fun onCreate(b:Bundle?){super.onCreate(b);root=Ui.page(this);root.addView(Ui.title(this,"Paired OBD Bluetooth Devices"),Ui.lp());root.addView(Ui.text(this,"Pair the adapter in Android Bluetooth settings first if it is not listed."),Ui.lp());root.addView(Ui.button(this,"REFRESH"){perm()},Ui.lp());setContentView(ScrollView(this).apply{addView(root)});perm()}
    private fun perm(){if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT),7)else load()}
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==7&&g.firstOrNull()==PackageManager.PERMISSION_GRANTED)load()}
    @SuppressLint("MissingPermission") private fun load(){val a=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?:return;val ds=a.bondedDevices.sortedBy{it.name?:it.address};if(ds.isEmpty())root.addView(Ui.text(this,"No paired devices found."),Ui.lp());for(d:BluetoothDevice in ds)root.addView(Ui.button(this,"${d.name?:"Unknown"}\n${d.address}"){setResult(RESULT_OK,Intent().putExtra("address",d.address));finish()},Ui.lp())}
}

class DashboardActivity:Activity(){
    private val io=Executors.newSingleThreadExecutor();private val main=Handler(Looper.getMainLooper());private val obd get()=(application as ScannerApp).obd;private lateinit var status:TextView;private lateinit var info:TextView;private lateinit var profile:VehicleProfile;private var auto=false
    override fun onCreate(b:Bundle?){super.onCreate(b);profile=VehicleProfile.fromKey(intent.getStringExtra("profile"));auto=intent.getBooleanExtra("auto",false);val r=Ui.page(this);r.addView(Ui.title(this,if(auto)"AUTO-DETECT" else profile.label),Ui.lp());status=Ui.text(this,"Adapter: not connected",16f);info=Ui.text(this,"");r.addView(status,Ui.lp());r.addView(info,Ui.lp());r.addView(Ui.button(this,"CONNECT / SELECT OBD ADAPTER"){startActivityForResult(Intent(this,DevicePickerActivity::class.java),20)},Ui.lp());r.addView(Ui.button(this,"READ VIN / VEHICLE INFO"){connected{readVin()}},Ui.lp());r.addView(Ui.button(this,"FULL HEALTH SCAN"){connected{scan()}},Ui.lp());r.addView(Ui.button(this,"READ / CLEAR TROUBLE CODES"){connected{startActivity(Intent(this,DtcActivity::class.java))}},Ui.lp());r.addView(Ui.button(this,"LIVE DATA"){connected{startActivity(Intent(this,LiveDataActivity::class.java))}},Ui.lp());r.addView(Ui.button(this,if(profile==VehicleProfile.HONDA)"HONDA IMA / HYBRID BATTERY" else "VOLVO ELECTRICAL / MODULES"){startActivity(Intent(this,EnhancedActivity::class.java).putExtra("profile",profile.key))},Ui.lp());r.addView(Ui.button(this,"BUTTON / SWITCH STATUS"){startActivity(Intent(this,EnhancedActivity::class.java).putExtra("profile",profile.key).putExtra("section","inputs"))},Ui.lp());r.addView(Ui.button(this,"ACTIVE TESTS / BIDIRECTIONAL"){startActivity(Intent(this,EnhancedActivity::class.java).putExtra("profile",profile.key).putExtra("section","active"))},Ui.lp());r.addView(Ui.button(this,"SAVE BASELINE SCAN"){connected{baseline()}},Ui.lp());r.addView(Ui.button(this,"COMPARE TO BASELINE"){connected{compare()}},Ui.lp());r.addView(Ui.button(this,"SHARE DIAGNOSTIC REPORT"){connected{share()}},Ui.lp());r.addView(Ui.button(this,"DISCONNECT"){obd.disconnect();refresh()},Ui.lp());setContentView(ScrollView(this).apply{addView(r)});refresh()}
    @Deprecated("deprecated") override fun onActivityResult(q:Int,res:Int,d:Intent?){super.onActivityResult(q,res,d);if(q==20&&res==RESULT_OK){val a=d?.getStringExtra("address")?:return;status.text="Connecting…";io.execute{val x=runCatching{obd.connect(a)};main.post{if(x.isSuccess){refresh();if(auto)readVin()}else{status.text="Connection failed";dialog("Connection failed",x.exceptionOrNull()?.message?:"Unknown")}}}}}
    override fun onResume(){super.onResume();refresh()}
    private fun refresh(){status.text=if(obd.connected)"Adapter: CONNECTED — ${obd.adapterId}\nProtocol: ${obd.protocol}" else "Adapter: not connected"}
    private fun connected(f:()->Unit){if(obd.connected)f()else Toast.makeText(this,"Connect an OBD adapter first",Toast.LENGTH_LONG).show()}
    private fun readVin(){status.text="Reading VIN…";io.execute{val v=runCatching{obd.getVin()}.getOrDefault("");main.post{VehicleProfile.fromVin(v)?.let{if(auto)profile=it};info.text="VIN: ${v.ifBlank{"Not returned"}}\nProfile: ${profile.label}";refresh()}}}
    private fun scan(){status.text="Scanning…";io.execute{val s=obd.snapshot(profile);main.post{refresh();dialog("Health Scan",report(s)+"\nEnhanced all-module scanning requires verified Honda/Volvo commands and compatible VCI.")}}}
    private fun baseline(){io.execute{SnapshotStore.save(this,obd.snapshot(profile));main.post{Toast.makeText(this,"Baseline saved",Toast.LENGTH_LONG).show()}}}
    private fun compare(){val old=SnapshotStore.load(this)?:run{Toast.makeText(this,"Save a baseline first",Toast.LENGTH_LONG).show();return};io.execute{val now=obd.snapshot(profile);main.post{dialog("Before / After",SnapshotStore.compare(old,now))}}}
    private fun share(){io.execute{val txt=report(obd.snapshot(profile));main.post{startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_SUBJECT,"OBD2 Report - ${profile.label}");putExtra(Intent.EXTRA_TEXT,txt)},"Share report"))}}}
    private fun report(s:ScanSnapshot)=buildString{appendLine("OBD2 DIAGNOSTIC REPORT");appendLine("Vehicle: ${VehicleProfile.fromKey(s.profile).label}");appendLine("Date: ${DateFormat.getDateTimeInstance().format(Date(s.time))}");appendLine("VIN: ${s.vin.ifBlank{"Not available"}}");appendLine("Adapter: ${obd.adapterId}");appendLine("Protocol: ${obd.protocol}");appendLine("Current: ${if(s.current.isEmpty())"None" else s.current.joinToString()}");appendLine("Pending: ${if(s.pending.isEmpty())"None" else s.pending.joinToString()}");appendLine("Permanent: ${if(s.permanent.isEmpty())"None" else s.permanent.joinToString()}")}
    private fun dialog(t:String,m:String)=AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK",null).show()
}

class DtcActivity:Activity(){
    private val io=Executors.newSingleThreadExecutor();private val main=Handler(Looper.getMainLooper());private val obd get()=(application as ScannerApp).obd;private lateinit var out:TextView
    override fun onCreate(b:Bundle?){super.onCreate(b);val r=Ui.page(this);r.addView(Ui.title(this,"Trouble Codes"),Ui.lp());out=Ui.text(this,"Choose code type.",17f);r.addView(out,Ui.lp());r.addView(Ui.button(this,"READ CURRENT CODES"){read("03","Current")},Ui.lp());r.addView(Ui.button(this,"READ PENDING CODES"){read("07","Pending")},Ui.lp());r.addView(Ui.button(this,"READ PERMANENT CODES"){read("0A","Permanent")},Ui.lp());r.addView(Ui.button(this,"CLEAR EMISSIONS DTCs / MIL"){clear()},Ui.lp());setContentView(ScrollView(this).apply{addView(r)})}
    private fun read(m:String,l:String){out.text="Reading…";io.execute{val x=runCatching{obd.dtcs(m)};main.post{out.text=if(x.isSuccess){val v=x.getOrDefault(emptyList());"$l codes (${v.size})\n\n${if(v.isEmpty())"None" else v.joinToString("\n")}"}else"Error: ${x.exceptionOrNull()?.message}"}}}
    private fun clear(){AlertDialog.Builder(this).setTitle("Clear codes?").setMessage("Record codes first. This sends standard OBD service 04 and may clear readiness/freeze-frame data.").setNegativeButton("Cancel",null).setPositiveButton("CLEAR"){_,_->io.execute{val x=runCatching{obd.clear()};main.post{out.text=if(x.isSuccess)"Response: ${x.getOrNull()}\nRe-read codes to verify." else "Error: ${x.exceptionOrNull()?.message}"}}}.show()}
}

class LiveDataActivity:Activity(){
    private val io=Executors.newSingleThreadExecutor();private val main=Handler(Looper.getMainLooper());private val run=AtomicBoolean(false);private val obd get()=(application as ScannerApp).obd;private val rows=linkedMapOf<ObdPid,TextView>()
    override fun onCreate(b:Bundle?){super.onCreate(b);val r=Ui.page(this);r.addView(Ui.title(this,"Live OBD Data"),Ui.lp());for(p in ObdPid.COMMON){val t=Ui.text(this,"${p.name}: -- ${p.unit}",17f);rows[p]=t;r.addView(t,Ui.lp())};r.addView(Ui.button(this,"START"){start()},Ui.lp());r.addView(Ui.button(this,"STOP"){run.set(false)},Ui.lp());setContentView(ScrollView(this).apply{addView(r)})}
    override fun onResume(){super.onResume();start()};override fun onPause(){run.set(false);super.onPause()}
    private fun start(){if(!obd.connected||!run.compareAndSet(false,true))return;io.execute{while(run.get()&&obd.connected){for((p,t) in rows){if(!run.get())break;val v=runCatching{obd.readPid(p)}.getOrNull();main.post{t.text=if(v==null)"${p.name}: -- ${p.unit}" else "${p.name}: ${if(abs(v)>=100)"%.0f".format(v) else "%.1f".format(v)} ${p.unit}"}}};run.set(false)}}
}

class EnhancedActivity:Activity(){
    override fun onCreate(b:Bundle?){super.onCreate(b);val p=VehicleProfile.fromKey(intent.getStringExtra("profile"));val s=intent.getStringExtra("section")?:"main";val r=Ui.page(this);r.addView(Ui.title(this,"${p.label} — ${if(s=="active")"Active Tests" else if(s=="inputs")"Inputs / Switches" else "Enhanced Diagnostics"}"),Ui.lp());val items=when{p==VehicleProfile.HONDA&&s=="main"->listOf("IMA State of Charge","IMA Pack Voltage / Current","Hybrid Battery Temperatures","Assist / Regeneration","IMA Battery Fan / Hybrid DTC Data");p==VehicleProfile.HONDA&&s=="inputs"->listOf("Brake / Clutch / Accelerator Switches","Steering-Wheel Buttons","Door / Hatch / Lock Inputs","ECON / NORMAL / SPORT / S+ Inputs");p==VehicleProfile.HONDA->listOf("IMA Battery Fan Test","Cooling Fan Test","Horn / Lighting / Locks","Gauge / Indicator Test","Window Actuation");p==VehicleProfile.VOLVO&&s=="main"->listOf("CEM / ECM / TCM / BCM / SRS / DIM / CCM","DDM / PDM / REM / PAM / PSM / SAS","Generator Load / Battery Current","Transmission Temperature / AWD Data","Module-specific VIDA-style DTCs and Parameters");p==VehicleProfile.VOLVO&&s=="inputs"->listOf("Door / Hood / Tailgate Status","Window / Lock Button Status","Brake / Steering / Pedal Inputs","HVAC Buttons / Sensors","Parking Sensor Inputs");else->listOf("Cooling Fan Low / High","Fuel Pump / EVAP Tests","Door Locks / Horn / Lights","Wipers / Washers / Rear Defroster","Window Up / Down","Climate Actuators","Instrument / Gauge Test")};for(x in items)r.addView(Ui.button(this,"🔒 $x",false){},Ui.lp());r.addView(Ui.text(this,"Locked items require a verified model-specific command pack. The app does not transmit guessed active-test commands."),Ui.lp());setContentView(ScrollView(this).apply{addView(r)})}
}