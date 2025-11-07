package com.example.blescan

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERM_REQUEST = 1001
    private lateinit var adapterLv: ArrayAdapter<String>
    private val devices = LinkedHashMap<String, BluetoothDevice>() // address -> device
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD_MS = 10000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Minimal UI
        val btn = Button(this).apply { text = "Start Scan" }
        val list = ListView(this)
        adapterLv = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        list.adapter = adapterLv

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(btn)
            addView(list)
        }
        setContentView(layout)

        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        btn.setOnClickListener {
            if (checkAndRequestPermissions()) startScan()
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val entry = adapterLv.getItem(pos) ?: return@setOnItemClickListener
            val addr = entry.substringBefore(" ")
            val device = devices[addr] ?: return@setOnItemClickListener
            connectToDevice(device)
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
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
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScan()
            } else {
                Toast.makeText(this, "Permission required to scan/connect Bluetooth", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = dev.name ?: result.scanRecord?.deviceName ?: "Unknown"
            val entry = "${dev.address} ${name}"
            if (!devices.containsKey(dev.address)) {
                devices[dev.address] = dev
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
        adapterLv.clear()
        scanning = true
        Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()

        handler.postDelayed({ stopScan() }, SCAN_PERIOD_MS)

        scanner?.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback)
    }

    private fun stopScan() {
        if (!scanning) return
        scanner?.stopScan(scanCallback)
        scanning = false
        Toast.makeText(this, "Scan stopped", Toast.LENGTH_SHORT).show()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Toast.makeText(this, "Connecting to ${device.address}", Toast.LENGTH_SHORT).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Toast.makeText(this@MainActivity, "Connected to ${gatt.device.address}", Toast.LENGTH_SHORT).show()
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Toast.makeText(this@MainActivity, "Disconnected from ${gatt.device.address}", Toast.LENGTH_SHORT).show()
                    gatt.close()
                }
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Services discovered: ${gatt.services.size}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }
}