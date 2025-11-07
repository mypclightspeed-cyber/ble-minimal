package com.example.blescan

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    // -------- Scan config --------
    private val SCAN_MS = 20_000L
    private val PERM_REQUEST = 1001

    // -------- JBD UUIDs & commands (notify/write in FF00 service) --------
    private val JBD_SERVICE = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    private val JBD_READ_CH = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")   // notify
    private val JBD_WRITE_CH = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")  // write
    private val CMD_BASIC_INFO = hex("DD A5 03 00 FF FD 77") // voltage/current/soc

    // GAP generic access → Device Name
    private val GAP_SERVICE = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    private val GAP_DEVICE_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

    private fun cmdReadRegister(reg: Int): ByteArray {
        val r = reg and 0xFF
        val chk = (0x10000 - ((r + 0) and 0xFFFF)) and 0xFFFF
        return byteArrayOf(
            0xDD.toByte(), 0xA5.toByte(),
            r.toByte(), 0x00,
            ((chk shr 8) and 0xFF).toByte(), (chk and 0xFF).toByte(),
            0x77.toByte()
        )
    }

    // -------- UI --------
    private lateinit var btnScan: Button
    private lateinit var list: ListView

    // cards
    private lateinit var cardName: LinearLayout
    private lateinit var cardVolt: LinearLayout
    private lateinit var cardCurr: LinearLayout
    private lateinit var cardSoc: LinearLayout

    // fields
    private lateinit var tvName: TextView
    private lateinit var tvVolt: TextView
    private lateinit var tvCurr: TextView
    private lateinit var tvSoc: TextView

    // gauge
    private lateinit var socBar: ProgressBar

    private lateinit var adapterLv: ArrayAdapter<String>
    private val rows = mutableListOf<String>()                     // "MAC  Name"
    private val devices = LinkedHashMap<String, BluetoothDevice>() // MAC -> device
    private val advertisedName = HashMap<String, String>()         // MAC -> name from scan

    // -------- BLE --------
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var chNotify: BluetoothGattCharacteristic? = null
    private var chWrite: BluetoothGattCharacteristic? = null

    // JBD frame reassembly
    private val rxBuffer = ArrayList<Byte>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---------- build UI programmatically ----------
        // your logo at the top
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo) // <-- uses res/drawable/logo.png
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                160
            ).apply {
                setMargins(16, 16, 16, 8)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        btnScan = Button(this).apply {
            text = "Start Scan (20s)"
        }
        list = ListView(this)

        fun makeCard(title: String, bgHex: String): Pair<LinearLayout, TextView> {
            val bgColor = Color.parseColor(bgHex)
            val outer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                setBackgroundColor(bgColor)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(16, 12, 16, 12)
                layoutParams = lp
                elevation = 6f
            }
            val label = TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val value = TextView(this).apply {
                text = "-"
                setTextColor(Color.WHITE)
                textSize = 20f
                setPadding(0, 6, 0, 0)
            }
            outer.addView(label)
            outer.addView(value)
            return outer to value
        }

        val (cardNameView, tvNameView) = makeCard("Name", "#3B82F6")     // blue
        val (cardVoltView, tvVoltView) = makeCard("Voltage (V)", "#10B981") // green
        val (cardCurrView, tvCurrView) = makeCard("Current (A)", "#F59E0B") // amber
        val (cardSocView,  tvSocView)  = makeCard("SOC (%)",    "#8B5CF6") // violet

        cardName = cardNameView; tvName = tvNameView
        cardVolt = cardVoltView; tvVolt = tvVoltView
        cardCurr = cardCurrView; tvCurr = tvCurrView
        cardSoc  = cardSocView;  tvSoc  = tvSocView

        // SOC gauge
        socBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                22
            )
            lp.setMargins(0, 12, 0, 0)
            layoutParams = lp
            progressDrawable = resources.getDrawable(android.R.drawable.progress_horizontal, theme)
        }
        cardSoc.addView(socBar)

        // main container
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(logo)
            addView(btnScan)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(cardName)
            addView(cardVolt)
            addView(cardCurr)
            addView(cardSoc)
        }

        setContentView(top)
        // -----------------------------------------------

        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        adapterLv = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapterLv

        btnScan.setOnClickListener { if (checkAndRequestPermissions()) startScan() }

        list.setOnItemClickListener { _, _, pos, _ ->
            val entry = adapterLv.getItem(pos) ?: return@setOnItemClickListener
            val mac = entry.substringBefore("  ")
            val dev = devices[mac] ?: return@setOnItemClickListener
            connectTo(dev, advertisedName[mac] ?: "Unknown")
        }
    }

    // ---------- permissions ----------
    private fun checkAndRequestPermissions(): Boolean {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!has(Manifest.permission.BLUETOOTH_SCAN)) need += Manifest.permission.BLUETOOTH_SCAN
            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) need += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return if (need.isEmpty()) true
        else { ActivityCompat.requestPermissions(this, need.toTypedArray(), PERM_REQUEST); false }
    }
    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == PERM_REQUEST && r.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
        else Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show()
    }

    // ---------- scan ----------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, res: ScanResult) {
            val dev = res.device
            val name = dev.name ?: res.scanRecord?.deviceName ?: "Unknown"
            val row = "${dev.address}  $name"
            if (!devices.containsKey(dev.address)) {
                devices[dev.address] = dev
                advertisedName[dev.address] = name
                rows.add(row)
                adapterLv.add(row)
                adapterLv.notifyDataSetChanged()
            }
        }
        override fun onScanFailed(code: Int) { toast("Scan failed: $code") }
    }

    private fun startScan() {
        if (scanning) return
        val ad = bluetoothAdapter
        if (ad == null || !ad.isEnabled) { toast("Turn ON Bluetooth"); return }

        // reset UI
        devices.clear(); rows.clear(); adapterLv.clear(); advertisedName.clear()
        setName("-"); setVoltage(0.0); setCurrent(0.0); setSoc(0)

        scanning = true
        toast("Scanning for ${SCAN_MS/1000}s…")
        handler.postDelayed({
            stopScan()
            toast("Scan done: ${rows.size} device(s) found")
        }, SCAN_MS)

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(null, settings, scanCb)
    }

    private fun stopScan() {
        if (!scanning) return
        scanner?.stopScan(scanCb)
        scanning = false
    }

    // ---------- connect / services ----------
    private fun connectTo(device: BluetoothDevice, nameFromScan: String) {
        stopScan()
        toast("Connecting to ${device.address}…")
        gatt?.close()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else
            device.connectGatt(this, false, gattCb)

        // fallback show
        setName(nameFromScan)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread { toast("State: ${stateName(newState)} (status=$status)") }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                chNotify = null; chWrite = null; rxBuffer.clear()
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // GAP device name
            g.getService(GAP_SERVICE)?.getCharacteristic(GAP_DEVICE_NAME)?.let { gapNameCh ->
                if ((gapNameCh.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                    g.readCharacteristic(gapNameCh)
                }
            }

            // JBD service
            val svc = g.getService(JBD_SERVICE)
            chNotify = svc?.getCharacteristic(JBD_READ_CH)
            chWrite  = svc?.getCharacteristic(JBD_WRITE_CH)
            runOnUiThread {
                if (svc == null || chNotify == null || chWrite == null) {
                    toast("JBD FF00/FF01/FF02 not found")
                } else toast("JBD service ready")
            }

            // enable notifications
            chNotify?.let { notifyCh ->
                g.setCharacteristicNotification(notifyCh, true)
                val cccd = notifyCh.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }

            // Ask JBD: basic info + EEPROM name
            handler.postDelayed({
                chWrite?.let { w ->
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    w.value = CMD_BASIC_INFO; g.writeCharacteristic(w)
                    w.value = cmdReadRegister(0xA1); g.writeCharacteristic(w)
                }
            }, 300)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && ch.uuid == GAP_DEVICE_NAME) {
                val s = try { String(ch.value ?: byteArrayOf(), Charsets.UTF_8).trim() } catch (_: Exception) { "" }
                if (s.isNotEmpty()) runOnUiThread { setName(s) }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == JBD_READ_CH) {
                onJbdBytes(characteristic.value ?: return)
            }
        }
    }

    // ---------- JBD frame handling ----------
    private fun onJbdBytes(chunk: ByteArray) {
        synchronized(rxBuffer) {
            chunk.forEach { rxBuffer.add(it) }
            while (true) {
                val start = rxBuffer.indexOfFirst { it == 0xDD.toByte() }
                if (start < 0) { rxBuffer.clear(); return }
                if (start > 0) repeat(start) { rxBuffer.removeAt(0) }
                if (rxBuffer.size < 7) return

                val reg = (rxBuffer[1].toInt() and 0xFF)
                val status = (rxBuffer[2].toInt() and 0xFF)
                val length = (rxBuffer[3].toInt() and 0xFF)
                val total = 1 + 1 + 1 + 1 + length + 2 + 1
                if (rxBuffer.size < total) return

                val frame = ByteArray(total) { i -> rxBuffer[i] }
                repeat(total) { rxBuffer.removeAt(0) }
                if (frame.last() != 0x77.toByte()) continue

                val payload = frame.sliceArray(4 until 4 + length)
                val chkHi = frame[4 + length].toInt() and 0xFF
                val chkLo = frame[5 + length].toInt() and 0xFF
                val sum = (status + length + payload.sumOf { it.toInt() and 0xFF }) and 0xFFFF
                val expected = (0x10000 - sum) and 0xFFFF
                val got = (chkHi shl 8) or chkLo
                if (expected != got || status != 0) continue

                when (reg) {
                    0x03 -> handleBasicInfo(payload)
                    0xA1 -> handleDeviceName(payload)
                }
            }
        }
    }

    // payload layout: voltage(2) current(2s) ... soc(1) at offset 19 in payload
    private fun handleBasicInfo(p: ByteArray) {
        if (p.size < 24) return
        val vRaw = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val iRawU = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        var iRaw = iRawU
        if ((iRaw and 0x8000) != 0) iRaw = -((iRaw xor 0xFFFF) + 1)
        val voltage = vRaw / 100.0
        val current = iRaw / 100.0
        val soc = p[19].toInt() and 0xFF
        runOnUiThread {
            setVoltage(voltage)
            setCurrent(current)
            setSoc(soc)
        }
    }

    private fun handleDeviceName(p0: ByteArray) {
        var p = p0
        if (p.isNotEmpty() && (p[0].toInt() and 0xFF) == (p.size - 1)) p = p.copyOfRange(1, p.size)
        val name = try { String(p.dropLastWhile { it == 0.toByte() }.toByteArray(), Charsets.US_ASCII).trim() } catch (_: Exception) { "" }
        if (name.isNotEmpty()) runOnUiThread { setName(name) }
    }

    // ---------- pretty UI setters ----------
    private fun setName(s: String) {
        tvName.text = s
    }

    private fun setVoltage(v: Double) {
        tvVolt.text = String.format("%.3f V", v)
        val shade = when {
            v >= 54 -> "#059669"
            v >= 48 -> "#10B981"
            else    -> "#34D399"
        }
        cardVolt.setBackgroundColor(Color.parseColor(shade))
    }

    private fun setCurrent(a: Double) {
        tvCurr.text = String.format("%.3f A", a)
        val col = if (a >= 0) "#F59E0B" else "#EF4444"
        cardCurr.setBackgroundColor(Color.parseColor(col))
    }

    private fun setSoc(soc: Int) {
        val clamp = min(100, max(0, soc))
        tvSoc.text = "$clamp %"
        socBar.progress = clamp
        val color = when {
            clamp >= 80 -> Color.parseColor("#22C55E")
            clamp >= 30 -> Color.parseColor("#F59E0B")
            else        -> Color.parseColor("#EF4444")
        }
        socBar.progressDrawable.setTint(color)
    }

    // ---------- helpers ----------
    private fun hex(s: String): ByteArray =
        s.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun stateName(s: Int) = when (s) {
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        else -> "$s"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopScan()
        gatt?.close()
        super.onDestroy()
    }
}
