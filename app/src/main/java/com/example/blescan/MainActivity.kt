package com.example.blescan

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    // ------ BLE setup for JBD BMS (from your BMS.py) ------
    private val JBD_SERVICE = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    private val JBD_READ_CH = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")   // notify
    private val JBD_WRITE_CH = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")  // write
    // Commands
    private val CMD_BASIC_INFO = hex("DD A5 03 00 FF FD 77") // gives V, I, SoC (+ more)
    // Read register helper for Device Name (0xA1)
    private fun cmdReadRegister(reg: Int): ByteArray {
        val regByte = reg and 0xFF
        val len = 0
        // checksum = (0x10000 - sum([reg, len] + data)) & 0xFFFF
        val sum = (regByte + len) and 0xFFFF
        val chk = (0x10000 - sum) and 0xFFFF
        val bb = ByteBuffer.allocate(2 + 2 + 2 + 1) // DD + A5 + reg + len + chk(2) + 77
        // but we need payload [DD A5] [reg len] [chkHi chkLo] [77]
        return byteArrayOf(
            0xDD.toByte(), 0xA5.toByte(),
            regByte.toByte(), 0x00,
            ((chk shr 8) and 0xFF).toByte(), (chk and 0xFF).toByte(),
            0x77.toByte()
        )
    }
    // ------------------------------------------------------

    private val SCAN_MS = 20_000L
    private val PERM_REQUEST = 1001

    // UI
    private lateinit var btnScan: Button
    private lateinit var list: ListView
    private lateinit var tvName: TextView
    private lateinit var tvVolt: TextView
    private lateinit var tvCurr: TextView
    private lateinit var tvSoc: TextView

    private lateinit var adapterLv: ArrayAdapter<String>
    private val allEntries = mutableListOf<String>()               // "MAC  Name"
    private val devices = LinkedHashMap<String, BluetoothDevice>() // MAC -> device

    // BLE
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var chNotify: BluetoothGattCharacteristic? = null
    private var chWrite: BluetoothGattCharacteristic? = null

    // assemble notifications (JBD frames can come in parts)
    private val rxBuffer = ArrayList<Byte>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- simple UI (scan + list + 4 values)
        btnScan = Button(this).apply { text = "Start Scan (20s)" }
        list = ListView(this)
        tvName = TextView(this).apply { textSize = 16f; text = "Name: -" }
        tvVolt = TextView(this).apply { textSize = 16f; text = "Voltage: -" }
        tvCurr = TextView(this).apply { textSize = 16f; text = "Current: -" }
        tvSoc  = TextView(this).apply { textSize = 16f; text = "SOC: -" }

        adapterLv = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapterLv

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(btnScan)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tvName); addView(tvVolt); addView(tvCurr); addView(tvSoc)
        }
        setContentView(layout)

        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        btnScan.setOnClickListener {
            if (checkAndRequestPermissions()) startScan()
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val entry = adapterLv.getItem(pos) ?: return@setOnItemClickListener
            val mac = entry.substringBefore("  ")
            val dev = devices[mac] ?: return@setOnItemClickListener
            connectTo(dev)
        }
    }

    // ---------- permissions ----------
    private fun checkAndRequestPermissions(): Boolean {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!has(Manifest.permission.BLUETOOTH_SCAN)) missing += Manifest.permission.BLUETOOTH_SCAN
            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) missing += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return if (missing.isEmpty()) true
        else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQUEST)
            false
        }
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
                allEntries.add(row)
                adapterLv.add(row)
                adapterLv.notifyDataSetChanged()
            }
        }
        override fun onScanFailed(code: Int) {
            Toast.makeText(this@MainActivity, "Scan failed: $code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScan() {
        if (scanning) return
        val ad = bluetoothAdapter
        if (ad == null || !ad.isEnabled) { Toast.makeText(this, "Turn ON Bluetooth", Toast.LENGTH_SHORT).show(); return }
        devices.clear(); allEntries.clear(); adapterLv.clear()
        tvName.text = "Name: -"; tvVolt.text = "Voltage: -"; tvCurr.text = "Current: -"; tvSoc.text = "SOC: -"

        scanning = true
        Toast.makeText(this, "Scanning ${SCAN_MS/1000}s…", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ stopScan() }, SCAN_MS)
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(null, settings, scanCb)
    }
    private fun stopScan() {
        if (!scanning) return
        scanner?.stopScan(scanCb)
        scanning = false
        Toast.makeText(this, "Scan stopped", Toast.LENGTH_SHORT).show()
    }

    // ---------- connect / services ----------
    private fun connectTo(device: BluetoothDevice) {
        stopScan()
        Toast.makeText(this, "Connecting to ${device.address}…", Toast.LENGTH_SHORT).show()
        gatt?.close()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else
            device.connectGatt(this, false, gattCb)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                Toast.makeText(this@MainActivity,
                    "State: ${stateName(newState)} (status=$status)", Toast.LENGTH_SHORT).show()
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                chNotify = null; chWrite = null; rxBuffer.clear()
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(JBD_SERVICE)
            val notifyCh = svc?.getCharacteristic(JBD_READ_CH)
            val writeCh  = svc?.getCharacteristic(JBD_WRITE_CH)
            chNotify = notifyCh
            chWrite = writeCh

            runOnUiThread {
                if (svc == null || notifyCh == null || writeCh == null) {
                    Toast.makeText(this@MainActivity, "JBD service/characteristics not found", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "JBD service ready", Toast.LENGTH_SHORT).show()
                }
            }

            // enable notifications
            if (notifyCh != null) {
                g.setCharacteristicNotification(notifyCh, true)
                val cccd = notifyCh.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }

            // After services ready, request BASIC INFO and DEVICE NAME
            handler.postDelayed({
                chWrite?.let {
                    // request basic info (for V/I/SOC)
                    g.writeCharacteristic(it.apply {
                        writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        value = CMD_BASIC_INFO
                    })
                    // request device name (EEPROM register 0xA1)
                    g.writeCharacteristic(it.apply {
                        writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        value = cmdReadRegister(0xA1)
                    })
                }
            }, 300)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == JBD_READ_CH) {
                val data = characteristic.value ?: return
                onJbdBytes(data)
            }
        }
    }

    // ---------- JBD frame handling ----------
    private fun onJbdBytes(chunk: ByteArray) {
        // Append and try to extract complete frames between 0xDD ... 0x77 with valid checksum
        synchronized(rxBuffer) {
            chunk.forEach { rxBuffer.add(it) }
            // try to peel multiple frames if present
            while (true) {
                val start = rxBuffer.indexOfFirst { it == 0xDD.toByte() }
                if (start < 0) { rxBuffer.clear(); return }
                if (start > 0) repeat(start) { rxBuffer.removeAt(0) }

                // need at least minimal header DD ?? ?? len ?? ?? 77
                if (rxBuffer.size < 7) return

                // For most JBD responses:
                // [0]=DD, [1]=register, [2]=status(0=ok), [3]=len, [4..4+len-1]=payload, [4+len..4+len+1]=chk, last=77
                val reg = (rxBuffer[1].toInt() and 0xFF)
                val status = (rxBuffer[2].toInt() and 0xFF)
                val length = (rxBuffer[3].toInt() and 0xFF)
                val totalNeeded = 1 + 1 + 1 + 1 + length + 2 + 1 // DD + reg + status + len + payload + checksum(2) + 77
                if (rxBuffer.size < totalNeeded) return

                // slice a frame
                val frame = ByteArray(totalNeeded) { i -> rxBuffer[i] }
                // drop it from buffer
                repeat(totalNeeded) { rxBuffer.removeAt(0) }

                // sanity: end marker
                if (frame.last() != 0x77.toByte()) continue

                // checksum over [status,len,payload]
                val payload = frame.sliceArray(4 until 4 + length)
                val chkHi = frame[4 + length].toInt() and 0xFF
                val chkLo = frame[5 + length].toInt() and 0xFF
                val sum = (status + length + payload.sumOf { it.toInt() and 0xFF }) and 0xFFFF
                val expected = (0x10000 - sum) and 0xFFFF
                val got = (chkHi shl 8) or chkLo
                if (expected != got) continue

                if (status != 0) continue // only handle OK

                when (reg) {
                    0x03 -> handleBasicInfo(payload)   // voltage/current/soc are here
                    0xA1 -> handleDeviceName(payload)  // name
                    else -> { /* ignore others */ }
                }
            }
        }
    }

    // BASIC INFO response payload layout (as in your Python):
    // bytes: [ .. ] voltage(2) current(2 signed) remCap(2) fullCap(2) ... soc(1) fet(1) cellNum(1) ...
    private fun handleBasicInfo(payload: ByteArray) {
        // Guard
        if (payload.size < 24) return
        // positions are relative to whole response in Python, but payload here starts at offset 0
        // In your file, voltage = (data[4]<<8 | data[5]) /100, current signed /100, soc at [23]
        // Our payload starts where their "data[4]" was, so:
        val voltageRaw = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val currentRawU = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        var currentRaw = currentRawU
        if ((currentRaw and 0x8000) != 0) currentRaw = -((currentRaw xor 0xFFFF) + 1) // signed
        val voltage = voltageRaw / 100.0
        val current = currentRaw / 100.0
        val soc = payload[19].toInt() and 0xFF  // aligns to data[23] in full frame

        runOnUiThread {
            tvVolt.text = String.format("Voltage: %.3f V", voltage)
            tvCurr.text = String.format("Current: %.3f A", current)
            tvSoc.text  = "SOC: $soc %"
        }
    }

    // Device name register payload special-case: Python trims one extra length byte; here,
    // payload already excludes JBD header/trailer. We just strip trailing zeros and decode ASCII.
    private fun handleDeviceName(payload: ByteArray) {
        var p = payload
        // Some firmwares include an extra length byte first; if so, drop it.
        if (p.isNotEmpty() && (p[0].toInt() and 0xFF) == (p.size - 1)) {
            p = p.copyOfRange(1, p.size)
        }
        val trimmed = p.dropLastWhile { it == 0.toByte() }.toByteArray()
        val name = try { String(trimmed, Charsets.US_ASCII).trim() } catch (_: Exception) { "" }
        if (name.isNotEmpty()) runOnUiThread { tvName.text = "Name: $name" }
    }

    // ---------- helpers ----------
    private fun hex(s: String): ByteArray =
        s.split(Regex("\\s+")).filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }.toByteArray()

    private fun stateName(s: Int) = when (s) {
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        else -> "$s"
    }

    override fun onDestroy() {
        stopScan()
        gatt?.close()
        super.onDestroy()
    }
}
