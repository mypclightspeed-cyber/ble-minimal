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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import java.util.UUID
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    // ---- tweak these if needed ----
    private val SCAN_PERIOD_MS = 20_000L
    private val SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB") // TODO replace (service)
    private val CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB") // TODO replace (write char)
    private val CHUNK_SIZE = 20
    // -------------------------------

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
        val etFilter = EditText(this).apply { hint = "type to filter results…" }
        val btnScan = Button(this).apply { text = "Start Scan (20s)" }
        val btnPick = Button(this).apply { text = "Select File (optional)" }
        val list = ListView(this)

        adapterLv = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapterLv

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(btnScan)
            addView(etFilter)
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

        // live filter on the already-scanned list (client-side)
        etFilter.addTextChangedListener { text ->
            val q = text?.toString().orEmpty()
            applyFilter(q)
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val entry = adapterLv.getItem(pos) ?: return@setOnItemClickListener
            val addr = entry.substringBefore("  ") // two spaces separator
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

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = dev.name ?: result.scanRecord?.deviceName ?: "Unknown"
            val entry = "${dev.address}  $name"
            if (!devices.containsKey(dev.address)) {
                devices[dev.address] = dev
                allEntries.add(entry)            // keep master list
                adapterLv.add(entry)             // show immediately
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
                    currentGatt?.close()
                    currentGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc = gatt.getService(SERVICE_UUID)
            val ch = svc?.getCharacteristic(CHARACTERISTIC_UUID)
            writeCharacteristic = ch
            runOnUiThread {
                if (ch != null) {
                    Toast.makeText(this@MainActivity, "Write characteristic ready", Toast.LENGTH_SHORT).show()
                    trySendFileIfReady()
                } else {
                    Toast.makeText(this@MainActivity, "Characteristic not found. Check UUIDs.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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

    private fun readFileBytes(uri: Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) { null }

    override fun onDestroy() {
        stopScan()
        currentGatt?.close()
        super.onDestroy()
    }
}
