package com.example.blescan

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import java.util.UUID
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    // --- tweak if needed ---
    private val SCAN_PERIOD_MS = 20_000L
    private val CHUNK_SIZE = 20
    // file send service/characteristic (optional — replace with yours)
    private val SERVICE_UUID_SEND = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    private val CHARACTERISTIC_UUID_SEND = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
    // Device Information Service & characteristics
    private val DIS_SERVICE = uuid16(0x180A)
    private val DIS_CHARS: List<Pair<String, UUID>> = listOf(
        "Manufacturer" to uuid16(0x2A29),
        "Model Number" to uuid16(0x2A24),
        "Serial Number" to uuid16(0x2A25),
        "Hardware Rev" to uuid16(0x2A27),
        "Firmware Rev" to uuid16(0x2A26),
        "Software Rev" to uuid16(0x2A28),
        "System ID"     to uuid16(0x2A23)
    )
    // ------------------------

    private val PERM_REQUEST = 1001
    private lateinit var adapterLv: ArrayAdapter<String>
    private val devices = LinkedHashMap<String, BluetoothDevice>() // address -> device
    private val allEntries = mutableListOf<String>()               // "MAC  Name" master list
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    // UI refs
    private lateinit var btnReadInfo: Button

    // GATT state
    private var currentGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var connectTimeoutRunnable: Runnable? = null
    private var triedAltTransport = false

    // DIS read queue
    private val disQueue: ArrayDeque<Pair<String, BluetoothGattCharacteristic>> = ArrayDeque()
    private val disResults: MutableMap<String, String> = linkedMapOf()

    // optional file sending
    private var selectedFileBytes: ByteArray? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            toast("No file selected"); return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        readFileBytes(uri)?.let { data ->
            selectedFileBytes = data
            toast("File loaded: ${data.size} bytes")
            trySendFileIfReady()
        } ?: toast("Failed to read file")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI
        val btnScan = Button(this).apply { text = "Start Scan (20s)" }
        val etFilter = EditText(this).apply { hint = "type to filter results…" }
        btnReadInfo = Button(this).apply { text = "Read Device Info"; isEnabled = false }
        val btnPick = Button(this).apply { text = "Select File (optional)" }
        val list = ListView(this)

        adapterLv = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapterLv

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(btnScan)
            addView(etFilter)
            addView(btnReadInfo)
            addView(btnPick)
            addView(list)
        }
        setContentView(layout)

        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        btnScan.setOnClickListener { if (checkAndRequestPermissions()) startScan() }
        btnPick.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        btnReadInfo.setOnClickListener { readDeviceInfo() }
        etFilter.addTextChangedListener { applyFilter(it?.toString().orEmpty()) }

        list.setOnItemClickListener { _, _, pos, _ ->
            val entry = adapterLv.getItem(pos) ?: return@setOnItemClickListener
            val addr = entry.substringBefore("  ")
            val device = devices[addr] ?: return@setOnItemClickListener
            initiateConnect(device)
        }
    }

    // ------- Permissions -------
    private fun checkAndRequestPermissions(): Boolean {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!has(Manifest.permission.BLUETOOTH_SCAN)) perms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return if (perms.isEmpty()) true else {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQUEST); false
        }
    }
    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == PERM_REQUEST && r.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
        else if (rc == PERM_REQUEST) toast("Permission required")
    }

    // ------- Scanning -------
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, res: ScanResult) {
            val dev = res.device
            val name = dev.name ?: res.scanRecord?.deviceName ?: "Unknown"
            val entry = "${dev.address}  $name"
            if (!devices.containsKey(dev.address)) {
                devices[dev.address] = dev
                allEntries.add(entry)
                adapterLv.add(entry)
                adapterLv.notifyDataSetChanged()
            }
        }
        override fun onScanFailed(code: Int) { toast("Scan failed: $code") }
    }

    private fun startScan() {
        if (scanning) return
        val ad = bluetoothAdapter
        if (ad == null || !ad.isEnabled) { toast("Turn ON Bluetooth"); return }
        devices.clear(); allEntries.clear(); adapterLv.clear()
        btnReadInfo.isEnabled = false
        scanning = true
        toast("Scanning for ${SCAN_PERIOD_MS / 1000}s…")
        handler.postDelayed({ stopScan() }, SCAN_PERIOD_MS)

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(null, settings, scanCallback)
    }

    private fun stopScan() {
        if (!scanning) return
        scanner?.stopScan(scanCallback)
        scanning = false
        toast("Scan stopped")
    }

    private fun applyFilter(q: String) {
        adapterLv.clear()
        if (q.isBlank()) adapterLv.addAll(allEntries)
        else adapterLv.addAll(allEntries.filter { it.contains(q.trim(), ignoreCase = true) })
        adapterLv.notifyDataSetChanged()
    }

    // ------- Connect flow with visibility & retries -------
    private fun initiateConnect(device: BluetoothDevice) {
        // Classic-only warning
        if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            toast("Selected device is Classic Bluetooth only (not BLE). This app uses BLE.")
            return
        }
        // Stop scanning before connect (improves stability)
        stopScan()

        // Re-check BLUETOOTH_CONNECT permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), PERM_REQUEST)
            return
        }

        // Close any previous GATT
        currentGatt?.close()
        currentGatt = null
        writeCharacteristic = null
        btnReadInfo.isEnabled = false
        triedAltTransport = false

        toast("Connecting to ${device.address}…")
        connectWithTransport(device, BluetoothDevice.TRANSPORT_LE)
        startConnectTimeout(device)
    }

    private fun connectWithTransport(device: BluetoothDevice, transport: Int) {
        currentGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, transport)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private fun startConnectTimeout(device: BluetoothDevice) {
        cancelConnectTimeout()
        connectTimeoutRunnable = Runnable {
            toast("Connect timeout. Retrying…")
            currentGatt?.close()
            currentGatt = null
            if (!triedAltTransport && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                triedAltTransport = true
                // Some stacks behave better with TRANSPORT_AUTO
                connectWithTransport(device, BluetoothDevice.TRANSPORT_AUTO)
                startConnectTimeout(device)
            } else {
                toast("Failed to connect.")
            }
        }
        handler.postDelayed(connectTimeoutRunnable!!, 12_000L) // 12 sec timeout
    }
    private fun cancelConnectTimeout() {
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                val statusMsg = when (status) {
                    BluetoothGatt.GATT_SUCCESS -> "OK"
                    133 -> "GATT(133) general error"
                    else -> "status=$status"
                }
                val stateMsg = when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                    else -> "$newState"
                }
                toast("onConnectionStateChange: $stateMsg ($statusMsg)")
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                cancelConnectTimeout()
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cancelConnectTimeout()
                writeCharacteristic = null
                btnReadInfo.isEnabled = false
                currentGatt?.close()
                currentGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnUiThread { toast("Services discovered (status=$status)") }
            val svcSend = gatt.getService(SERVICE_UUID_SEND)
            writeCharacteristic = svcSend?.getCharacteristic(CHARACTERISTIC_UUID_SEND)
            runOnUiThread {
                if (writeCharacteristic != null) toast("Write characteristic ready")
                btnReadInfo.isEnabled = gatt.getService(DIS_SERVICE) != null
            }
            // (Optional) request a larger MTU for bigger chunks
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try { gatt.requestMtu(185) } catch (_: Exception) {}
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            runOnUiThread { toast("MTU changed: $mtu (status=$status)") }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val label = DIS_CHARS.firstOrNull { it.second == characteristic.uuid }?.first ?: characteristic.uuid.toString()
                val valueStr = bytesToString(characteristic.value)
                disResults[label] = valueStr
            }
            readNextDIS(gatt)
        }
    }

    // ------- Device Information read -------
    private fun readDeviceInfo() {
        val gatt = currentGatt ?: run { toast("Not connected"); return }
        val svc = gatt.getService(DIS_SERVICE)
        if (svc == null) { toast("Device Information Service not found"); return }

        disQueue.clear(); disResults.clear()
        for ((label, uuid) in DIS_CHARS) {
            svc.getCharacteristic(uuid)?.let { ch ->
                if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0)
                    disQueue.add(label to ch)
            }
        }
        if (disQueue.isEmpty()) { toast("No readable DIS characteristics"); return }
        toast("Reading device info…")
        readNextDIS(gatt)
    }

    private fun readNextDIS(gatt: BluetoothGatt) {
        if (disQueue.isEmpty()) {
            runOnUiThread {
                val msg = if (disResults.isEmpty()) "No data"
                else disResults.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                AlertDialog.Builder(this).setTitle("Device Information").setMessage(msg)
                    .setPositiveButton("OK", null).show()
            }
            return
        }
        val (_, ch) = disQueue.removeFirst()
        gatt.readCharacteristic(ch)
    }

    // ------- Optional: send file -------
    private fun trySendFileIfReady() {
        val gatt = currentGatt ?: return
        val ch = writeCharacteristic ?: return
        val data = selectedFileBytes ?: return
        toast("Sending ${data.size} bytes…")
        sendInChunks(gatt, ch, data)
    }

    private fun sendInChunks(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val end = min(offset + CHUNK_SIZE, data.size)
            ch.value = data.copyOfRange(offset, end)
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val ok = gatt.writeCharacteristic(ch)
            if (!ok) { runOnUiThread { toast("Write failed at $offset/${data.size}") }; return }
            offset = end
            try { Thread.sleep(10) } catch (_: InterruptedException) {}
        }
        runOnUiThread { toast("File sent (${data.size} bytes)") }
    }

    // ------- helpers -------
    private fun readFileBytes(uri: Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) { null }

    private fun bytesToString(bytes: ByteArray?): String {
        if (bytes == null) return ""
        return try {
            val s = String(bytes, Charsets.UTF_8).trim()
            if (s.isNotEmpty() && s.any { it.isLetterOrDigit() || it.isWhitespace() || it in "-_.,/#()" }) s
            else bytes.joinToString(" ") { "%02X".format(it) }
        } catch (_: Exception) {
            bytes.joinToString(" ") { "%02X".format(it) }
        }
    }

    private fun uuid16(short: Int): UUID =
        UUID.fromString("0000%04X-0000-1000-8000-00805F9B34FB".format(short))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopScan()
        cancelConnectTimeout()
        currentGatt?.close()
        super.onDestroy()
    }
}
