package com.example.blescan

import android.Manifest
import android.bluetooth.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * CLEAN MeterActivity with NO scanner code or scan UI.
 * - Expects "mac" and "name" extras from ScanActivity
 * - Auto-connects and reads Amitis/JBD basic info
 */
class MeterActivity : AppCompatActivity() {

    // ---- UUIDs (short -> full) ----
    private fun uuid16(short: String) = UUID.fromString("${short}-0000-1000-8000-00805f9b34fb")
    private val AMITIS_SERVICE = uuid16("0000ff00")
    private val AMITIS_READ_CH = uuid16("0000ff01")
    private val AMITIS_WRITE_CH = uuid16("0000ff02")
    private val CMD_BASIC_INFO = hex("DD A5 03 00 FF FD 77")

    // ---- UI ----
    private lateinit var tvHeader: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvVolt: TextView
    private lateinit var tvCurr: TextView
    private lateinit var tvSoc: TextView
    private lateinit var tvTemp: TextView

    // ---- BLE state ----
    private var gatt: BluetoothGatt? = null
    private var chNotify: BluetoothGattCharacteristic? = null
    private var chWrite: BluetoothGattCharacteristic? = null
    private val rxBuffer = ArrayList<Byte>()
    private val handler = Handler(Looper.getMainLooper())

    private val REQ_CONNECT = 2101
    private var pendingMac: String? = null

    private val pollIntervalMs = 1000L
    private val pollTask = object : Runnable {
        override fun run() {
            val w = chWrite
            val g = gatt
            if (w != null && g != null) {
                try {
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    w.value = CMD_BASIC_INFO
                    g.writeCharacteristic(w)
                } catch (_: SecurityException) {
                    runOnUiThread { tvStatus.text = "Status: missing BLUETOOTH_CONNECT" }
                }
            }
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Very small safe UI (no XML ids referenced)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        tvHeader = TextView(this).apply { textSize = 18f; setTextColor(Color.BLACK) }
        tvStatus = TextView(this).apply { text = "Status: idle" }
        tvVolt = TextView(this).apply { text = "Voltage: -" }
        tvCurr = TextView(this).apply { text = "Current: -" }
        tvSoc  = TextView(this).apply { text = "SOC: -" }
        tvTemp = TextView(this).apply { text = "Temp: -" }
        val btnBack = Button(this).apply { text = "← Back"; setOnClickListener { finish() } }

        listOf(tvHeader, tvStatus, tvVolt, tvCurr, tvSoc, tvTemp, btnBack).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })

        // Get extras from ScanActivity
        val mac  = intent.getStringExtra("mac")
        val name = intent.getStringExtra("name") ?: "Unknown"
        tvHeader.text = "Device: $name  ($mac)"

        if (mac.isNullOrBlank()) {
            toast("No device MAC provided"); finish(); return
        }

        // Android 12+ runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            pendingMac = mac
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_CONNECT)
        } else {
            connect(mac)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CONNECT) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                pendingMac?.let { connect(it) }
                pendingMac = null
            } else {
                toast("BLUETOOTH_CONNECT permission is required"); finish()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollTask)
        gatt?.close()
        gatt = null
        super.onDestroy()
    }

    // ---- Connect & GATT ----
    private fun connect(mac: String) {
        tvStatus.text = "Status: connecting…"
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) { toast("Bluetooth unavailable"); finish(); return }
        try {
            val device = adapter.getRemoteDevice(mac)
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            else
                device.connectGatt(this, false, gattCb)
        } catch (e: Exception) {
            toast("Connect error: ${e.message}"); finish()
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { tvStatus.text = "Status: connected (discovering…)" }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread { tvStatus.text = "Status: disconnected" }
                handler.removeCallbacks(pollTask); chNotify = null; chWrite = null; rxBuffer.clear(); g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(AMITIS_SERVICE)
            if (svc == null) {
                runOnUiThread { tvStatus.text = "Status: Amitis service not found" }
                return
            }
            chNotify = svc.getCharacteristic(AMITIS_READ_CH)
            chWrite  = svc.getCharacteristic(AMITIS_WRITE_CH)
            if (chNotify == null || chWrite == null) {
                runOnUiThread { tvStatus.text = "Status: characteristics missing" }
                return
            }
            try {
                g.setCharacteristicNotification(chNotify, true)
                val cccd = chNotify!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            } catch (_: SecurityException) {
                runOnUiThread { tvStatus.text = "Status: missing BLUETOOTH_CONNECT" }
                return
            }
            handler.removeCallbacks(pollTask); handler.postDelayed(pollTask, 300)
            runOnUiThread { tvStatus.text = "Status: reading…" }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == AMITIS_READ_CH) onAmitisBytes(ch.value ?: return)
        }
    }

    // ---- Protocol parsing: JBD/Amitis "basic info" ----
    private fun onAmitisBytes(chunk: ByteArray) {
        synchronized(rxBuffer) {
            chunk.forEach { rxBuffer.add(it) }
            while (true) {
                val start = rxBuffer.indexOfFirst { it == 0xDD.toByte() }
                if (start < 0) { rxBuffer.clear(); return }
                if (start > 0) repeat(start) { rxBuffer.removeAt(0) }
                if (rxBuffer.size < 7) return

                val cmd = (rxBuffer[1].toInt() and 0xFF)
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

                if (cmd == 0x03) handleBasicInfo(payload)
            }
        }
    }

    private fun handleBasicInfo(p: ByteArray) {
        if (p.size < 29) return
        val vRaw = ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)
        val iU = ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)
        var iS = iU; if ((iS and 0x8000) != 0) iS = -((iS xor 0xFFFF) + 1)
        val voltage = vRaw / 100.0; val current = iS / 100.0
        val soc = (p[23].toInt() and 0xFF).coerceIn(0,100)

        var tempC: Double? = null
        if (p.size > 28) {
            val ntcCount = p[26].toInt() and 0xFF
            if (ntcCount > 0) {
                val rawT = ((p[27].toInt() and 0xFF) shl 8) or (p[28].toInt() and 0xFF)
                tempC = (rawT - 2731.5) / 10.0
            }
        }

        runOnUiThread {
            tvVolt.text = "Voltage: %.3f V".format(voltage)
            tvCurr.text = "Current: %.3f A".format(current)
            tvSoc.text  = "SOC: %d %%".format(soc)
            tvTemp.text = if (tempC != null) "Temp: %.1f °C".format(tempC) else "Temp: -"
        }
    }

    // ---- Utils ----
    private fun hex(s: String): ByteArray =
        s.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
