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

    // ---- tweak these if needed ----
    private val SCAN_PERIOD_MS = 20_000L
    private val SERVICE_UUID_SEND = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB") // TODO replace (service for sending)
    private val CHARACTERISTIC_UUID_SEND = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB") // TODO replace (write char)
    private val CHUNK_SIZE = 20
    // -------------------------------

    // Device Information Service & characteristic UUIDs
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

    private val PERM_REQUEST = 1001
    private lateinit var adapterLv: ArrayAdapter<String>
    private val devices = LinkedHashMap<String, BluetoothDevice>() // address -> device
    private val allEntries = mutableListOf<String>()               // "MAC  Name" (for UI filtering)
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    // BLE connection state
    private var currentGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    // selected file
    private var selectedFileBytes: ByteArray? = null

    // UI references we need to enable/disable
    private lateinit var btnReadInfo: Button

    // queue & map for DIS reads
    private val disQueue: ArrayDeque<Pair<String, BluetoothGattCharacteristic>> = ArrayDeque()
    private val disResults: MutableMap<String, String> = linkedMapOf()

    // SAF file picker
    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        readFileBytes(uri)?.let { data ->
            selectedFileBytes = data
            Toast.makeText(this, "File loaded: ${data.size} bytes", Toast.LENGTH_SHORT).show()
            trySendFileIfReady()
        } ?: Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI
        val btnScan = Button(this).apply { text = "Start Scan (20s)" }
        val etFilter = EditText(this).apply { hint = "type to filter results…" }
        btnReadInfo = Button(this).apply {
            text = "Read Device Info"
            isEnabled = false
        }
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

        btnScan.setOnClickListener {
            if (checkAndRequestPermissions()) startScan()
        }
        btnPick.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        btnReadInfo.setOnClickListener { readDeviceInfo() }

        // client-side filter after scan
        etFilter.addTextChangedListener { text ->
            val q = text?.toString().orEmpty()
            applyFilter(q)
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val entry = adapterLv.getItem(pos) ?: return@setOnItemClickListener
            val addr = entry.substringBefore("  ")
            val device = devices[addr] ?: return@setOnItemClickListener
            connectToDevice(device)
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return if (perms.isEmpty()) true
        else {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQUEST)
            false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
            else Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Scanning ----
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = dev.name ?: result.scanRecord?.deviceName ?: "Unknown"
            val entry = "${dev.address}  $name"
            if (!devices.containsKey(dev.address)) {
                devices[dev.address] = dev
                allEntries.add(entry)
                adapterLv.add(entry)
                adapterLv.notifyDataSetChanged()
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Toast.makeText(this@MainActivity, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScan() {
        if (scanning) return
        if (scanner == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show()
            return
        }
        devices.clear()
        allEntries.clear()
        adapterLv.clear()
        btnReadInfo.isEnabled = false

        scanning = true
        Toast.makeText(this, "Scanning for ${SCAN_PERIOD_MS / 1000}s...", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ stopScan() }, SCAN_PERIOD_MS)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
    }

    private fun stopScan() {
        if (!scanning) return
        scanner?.stopScan(scanCallback)
        scanning = false
        Toast.makeText(this, "Scan stopped", Toast.LENGTH_SHORT).show()
    }

    private fun applyFilter(query: String) {
        adapterLv.clear()
        if (query.isBlank()) {
            adapterLv.addAll(allEntries)
        } else {
            val q = query.trim()
            adapterLv.addAll(allEntries.filter { it.contains(q, ignoreCase = true) })
        }
        adapterLv.notifyDataSetChanged()
    }

    // ---- Connect / GATT ----
    private fun connectToDevice(device: BluetoothDevice) {
        Toast.makeText(this, "Connecting to ${device.address}", Toast.LENGTH_SHORT).show()
        currentGatt?.close()
        currentGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Toast.makeText(this@MainActivity, "Connected: ${gatt.device.address}", Toast.LENGTH_SHORT).show()
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                    writeCharacteristic = null
                    btnReadInfo.isEnabled = false
                    currentGatt?.close()
                    currentGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // Prepare write char (optional feature)
            val svcSend = gatt.getService(SERVICE_UUID_SEND)
            writeCharacteristic = svcSend?.getCharacteristic(CHARACTERISTIC_UUID_SEND)

            // Enable Device Info button only if we have the service or we'll still try (some devices expose late)
            btnReadInfo.isEnabled = gatt.getService(DIS_SERVICE) != null

            runOnUiThread {
                if (writeCharacteristic != null) {
                    Toast.makeText(this@MainActivity, "Write characteristic ready", Toast.LENGTH_SHORT).show()
                } else {
                    // It's fine if not present — feature is optional
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // handle DIS read chain
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // find label by uuid
                val label = DIS_CHARS.firstOrNull { it.second == characteristic.uuid }?.first ?: characteristic.uuid.toString()
                val valueStr = bytesToString(characteristic.value)
                disResults[label] = valueStr
            }
            // continue with next characteristic in queue
            readNextDIS(gatt)
        }
    }

    // ---- Device Information read flow ----
    private fun readDeviceInfo() {
        val gatt = currentGatt ?: run {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = gatt.getService(DIS_SERVICE)
        if (svc == null) {
            Toast.makeText(this, "Device Information Service not found", Toast.LENGTH_SHORT).show()
            return
        }

        // Build queue of available DIS characteristics on this device
        disQueue.clear()
        disResults.clear()
        for ((label, uuid) in DIS_CHARS) {
            svc.getCharacteristic(uuid)?.let { ch ->
                disQueue.add(label to ch)
            }
        }
        if (disQueue.isEmpty()) {
            Toast.makeText(this, "No DIS characteristics available", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Reading device info…", Toast.LENGTH_SHORT).show()
        readNextDIS(gatt)
    }

    private fun readNextDIS(gatt: BluetoothGatt) {
        if (disQueue.isEmpty()) {
            // Done — show results
            runOnUiThread {
                val msg = if (disResults.isEmpty()) "No data" else disResults.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                AlertDialog.Builder(this)
                    .setTitle("Device Information")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
            return
        }
        val (_, ch) = disQueue.removeFirst()
        // Some stacks require READ type; ensure properties allow read
        val canRead = (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
        if (!canRead) {
            readNextDIS(gatt) // skip
            return
        }
        gatt.readCharacteristic(ch)
    }

    // ---- File send (optional) ----
    private fun trySendFileIfReady() {
        val gatt = currentGatt ?: return
        val ch = writeCharacteristic ?: return
        val data = selectedFileBytes ?: return

        Toast.makeText(this, "Sending ${data.size} bytes...", Toast.LENGTH_SHORT).show()
        sendInChunks(gatt, ch, data)
    }

    private fun sendInChunks(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val end = min(offset + CHUNK_SIZE, data.size)
            val chunk = data.copyOfRange(offset, end)
            ch.value = chunk
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val ok = gatt.writeCharacteristic(ch)
            if (!ok) {
                runOnUiThread { Toast.makeText(this, "Write failed at $offset/${data.size}", Toast.LENGTH_SHORT).show() }
                return
            }
            offset = end
            try { Thread.sleep(10) } catch (_: InterruptedException) {}
        }
        runOnUiThread { Toast.makeText(this, "File sent (${data.size} bytes)", Toast.LENGTH_SHORT).show() }
    }

    // ---- helpers ----
    private fun readFileBytes(uri: Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) { null }

    private fun bytesToString(bytes: ByteArray?): String {
        if (bytes == null) return ""
        // Try UTF-8 printable; fallback to hex
        return try {
            val s = String(bytes, Charsets.UTF_8).trim()
            if (s.isNotEmpty() && s.any { it.isLetterOrDigit() || it.isWhitespace() || it in "-_.,/#()" }) s
            else bytes.joinToString(separator = " ") { "%02X".format(it) }
        } catch (_: Exception) {
            bytes.joinToString(separator = " ") { "%02X".format(it) }
        }
    }

    private fun uuid16(short: Int): UUID =
        UUID.fromString("0000%04X-0000-1000-8000-00805F9B34FB".format(short))

    override fun onDestroy() {
        stopScan()
        currentGatt?.close()
        super.onDestroy()
    }
}
