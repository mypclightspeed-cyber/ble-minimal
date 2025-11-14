package com.example.blescan

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.math.*

class MeterActivity : AppCompatActivity() {

    // --- scan/config ---
    private val SCAN_MS = 20_000L
    private val PERM_REQUEST = 1001

    // --- Amitis BMS (FF00) ---
    private val AMITIS_SERVICE = uuid("0000ff00")
    private val AMITIS_READ_CH = uuid("0000ff01")   // notify
    private val AMITIS_WRITE_CH = uuid("0000ff02")  // write
    private val CMD_BASIC_INFO = hex("DD A5 03 00 FF FD 77")
    private val CMD_CELL_VOLTAGES = hex("DD A5 04 00 FF FC 77")
    private val CMD_DEVICE_NAME = hex("DD A5 05 00 FF FB 77")

    // FET Control Commands
    private val CMD_ENTER_FACTORY_MODE = hex("DD 5A 00 02 56 78 01 F3 77")
    private val CMD_EXIT_FACTORY_MODE = hex("DD 5A 01 02 00 00 02 1F 77")
    private val CMD_ENABLE_CHARGE_FET = hex("DD 5A E1 02 00 00 02 1B 77")    // Clear bit 0
    private val CMD_DISABLE_CHARGE_FET = hex("DD 5A E1 02 00 01 02 1A 77")   // Set bit 0
    private val CMD_ENABLE_DISCHARGE_FET = hex("DD 5A E1 02 00 00 02 1B 77") // Clear bit 1
    private val CMD_DISABLE_DISCHARGE_FET = hex("DD 5A E1 02 00 02 02 19 77") // Set bit 1
    private val CMD_ENABLE_BOTH_FETS = hex("DD 5A E1 02 00 00 02 1B 77")     // Clear both bits
    private val CMD_DISABLE_BOTH_FETS = hex("DD 5A E1 02 00 03 02 18 77")    // Set both bits

    private fun cmdReadRegister(reg: Int): ByteArray {
        val r = reg and 0xFF
        val chk = (0x10000 - (r + 0)) and 0xFFFF
        return byteArrayOf(
            0xDD.toByte(), 0xA5.toByte(), r.toByte(), 0x00,
            ((chk shr 8) and 0xFF).toByte(), (chk and 0xFF).toByte(), 0x77.toByte()
        )
    }

    // --- UI ---
    private lateinit var bannerWarn: TextView
    private lateinit var btnScan: Button
    private lateinit var list: ListView
    private lateinit var gauge: ModernHalfGauge

    private lateinit var tvVolt: TextView
    private lateinit var tvCurr: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvName: TextView
    private lateinit var tvFetStatus: TextView
    private lateinit var thermometerView: ThermometerView

    // FET Control Buttons
    private lateinit var btnEnableCharge: Button
    private lateinit var btnDisableCharge: Button
    private lateinit var btnEnableDischarge: Button
    private lateinit var btnDisableDischarge: Button
    private lateinit var btnEnableBoth: Button
    private lateinit var btnDisableBoth: Button

    private lateinit var adapterLv: ArrayAdapter<String>
    private val rows = mutableListOf<String>()                     // "MAC  Name"
    private val devices = LinkedHashMap<String, BluetoothDevice>() // MAC -> device
    private val advertisedName = HashMap<String, String>()         // MAC -> name from scan

    // --- BLE ---
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var connected = false
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var chNotify: BluetoothGattCharacteristic? = null
    private var chWrite: BluetoothGattCharacteristic? = null
    private val rxBuffer = ArrayList<Byte>()

    // periodic polling while connected
    private val pollIntervalMs = 1000L
    private val pollTask = object : Runnable {
        override fun run() {
            chWrite?.let { w ->
                gatt?.let { g ->
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    w.value = CMD_BASIC_INFO
                    g.writeCharacteristic(w)
                }
            }
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val logo = ImageView(this).apply {
            try { setImageResource(R.drawable.logo) } catch (_: Exception) {}
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 150
            ).apply { setMargins(16, 16, 16, 8) }
        }

        bannerWarn = TextView(this).apply {
            setPadding(20, 14, 20, 14)
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DC2626"))
            visibility = View.GONE
        }

        btnScan = Button(this).apply { text = "Scan Amitis BMS" }
        list = ListView(this)

        // Gauge style 3 (modern half-circle) with A1: 180° sweep, start at 180°
        gauge = ModernHalfGauge(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 380
            ).apply { setMargins(16, 10, 16, 6) }
            setLabel("SOC")
            setPercent(0)
        }

        fun makeCard(title: String, colorHex: String): Pair<LinearLayout, TextView> {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
                setBackgroundColor(Color.parseColor(colorHex))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(16, 10, 16, 10)
                layoutParams = lp
                elevation = 6f
            }
            val titleTv = TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD) // titles bold
                setTextColor(Color.WHITE)
            }
            val valueTv = TextView(this).apply {
                text = "-"
                textSize = 26f // NOT bold
                setTextColor(Color.WHITE)
            }
            card.addView(titleTv); card.addView(valueTv)
            return card to valueTv
        }

        // Create thermometer card with custom layout
        fun makeThermometerCard(): Pair<LinearLayout, Pair<TextView, ThermometerView>> {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 18, 24, 18)
                setBackgroundColor(Color.parseColor("#F59E0B"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(16, 10, 16, 10)
                layoutParams = lp
                elevation = 6f
            }
            
            val leftLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    rightMargin = 20 // ایجاد فاصله بین متن و ترمومتر
                }
            }
            
            val titleTv = TextView(this).apply {
                text = "Temperature (°C)"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            val valueTv = TextView(this).apply {
                text = "-"
                textSize = 26f
                setTextColor(Color.WHITE)
            }
            
            leftLayout.addView(titleTv)
            leftLayout.addView(valueTv)
            
            val thermometer = ThermometerView(this).apply {
                layoutParams = LinearLayout.LayoutParams(100, 140) // افزایش ارتفاع و عرض
            }
            
            card.addView(leftLayout)
            card.addView(thermometer)
            
            return card to (valueTv to thermometer)
        }

        // Create FET Status card
        fun makeFetStatusCard(): Pair<LinearLayout, TextView> {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
                setBackgroundColor(Color.parseColor("#8B5CF6")) // Purple color for FET status
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(16, 10, 16, 10)
                layoutParams = lp
                elevation = 6f
            }
            val titleTv = TextView(this).apply {
                text = "FET Status"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            val valueTv = TextView(this).apply {
                text = "-"
                textSize = 20f
                setTextColor(Color.WHITE)
            }
            card.addView(titleTv)
            card.addView(valueTv)
            return card to valueTv
        }

        // Create FET Control Panel
        fun makeFetControlPanel(): LinearLayout {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
                setBackgroundColor(Color.parseColor("#4B5563"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(16, 10, 16, 10)
                layoutParams = lp
                elevation = 6f
            }

            val title = TextView(this).apply {
                text = "FET Control"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 16)
            }
            panel.addView(title)

            // Create button row layouts
            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                weightSum = 2f
            }

            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                weightSum = 2f
            }

            val row3 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                weightSum = 2f
            }

            // Create buttons
            btnEnableCharge = Button(this).apply {
                text = "🔋 Enable Charge"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 4, 4, 4)
                }
                setBackgroundColor(Color.parseColor("#10B981"))
                setTextColor(Color.WHITE)
            }

            btnDisableCharge = Button(this).apply {
                text = "⛔ Disable Charge"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 4, 4, 4)
                }
                setBackgroundColor(Color.parseColor("#EF4444"))
                setTextColor(Color.WHITE)
            }

            btnEnableDischarge = Button(this).apply {
                text = "⚡ Enable Discharge"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 4, 4, 4)
                }
                setBackgroundColor(Color.parseColor("#10B981"))
                setTextColor(Color.WHITE)
            }

            btnDisableDischarge = Button(this).apply {
                text = "⛔ Disable Discharge"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 4, 4, 4)
                }
                setBackgroundColor(Color.parseColor("#EF4444"))
                setTextColor(Color.WHITE)
            }

            btnEnableBoth = Button(this).apply {
                text = "✅ Enable Both"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 4, 4, 4)
                }
                setBackgroundColor(Color.parseColor("#10B981"))
                setTextColor(Color.WHITE)
            }

            btnDisableBoth = Button(this).apply {
                text = "❌ Disable Both"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 4, 4, 4)
                }
                setBackgroundColor(Color.parseColor("#EF4444"))
                setTextColor(Color.WHITE)
            }

            // Add buttons to rows
            row1.addView(btnEnableCharge)
            row1.addView(btnDisableCharge)
            
            row2.addView(btnEnableDischarge)
            row2.addView(btnDisableDischarge)
            
            row3.addView(btnEnableBoth)
            row3.addView(btnDisableBoth)

            panel.addView(row1)
            panel.addView(row2)
            panel.addView(row3)

            return panel
        }

        // ترتیب جدید: اول ولتاژ، بعد جریان، بعد دما، در آخر device
        val (cardName, nameValue) = makeCard("Device", "#3B82F6")
        val (cardVolt, voltValue) = makeCard("Voltage (V)", "#10B981")
        val (cardCurr, currValue) = makeCard("Current (A)", "#DC143C")
        val (cardTemp, tempPair) = makeThermometerCard()
        val (cardFet, fetValue) = makeFetStatusCard()
        val fetControlPanel = makeFetControlPanel()
        
        tvVolt = voltValue
        tvCurr = currValue
        tvTemp = tempPair.first
        thermometerView = tempPair.second
        tvName = nameValue
        tvFetStatus = fetValue

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(logo)
            addView(bannerWarn)
            addView(btnScan)
            addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(gauge)          // gauge ABOVE parameters
            addView(cardName)
            addView(cardVolt)
            addView(cardCurr)
            addView(cardTemp)
            addView(cardFet)
            addView(fetControlPanel)
        }
        setContentView(root)

        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        adapterLv = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapterLv

        btnScan.setOnClickListener {
            if (!ensurePrereqs()) return@setOnClickListener
            if (checkAndRequestPermissions()) startScan()
        }

        // Set up FET control button listeners
        btnEnableCharge.setOnClickListener { sendFetCommand(CMD_ENABLE_CHARGE_FET, "Enable Charge FET") }
        btnDisableCharge.setOnClickListener { sendFetCommand(CMD_DISABLE_CHARGE_FET, "Disable Charge FET") }
        btnEnableDischarge.setOnClickListener { sendFetCommand(CMD_ENABLE_DISCHARGE_FET, "Enable Discharge FET") }
        btnDisableDischarge.setOnClickListener { sendFetCommand(CMD_DISABLE_DISCHARGE_FET, "Disable Discharge FET") }
        btnEnableBoth.setOnClickListener { sendFetCommand(CMD_ENABLE_BOTH_FETS, "Enable Both FETs") }
        btnDisableBoth.setOnClickListener { sendFetCommand(CMD_DISABLE_BOTH_FETS, "Disable Both FETs") }

        list.setOnItemClickListener { _, _, pos, _ ->
            val entry = adapterLv.getItem(pos) ?: return@setOnItemClickListener
            val mac = entry.substringBefore("  ")
            val dev = devices[mac] ?: return@setOnItemClickListener
            // Use advertiser name directly
            tvName.text = advertisedName[mac] ?: "Unknown"
            connectTo(dev)
        }

        // Initially disable FET controls until connected
        updateFetControlsEnabled(false)
    }

    private fun sendFetCommand(command: ByteArray, description: String) {
        if (!connected) {
            toast("Not connected to BMS")
            return
        }

        // First enter factory mode
        sendCommandWithDelay(CMD_ENTER_FACTORY_MODE, 500) {
            // Then send the actual FET command
            sendCommandWithDelay(command, 500) {
                // Then exit factory mode
                sendCommandWithDelay(CMD_EXIT_FACTORY_MODE, 500) {
                    toast("$description command sent")
                    // Refresh basic info to update FET status
                    handler.postDelayed({
                        chWrite?.let { w ->
                            gatt?.let { g ->
                                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                w.value = CMD_BASIC_INFO
                                g.writeCharacteristic(w)
                            }
                        }
                    }, 1000)
                }
            }
        }
    }

    private fun sendCommandWithDelay(command: ByteArray, delayMs: Long, callback: (() -> Unit)? = null) {
        chWrite?.let { w ->
            gatt?.let { g ->
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = command
                g.writeCharacteristic(w)
            }
        }
        callback?.let {
            handler.postDelayed(it, delayMs)
        }
    }

    private fun updateFetControlsEnabled(enabled: Boolean) {
        btnEnableCharge.isEnabled = enabled
        btnDisableCharge.isEnabled = enabled
        btnEnableDischarge.isEnabled = enabled
        btnDisableDischarge.isEnabled = enabled
        btnEnableBoth.isEnabled = enabled
        btnDisableBoth.isEnabled = enabled
        
        val alpha = if (enabled) 1.0f else 0.5f
        btnEnableCharge.alpha = alpha
        btnDisableCharge.alpha = alpha
        btnEnableDischarge.alpha = alpha
        btnDisableDischarge.alpha = alpha
        btnEnableBoth.alpha = alpha
        btnDisableBoth.alpha = alpha
    }

    override fun onResume() { super.onResume(); updateWarningBanner() }

    // ---------- BT/Location prerequisites ----------
    private fun ensurePrereqs(): Boolean {
        var ok = true
        val btOn = bluetoothAdapter?.isEnabled == true
        val locOn = isLocationEnabled(this)
        if (!btOn) {
            ok = false
            AlertDialog.Builder(this)
                .setTitle("Bluetooth is OFF")
                .setMessage("Please enable Bluetooth to scan for BLE devices.")
                .setPositiveButton("Open Bluetooth Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }.setNegativeButton("Cancel", null).show()
        }
        if (!locOn) {
            ok = false
            AlertDialog.Builder(this)
                .setTitle("Location is OFF")
                .setMessage("Location must be ON for BLE scanning on many Android versions.")
                .setPositiveButton("Open Location Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }.setNegativeButton("Cancel", null).show()
        }
        updateWarningBanner()
        return ok
    }

    private fun updateWarningBanner() {
        val btOn = bluetoothAdapter?.isEnabled == true
        val locOn = isLocationEnabled(this)
        when {
            !btOn && !locOn -> { bannerWarn.text = "Bluetooth and Location are OFF"; bannerWarn.visibility = View.VISIBLE }
            !btOn -> { bannerWarn.text = "Bluetooth is OFF"; bannerWarn.visibility = View.VISIBLE }
            !locOn -> { bannerWarn.text = "Location is OFF"; bannerWarn.visibility = View.VISIBLE }
            else -> bannerWarn.visibility = View.GONE
        }
    }

    private fun isLocationEnabled(ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { false }

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
        else toast("Permission required")
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
        if (ad == null || !ad.isEnabled) { toast("Turn ON Bluetooth"); updateWarningBanner(); return }

        // reset on each new scan
        devices.clear(); rows.clear(); adapterLv.clear(); advertisedName.clear()
        gauge.setPercent(0)
        tvVolt.text = "-"
        tvCurr.text = "-"
        tvTemp.text = "-"
        tvName.text = "-"
        tvFetStatus.text = "-"
        thermometerView.setTemperature(0.0)
        updateFetControlsEnabled(false)

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

    // ---------- connect/services ----------
    private fun connectTo(device: BluetoothDevice) {
        stopScan()
        toast("Connecting to ${device.address}…")
        gatt?.close()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else
            device.connectGatt(this, false, gattCb)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread { 
                toast("State: ${stateName(newState)} (status=$status)") 
                connected = (newState == BluetoothProfile.STATE_CONNECTED)
                updateFetControlsEnabled(connected)
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(pollTask)
                chNotify = null; chWrite = null; rxBuffer.clear()
                g.close()
                runOnUiThread {
                    connected = false
                    updateFetControlsEnabled(false)
                    tvFetStatus.text = "Disconnected"
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(AMITIS_SERVICE)
            chNotify = svc?.getCharacteristic(AMITIS_READ_CH)
            chWrite  = svc?.getCharacteristic(AMITIS_WRITE_CH)
            runOnUiThread {
                if (svc == null || chNotify == null || chWrite == null) toast("Amitis FF00/FF01/FF02 not found")
                else toast("Amitis service ready")
            }
            chNotify?.let { notifyCh ->
                g.setCharacteristicNotification(notifyCh, true)
                val cccd = notifyCh.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
            // start continuous polling
            handler.removeCallbacks(pollTask)
            handler.postDelayed(pollTask, 300)
            // optional EEPROM name request (ignored for UI)
            chWrite?.let { w ->
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = cmdReadRegister(0xA1)
                g.writeCharacteristic(w)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == AMITIS_READ_CH) onAmitisBytes(ch.value ?: return)
        }
    }

    // ---------- Amitis frames ----------
    private fun onAmitisBytes(chunk: ByteArray) {
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

                if (reg == 0x03) handleBasicInfo(payload)
            }
        }
    }

    // payload: voltage(2) current(2s) ... soc (byte) at offset 19, FET status at offset 0x13
    private fun handleBasicInfo(p: ByteArray) {
        if (p.size < 24) return
        val vRaw = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val iRawU = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        var iRaw = iRawU
        if ((iRaw and 0x8000) != 0) iRaw = -((iRaw xor 0xFFFF) + 1)
        val voltage = vRaw / 100.0
        val current = iRaw / 100.0
        val soc = p[19].toInt() and 0xFF
        
        // Extract FET status from byte 0x13
        val fetStatusByte = p[0x13].toInt() and 0xFF
        val chargeFetEnabled = (fetStatusByte and 0x01) != 0
        val dischargeFetEnabled = (fetStatusByte and 0x02) != 0

        // Temperature extraction per JBD (0x03) with null fallback
        val dataStart = 4
        var tempValue = 0.0
        var tempText = "-"
        if (p.size > dataStart + 22) {
            val ntcCount = p[dataStart + 22].toInt() and 0xFF
            val firstTempIdx = dataStart + 23
            if (ntcCount > 0 && p.size >= firstTempIdx + 2) {
                val tRaw = ((p[firstTempIdx].toInt() and 0xFF) shl 8) or (p[firstTempIdx + 1].toInt() and 0xFF)
                tempValue = (tRaw - 2731) / 10.0
                if (!tempValue.isNaN() && tempValue > -100 && tempValue < 200) {
                    tempText = String.format("%.1f", tempValue)
                }
            }
        }

        runOnUiThread {
            gauge.setPercent(soc.coerceIn(0, 100))
            tvVolt.text = String.format("%.3f", voltage)
            tvCurr.text = String.format("%.3f", current)
            tvTemp.text = tempText
            thermometerView.setTemperature(tempValue)
            
            // Update FET status display
            updateFetStatusDisplay(chargeFetEnabled, dischargeFetEnabled)
        }
    }

    private fun updateFetStatusDisplay(chargeFet: Boolean, dischargeFet: Boolean) {
        val chargeStatus = if (chargeFet) "🔋 CHG ON" else "⛔ CHG OFF"
        val dischargeStatus = if (dischargeFet) "⚡ DSG ON" else "⛔ DSG OFF"
        
        tvFetStatus.text = "$chargeStatus | $dischargeStatus"
        
        // Color coding
        when {
            chargeFet && dischargeFet -> {
                tvFetStatus.setTextColor(Color.parseColor("#10B981")) // Green - normal
            }
            !chargeFet && !dischargeFet -> {
                tvFetStatus.setTextColor(Color.parseColor("#EF4444")) // Red - protection active
            }
            else -> {
                tvFetStatus.setTextColor(Color.parseColor("#F59E0B")) // Yellow - mixed state
            }
        }
    }

    // --- helpers / utils ---
    private fun hex(s: String): ByteArray =
        s.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun uuid(short: String) = UUID.fromString("$short-0000-1000-8000-00805f9b34fb")
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
        handler.removeCallbacks(pollTask)
        gatt?.close()
        super.onDestroy()
    }

    // ===== Thermometer View =====
    class ThermometerView(context: Context) : View(context) {
        private var temperature = 0.0
        
        private val casePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5E7EB") // Light gray for case
            style = Paint.Style.FILL
        }
        
        private val bulbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#25AFFF")
            style = Paint.Style.FILL
        }
        
        private val mercuryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#25AFFF")
            style = Paint.Style.FILL
        }
        
        private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        fun setTemperature(temp: Double) {
            temperature = temp
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.on