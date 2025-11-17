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
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*
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
    
    // JBD Protocol Constants
    private val JBD_START: Byte = 0xDD.toByte()
    private val JBD_END: Byte = 0x77.toByte()
    private val JBD_READ: Byte = 0xA5.toByte()
    private val JBD_WRITE: Byte = 0x5A.toByte()

    // CORRECTED BLE Commands based on JBD BMS protocol
    private val CMD_FET_FORCE_ON = hex("DD 5A E1 02 00 00 FF 1D 77")
    
    // CORRECTED Factory mode commands for BLE (using proper JBD protocol)
    private val CMD_ENTER_FACTORY_MODE = createJbdCommand(0x00, hex("56 78"), false)
    private val CMD_EXIT_FACTORY_MODE = createJbdCommand(0x01, hex("28 28"), false)
    
    // CORRECTED Voltage settings using proper JBD protocol
    // Cell voltage protection settings (Registers 0x24-0x27)
    private val CMD_SET_CELL_OVP_4_2V = createJbdCommand(0x24, intToBytes(4200), false)  // 4.20V = 4200mV
    private val CMD_SET_CELL_OVP_RELEASE_4_1V = createJbdCommand(0x25, intToBytes(4100), false)  // 4.10V
    private val CMD_SET_CELL_UVP_2_8V = createJbdCommand(0x26, intToBytes(2800), false)  // 2.80V
    private val CMD_SET_CELL_UVP_RELEASE_3_0V = createJbdCommand(0x27, intToBytes(3000), false)  // 3.00V
    
    // Pack voltage protection settings (Registers 0x20-0x23)
    private val CMD_SET_PACK_OVP_16_8V = createJbdCommand(0x20, intToBytes(16800), false)  // 16.8V = 16800mV
    private val CMD_SET_PACK_OVP_RELEASE_16_5V = createJbdCommand(0x21, intToBytes(16500), false)  // 16.5V
    private val CMD_SET_PACK_UVP_12_0V = createJbdCommand(0x22, intToBytes(12000), false)  // 12.0V
    private val CMD_SET_PACK_UVP_RELEASE_12_5V = createJbdCommand(0x23, intToBytes(12500), false)  // 12.5V

    // Temperature settings (Registers 0x18-0x1F) - in deciKelvin
    private val CMD_SET_CHG_OT_45C = createJbdCommand(0x18, intToBytes(celsiusToDeciKelvin(45)), false)
    private val CMD_SET_CHG_OT_RELEASE_40C = createJbdCommand(0x19, intToBytes(celsiusToDeciKelvin(40)), false)
    private val CMD_SET_CHG_UT_0C = createJbdCommand(0x1A, intToBytes(celsiusToDeciKelvin(0)), false)
    private val CMD_SET_CHG_UT_RELEASE_5C = createJbdCommand(0x1B, intToBytes(celsiusToDeciKelvin(5)), false)

    // Add delay constants for proper BLE timing (INCREASED TIMES)
    private val BLE_COMMAND_DELAY = 1000L
    private val FACTORY_MODE_DELAY = 2000L
    private val SETTINGS_WRITE_DELAY = 1500L
    private val RESPONSE_TIMEOUT = 5000L

    // JBD Protocol Helper Functions
    private fun calculateJbdChecksum(data: ByteArray): Int {
        return (0x10000 - data.sum()) and 0xFFFF
    }

    private fun createJbdCommand(register: Int, data: ByteArray = byteArrayOf(), read: Boolean = true): ByteArray {
        val command = if (read) JBD_READ else JBD_WRITE
        val payload = byteArrayOf(register.toByte(), data.size.toByte()) + data
        val checksum = calculateJbdChecksum(payload)
        
        return byteArrayOf(JBD_START, command) + payload + byteArrayOf(
            ((checksum shr 8) and 0xFF).toByte(),
            (checksum and 0xFF).toByte(),
            JBD_END
        )
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    private fun celsiusToDeciKelvin(celsius: Int): Int {
        return ((celsius + 273.15) * 10).toInt()
    }

    private fun cmdReadRegister(reg: Int): ByteArray {
        return createJbdCommand(reg, byteArrayOf(), true)
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
    private lateinit var fetSwitch: Switch

    // Add debug TextView
    private lateinit var tvDebug: TextView
    private lateinit var debugScrollView: ScrollView

    private lateinit var adapterLv: ArrayAdapter<String>
    private val rows = mutableListOf<String>()
    private val devices = LinkedHashMap<String, BluetoothDevice>()
    private val advertisedName = HashMap<String, String>()

    // --- BLE ---
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var chNotify: BluetoothGattCharacteristic? = null
    private var chWrite: BluetoothGattCharacteristic? = null
    private val rxBuffer = ArrayList<Byte>()

    // Track status
    private var lastFetStatus: String = ""
    private var lastChargeFET: Boolean = false
    private var lastDischargeFET: Boolean = false
    private var isTemporaryLowVoltageMode = false
    private var isInFactoryMode = false

    // Response queue for settings
    private val responseQueue = mutableMapOf<Int, ByteArray>()
    private val pendingResponses = mutableMapOf<Int, Long>() // register -> timestamp

    // Debug logging
    private val debugLog = StringBuilder()
    private val MAX_DEBUG_LINES = 50

    // periodic polling while connected
    private val pollIntervalMs = 1000L
    private val pollTask = object : Runnable {
        override fun run() {
            chWrite?.let { w ->
                gatt?.let { g ->
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    w.value = CMD_BASIC_INFO
                    g.writeCharacteristic(w)
                    
                    runOnUiThread {
                        if (tvFetStatus.text == "-" || tvFetStatus.text == "Waiting for data...") {
                            tvFetStatus.text = "Updating..."
                        }
                    }
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
            isClickable = true
            setOnClickListener {
                openRelevantSettings()
            }
        }

        btnScan = Button(this).apply { text = "Scan Amitis BMS" }
        
        // Increase the height of the device list for better visibility
        list = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400 // Increased height
            )
        }

        gauge = ModernHalfGauge(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 380
            ).apply { setMargins(16, 10, 16, 6) }
            setLabel("SOC")
            setPercent(0)
        }

        // Create debug section (keeping it but making it smaller)
        debugScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 150 // Smaller debug area
            ).apply { setMargins(16, 10, 16, 6) }
        }

        tvDebug = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setTextColor(Color.parseColor("#00FF00"))
            textSize = 8f // Smaller font for debug
            typeface = Typeface.MONOSPACE
            setPadding(16, 8, 16, 8)
        }

        debugScrollView.addView(tvDebug)

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
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            val valueTv = TextView(this).apply {
                text = "-"
                textSize = 26f
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
                    rightMargin = 20
                }
            }
            
            val titleTv = TextView(this).apply {
                text = "Temperature (°C)"
                textSize = 16f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
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
                layoutParams = LinearLayout.LayoutParams(100, 140)
            }
            
            card.addView(leftLayout)
            card.addView(thermometer)
            
            return card to (valueTv to thermometer)
        }

        // Create FET status card with switch
        fun makeFetStatusCard(): Pair<LinearLayout, Pair<TextView, Switch>> {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 18, 24, 18)
                setBackgroundColor(Color.parseColor("#8B5CF6"))
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
                    rightMargin = 20
                }
            }
            
            val titleTv = TextView(this).apply {
                text = "FET Status"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            val valueTv = TextView(this).apply {
                text = "Waiting for data..."
                textSize = 16f
                setTextColor(Color.WHITE)
            }
            
            leftLayout.addView(titleTv)
            leftLayout.addView(valueTv)
            
            val switchLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER
            }
            
            val switchLabel = TextView(this).apply {
                text = "Force ON"
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }
            
            val switch = Switch(this).apply {
                text = ""
                isChecked = false
                setPadding(20, 10, 20, 10)
            }
            
            switchLayout.addView(switchLabel)
            switchLayout.addView(switch)
            
            card.addView(leftLayout)
            card.addView(switchLayout)
            
            return card to (valueTv to switch)
        }

        val (cardName, nameValue) = makeCard("Device", "#3B82F6")
        val (cardVolt, voltValue) = makeCard("Voltage (V)", "#10B981")
        val (cardCurr, currValue) = makeCard("Current (A)", "#DC143C")
        val (cardTemp, tempPair) = makeThermometerCard()
        val (cardFet, fetPair) = makeFetStatusCard()
        
        tvVolt = voltValue
        tvCurr = currValue
        tvTemp = tempPair.first
        thermometerView = tempPair.second
        tvName = nameValue
        tvFetStatus = fetPair.first
        fetSwitch = fetPair.second

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(logo)
            addView(bannerWarn)
            addView(btnScan)
            addView(list) // Increased height list view
            addView(gauge)
            addView(debugScrollView) // Keep debug view but smaller
            addView(cardName)
            addView(cardVolt)
            addView(cardCurr)
            addView(cardTemp)
            addView(cardFet)
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

        list.setOnItemClickListener { _, _, pos, _ ->
            val entry = adapterLv.getItem(pos) ?: return@setOnItemClickListener
            val mac = entry.substringBefore("  ")
            val dev = devices[mac] ?: return@setOnItemClickListener
            tvName.text = advertisedName[mac] ?: "Unknown"
            connectTo(dev)
        }

        // Set up FET switch listener
        fetSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                AlertDialog.Builder(this)
                    .setTitle("Force FETs ON with Voltage Settings")
                    .setMessage("This will:\n1. Enter factory mode\n2. Set safe voltage limits\n3. Force both FETs ON\n4. Exit factory mode\n\nWARNING: Make sure you understand the risks!")
                    .setPositiveButton("Proceed") { _, _ ->
                        setSafeVoltagesAndForceFets()
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        fetSwitch.isChecked = false
                    }
                    .show()
            } else {
                toast("FET control returned to BMS automatic operation")
            }
        }
    }

    // Debug logging functions
    private fun addDebugLog(message: String) {
        runOnUiThread {
            val timestamp = System.currentTimeMillis()
            val timeStr = String.format("%tT", timestamp)
            debugLog.append("[$timeStr] $message\n")
            
            // Keep only last MAX_DEBUG_LINES
            val lines = debugLog.toString().split("\n")
            if (lines.size > MAX_DEBUG_LINES) {
                debugLog.clear()
                debugLog.append(lines.takeLast(MAX_DEBUG_LINES).joinToString("\n"))
            }
            
            tvDebug.text = debugLog.toString()
            debugScrollView.post { debugScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        Log.d("BMS_DEBUG", message)
    }

    private fun logSentPacket(packet: ByteArray, description: String) {
        addDebugLog("📤 SENT: $description")
        addDebugLog("     HEX: ${packet.joinToString(" ") { "%02X".format(it) }}")
        addDebugLog("     Length: ${packet.size} bytes")
    }

    private fun logReceivedPacket(packet: ByteArray, description: String) {
        addDebugLog("📥 RECV: $description")
        addDebugLog("     HEX: ${packet.joinToString(" ") { "%02X".format(it) }}")
        addDebugLog("     Length: ${packet.size} bytes")
    }

    // Function to set safe voltage settings and force FETs ON with proper BLE timing
    private fun setSafeVoltagesAndForceFets() {
        chWrite?.let { w ->
            gatt?.let { g ->
                isTemporaryLowVoltageMode = true
                
                // Step 1: Enter factory mode
                addDebugLog("🚀 Starting EEPROM write sequence...")
                toast("Entering factory mode...")
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = CMD_ENTER_FACTORY_MODE
                logSentPacket(CMD_ENTER_FACTORY_MODE, "Enter Factory Mode")
                g.writeCharacteristic(w)
                isInFactoryMode = true
                
                handler.postDelayed({
                    // Step 2: Set safe cell voltage limits
                    toast("Setting safe cell voltages...")
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    
                    // Set Cell UVP to 2.8V
                    w.value = CMD_SET_CELL_UVP_2_8V
                    logSentPacket(CMD_SET_CELL_UVP_2_8V, "Set Cell UVP 2.8V")
                    g.writeCharacteristic(w)
                    
                    handler.postDelayed({
                        // Set Cell UVP Release to 3.0V
                        w.value = CMD_SET_CELL_UVP_RELEASE_3_0V
                        logSentPacket(CMD_SET_CELL_UVP_RELEASE_3_0V, "Set Cell UVP Release 3.0V")
                        g.writeCharacteristic(w)
                        
                        handler.postDelayed({
                            // Step 3: Set safe pack voltage limits
                            toast("Setting safe pack voltages...")
                            
                            // Set Pack UVP to 12.0V
                            w.value = CMD_SET_PACK_UVP_12_0V
                            logSentPacket(CMD_SET_PACK_UVP_12_0V, "Set Pack UVP 12.0V")
                            g.writeCharacteristic(w)
                            
                            handler.postDelayed({
                                // Set Pack UVP Release to 12.5V
                                w.value = CMD_SET_PACK_UVP_RELEASE_12_5V
                                logSentPacket(CMD_SET_PACK_UVP_RELEASE_12_5V, "Set Pack UVP Release 12.5V")
                                g.writeCharacteristic(w)
                                
                                handler.postDelayed({
                                    // Step 4: Exit factory mode
                                    toast("Exiting factory mode...")
                                    w.value = CMD_EXIT_FACTORY_MODE
                                    logSentPacket(CMD_EXIT_FACTORY_MODE, "Exit Factory Mode")
                                    g.writeCharacteristic(w)
                                    isInFactoryMode = false
                                    
                                    handler.postDelayed({
                                        // Step 5: Force FETs ON
                                        toast("Forcing both FETs ON...")
                                        w.value = CMD_FET_FORCE_ON
                                        logSentPacket(CMD_FET_FORCE_ON, "Force FETs ON")
                                        g.writeCharacteristic(w)
                                        
                                        // Step 6: Update status
                                        handler.postDelayed({
                                            chWrite?.let { w2 ->
                                                gatt?.let { g2 ->
                                                    w2.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                                    w2.value = CMD_BASIC_INFO
                                                    logSentPacket(CMD_BASIC_INFO, "Read Basic Info")
                                                    g2.writeCharacteristic(w2)
                                                }
                                            }
                                            addDebugLog("✅ EEPROM write sequence completed!")
                                        }, BLE_COMMAND_DELAY)
                                        
                                    }, FACTORY_MODE_DELAY)
                                }, SETTINGS_WRITE_DELAY)
                            }, SETTINGS_WRITE_DELAY)
                        }, SETTINGS_WRITE_DELAY)
                    }, SETTINGS_WRITE_DELAY)
                }, FACTORY_MODE_DELAY)
            } ?: run {
                toast("Not connected to BMS")
                fetSwitch.isChecked = false
                isTemporaryLowVoltageMode = false
                isInFactoryMode = false
            }
        } ?: run {
            toast("Not connected to BMS")
            fetSwitch.isChecked = false
            isTemporaryLowVoltageMode = false
            isInFactoryMode = false
        }
    }

    // ... (rest of the code remains exactly the same - only UI layout changes above)

    // The rest of the code (handleJbdResponse, parseEepromData, onActivityResult, onResume, 
    // ensurePrereqs, openRelevantSettings, updateWarningBanner, isLocationEnabled, 
    // checkAndRequestPermissions, scanCb, startScan, stopScan, connectTo, 
    // disconnectFromCurrentDevice, gattCb, onAmitisBytes, handleBasicInfo, 
    // helper functions, ThermometerView, ModernHalfGauge) remains exactly the same
    // as in the previous version...

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        handler.postDelayed({
            updateWarningBanner()
            
            if (ensurePrereqs()) {
                if (checkAndRequestPermissions()) {
                    startScan()
                }
            }
        }, 1000)
    }

    override fun onResume() { 
        super.onResume(); 
        updateWarningBanner()
        
        handler.postDelayed({
            if (scanning) {
                if (!ensurePrereqs()) {
                    stopScan()
                    toast("Scan stopped due to missing prerequisites")
                }
            }
        }, 300)
    }

    // ---------- BT/Location prerequisites ----------
    private fun ensurePrereqs(): Boolean {
        val btOn = bluetoothAdapter?.isEnabled == true
        val locOn = isLocationEnabled(this)
        
        if (!btOn) {
            AlertDialog.Builder(this)
                .setTitle("Bluetooth is OFF")
                .setMessage("Please enable Bluetooth to scan for BLE devices.")
                .setPositiveButton("Open Bluetooth Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }.setNegativeButton("Cancel", null).show()
            return false
        }
        
        if (!locOn) {
            AlertDialog.Builder(this)
                .setTitle("Location is OFF")
                .setMessage("Location must be ON for BLE scanning on many Android versions.")
                .setPositiveButton("Open Location Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }.setNegativeButton("Cancel", null).show()
            return false
        }
        
        updateWarningBanner()
        return true
    }

    private fun openRelevantSettings() {
        val btOn = bluetoothAdapter?.isEnabled == true
        val locOn = isLocationEnabled(this)
        
        when {
            !btOn && !locOn -> {
                AlertDialog.Builder(this)
                    .setTitle("Enable Bluetooth and Location")
                    .setMessage("Both Bluetooth and Location are required for BLE scanning. Which one do you want to enable first?")
                    .setPositiveButton("Bluetooth") { _, _ ->
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                    .setNegativeButton("Location") { _, _ ->
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                    .setNeutralButton("Cancel", null)
                    .show()
            }
            !btOn -> {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            !locOn -> {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
    }

    private fun updateWarningBanner() {
        val btOn = bluetoothAdapter?.isEnabled == true
        val locOn = isLocationEnabled(this)
        when {
            !btOn && !locOn -> { 
                bannerWarn.text = "Bluetooth and Location are OFF - Tap to enable"
                bannerWarn.visibility = View.VISIBLE 
            }
            !btOn -> { 
                bannerWarn.text = "Bluetooth is OFF - Tap to enable"
                bannerWarn.visibility = View.VISIBLE 
            }
            !locOn -> { 
                bannerWarn.text = "Location is OFF - Tap to enable"
                bannerWarn.visibility = View.VISIBLE 
            }
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
        override fun onScanFailed(code: Int) { 
            addDebugLog("❌ Scan failed: $code")
            toast("Scan failed: $code") 
        }
    }

    private fun startScan() {
        if (scanning) return
        
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) { 
            toast("Bluetooth is not available or turned off")
            updateWarningBanner()
            return 
        }

        scanner = bluetoothAdapter!!.bluetoothLeScanner
        if (scanner == null) {
            toast("Bluetooth LE Scanner is not available")
            return
        }

        // reset on each new scan
        devices.clear(); rows.clear(); adapterLv.clear(); advertisedName.clear()
        gauge.setPercent(0)
        tvVolt.text = "-"
        tvCurr.text = "-"
        tvTemp.text = "-"
        tvName.text = ""
        tvFetStatus.text = "Waiting for data..."
        thermometerView.setTemperature(0.0)
        fetSwitch.isChecked = false
        lastFetStatus = ""
        lastChargeFET = false
        lastDischargeFET = false
        isTemporaryLowVoltageMode = false
        isInFactoryMode = false
        responseQueue.clear()
        pendingResponses.clear()
        debugLog.clear()
        tvDebug.text = ""

        scanning = true
        addDebugLog("🔍 Starting BLE scan...")
        toast("Scanning for ${SCAN_MS/1000}s...")
        
        handler.postDelayed({
            stopScan()
            addDebugLog("✅ Scan completed: ${rows.size} device(s) found")
            toast("Scan done: ${rows.size} device(s) found")
        }, SCAN_MS)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
            
        val filters = mutableListOf<ScanFilter>()
        
        try {
            scanner?.startScan(filters, settings, scanCb)
        } catch (e: SecurityException) {
            addDebugLog("❌ Permission denied for Bluetooth scanning")
            toast("Permission denied for Bluetooth scanning")
            scanning = false
        } catch (e: Exception) {
            addDebugLog("❌ Scan failed: ${e.message}")
            toast("Scan failed: ${e.message}")
            scanning = false
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanner?.stopScan(scanCb)
        scanning = false
    }

    // ---------- connect/services ----------
    private fun connectTo(device: BluetoothDevice) {
        stopScan()
        
        disconnectFromCurrentDevice()
        
        addDebugLog("🔗 Connecting to ${device.address}...")
        toast("Connecting to ${device.address}...")
        
        // Reset UI values when connecting to new device
        gauge.setPercent(0)
        tvVolt.text = "-"
        tvCurr.text = "-"
        tvTemp.text = "-"
        tvFetStatus.text = "Connecting..."
        thermometerView.setTemperature(0.0)
        fetSwitch.isChecked = false
        lastFetStatus = ""
        lastChargeFET = false
        lastDischargeFET = false
        isTemporaryLowVoltageMode = false
        isInFactoryMode = false
        responseQueue.clear()
        pendingResponses.clear()
        
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else
            device.connectGatt(this, false, gattCb)
    }

    private fun disconnectFromCurrentDevice() {
        // Ensure we exit factory mode before disconnecting
        if (isInFactoryMode) {
            chWrite?.let { w ->
                gatt?.let { g ->
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    w.value = CMD_EXIT_FACTORY_MODE
                    addDebugLog("🔚 Exiting factory mode before disconnect")
                    g.writeCharacteristic(w)
                }
            }
            isInFactoryMode = false
        }
        
        handler.removeCallbacks(pollTask)
        chNotify = null
        chWrite = null
        rxBuffer.clear()
        responseQueue.clear()
        pendingResponses.clear()
        
        gatt?.let { g ->
            try {
                g.disconnect()
                g.close()
            } catch (e: Exception) {
                // ignore errors during disconnect
            }
            gatt = null
        }
        addDebugLog("🔌 Disconnected from device")
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread { 
                addDebugLog("🔄 Connection state: ${stateName(newState)} (status=$status)")
                toast("State: ${stateName(newState)} (status=$status)") 
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    tvFetStatus.text = "Discovering services..."
                }
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(pollTask)
                chNotify = null
                chWrite = null
                rxBuffer.clear()
                responseQueue.clear()
                pendingResponses.clear()
                g.close()
                gatt = null
                
                runOnUiThread {
                    fetSwitch.isChecked = false
                    tvFetStatus.text = "Disconnected"
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(AMITIS_SERVICE)
            chNotify = svc?.getCharacteristic(AMITIS_READ_CH)
            chWrite  = svc?.getCharacteristic(AMITIS_WRITE_CH)
            runOnUiThread {
                if (svc == null || chNotify == null || chWrite == null) {
                    addDebugLog("❌ Amitis FF00/FF01/FF02 not found")
                    toast("Amitis FF00/FF01/FF02 not found")
                    disconnectFromCurrentDevice()
                    fetSwitch.isChecked = false
                    tvFetStatus.text = "Service not found"
                } else {
                    addDebugLog("✅ Amitis service discovered - starting data polling")
                    toast("Amitis service ready - Starting data polling")
                    tvFetStatus.text = "Starting updates..."
                }
            }
            
            if (svc == null || chNotify == null || chWrite == null) return
            
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
            handler.postDelayed(pollTask, 500)
            chWrite?.let { w ->
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = cmdReadRegister(0xA1)
                addDebugLog("📤 Initial device info request")
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

                // Handle JBD protocol responses for settings
                if (reg in 0x18..0x2F || reg == 0xA1) {
                    handleJbdResponse(frame)
                }

                if (reg == 0x03) handleBasicInfo(payload)
            }
        }
    }

    // payload: voltage(2) current(2s) ... soc (byte) at offset 19, FET status at correct offset
    private fun handleBasicInfo(p: ByteArray) {
        if (p.size < 24) return
        val vRaw = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val iRawU = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        var iRaw = iRawU
        if ((iRaw and 0x8000) != 0) iRaw = -((iRaw xor 0xFFFF) + 1)
        val voltage = vRaw / 100.0
        val current = iRaw / 100.0
        val soc = p[19].toInt() and 0xFF

        // Correct FET status parsing according to JBD protocol
        // FET status is typically at byte 20 (0x14) in basic info response
        val fetStatusByte = if (p.size > 20) p[20].toInt() and 0xFF else 0
        
        // Extract FET status bits according to JBD protocol specification
        // Bit 0: Charge MOSFET status (1=ON, 0=OFF)
        // Bit 1: Discharge MOSFET status (1=ON, 0=OFF) 
        // Bit 2: Charge current limit status
        // Bit 3: Discharge current limit status
        val chargeFET = (fetStatusByte and 0x01) != 0
        val dischargeFET = (fetStatusByte and 0x02) != 0
        val chargeCurrentLimit = (fetStatusByte and 0x04) != 0
        val dischargeCurrentLimit = (fetStatusByte and 0x08) != 0

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
            
            // Update FET status display with more detailed information
            val modeStatus = when {
                isInFactoryMode -> " (Factory Mode)"
                isTemporaryLowVoltageMode -> " (Low Voltage Mode)"
                else -> ""
            }
            val fetStatusText = buildString {
                append("Charge: ")
                append(if (chargeFET) "ON" else "OFF")
                append(" | Discharge: ")
                append(if (dischargeFET) "ON" else "OFF")
                append(modeStatus)
                
                if (chargeCurrentLimit || dischargeCurrentLimit) {
                    append("\nLimits: ")
                    if (chargeCurrentLimit) append("Chg ")
                    if (dischargeCurrentLimit) append("Dischg")
                }
            }
            
            if (fetStatusText != lastFetStatus || chargeFET != lastChargeFET || dischargeFET != lastDischargeFET) {
                tvFetStatus.text = fetStatusText
                lastFetStatus = fetStatusText
                lastChargeFET = chargeFET
                lastDischargeFET = dischargeFET
                
                if (chargeFET && dischargeFET) {
                    // Switch remains checked if user manually set it
                } else {
                    if (fetSwitch.isChecked) {
                        // Don't automatically uncheck - let user decide
                    }
                }
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
        if (isInFactoryMode) {
            chWrite?.let { w ->
                gatt?.let { g ->
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    w.value = CMD_EXIT_FACTORY_MODE
                    addDebugLog("🔚 Exiting factory mode on destroy")
                    g.writeCharacteristic(w)
                }
            }
        }
        
        stopScan()
        handler.removeCallbacks(pollTask)
        disconnectFromCurrentDevice()
        super.onDestroy()
    }

    // ===== Thermometer View =====
    class ThermometerView(context: Context) : View(context) {
        private var temperature = 0.0
        
        private val casePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5E7EB")
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
            super.onDraw(canvas)
            
            val width = width.toFloat()
            val height = height.toFloat()
            val centerX = width / 2
            
            val tubeWidth = width * 0.2f
            val tubeLeft = centerX - tubeWidth / 2
            val tubeRight = centerX + tubeWidth / 2
            val tubeTop = height * 0.1f
            val tubeBottom = height * 0.75f
            val tubeHeight = tubeBottom - tubeTop
            
            canvas.drawRoundRect(
                tubeLeft, tubeTop, tubeRight, tubeBottom, 
                tubeWidth / 3, tubeWidth / 3, casePaint
            )
            
            val bulbRadius = tubeWidth * 1f
            val bulbCenterY = height - bulbRadius * 1f
            
            canvas.drawCircle(centerX, bulbCenterY, bulbRadius, bulbPaint)
            
            val minTemp = 0
            val maxTemp = 90.0
            val normalizedTemp = (temperature - minTemp) / (maxTemp - minTemp)
            val mercuryLevel = tubeBottom - (tubeHeight * normalizedTemp.toFloat().coerceIn(0f, 1f))
            
            mercuryPaint.color = when {
                temperature < 15 -> Color.parseColor("#25AFFF")
                temperature > 45 -> Color.parseColor("#DC2626")
                else -> Color.parseColor("#25AFFF")
            }
            
            bulbPaint.color = mercuryPaint.color
            
            val mercuryWidth = tubeWidth * 0.5f
            val mercuryLeft = centerX - mercuryWidth / 2
            val mercuryRight = centerX + mercuryWidth / 2
            
            val mercuryBottom = tubeBottom.coerceAtMost(bulbCenterY - bulbRadius * 0.5f)
            
            canvas.drawRoundRect(
                mercuryLeft, mercuryLevel, mercuryRight, mercuryBottom, 
                mercuryWidth / 2, mercuryWidth / 2, mercuryPaint
            )
            
            val scaleCount = 5
            for (i in 1 until scaleCount) {
                val markY = tubeTop + (tubeHeight * i / (scaleCount - 1))
                canvas.drawLine(
                    tubeRight + 5, markY,
                    tubeRight + 15, markY, scalePaint
                )
            }
            
            val connectorWidth = tubeWidth * 0.4f
            val connectorLeft = centerX - connectorWidth / 2
            val connectorRight = centerX + connectorWidth / 2
            canvas.drawRect(
                connectorLeft, tubeBottom,
                connectorRight, bulbCenterY - bulbRadius * 0.8f,
                casePaint
            )
        }
    }

    // ===== Gauge Style 3 (Modern half-circle) =====
    class ModernHalfGauge(context: Context) : View(context) {
        private var pct = 0
        private var label = "SOC"

        private val radiusScale = 0.75f

        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5E7EB")
            style = Paint.Style.STROKE
            strokeWidth = 30f
            strokeCap = Paint.Cap.ROUND
        }
        private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 30f
            strokeCap = Paint.Cap.ROUND
        }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9CA3AF")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val tickBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6B7280")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EF4444")
            style = Paint.Style.FILL
        }
        private val pointerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80EF4444")
            style = Paint.Style.FILL
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#FFEF4444"))
        }
        private val socPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB")
            textAlign = Paint.Align.LEFT
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB")
            textAlign = Paint.Align.LEFT
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        private val textLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#374151")
            textAlign = Paint.Align.CENTER
            textSize = 32f
        }

        fun setPercent(v: Int) { pct = v.coerceIn(0, 100); invalidate() }
        fun setLabel(s: String) { label = s; invalidate() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = max((w * 0.55f).roundToInt(), 260)
            setMeasuredDimension(w, h)
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val pad = 36f
            val w = width.toFloat()
            val h = height.toFloat()
            val baseSize = min(w - pad * 2, h * 2.0f - pad * 2)
            val size = baseSize * radiusScale
            val rect = RectF(
                (w - size) / 2f, pad + (baseSize - size) / 2f,
                (w + size) / 2f, pad + (baseSize - size) / 2f + size
            )

            val startAngle = 180f
            val sweepTotal = 180f

            c.drawArc(rect, startAngle, sweepTotal, false, track)

            drawTicks(c, rect, startAngle, sweepTotal)

            val levelColor = when {
                pct < 15 -> Color.RED
                pct < 30 -> Color.YELLOW
                pct <= 80 -> Color.GREEN
                else -> Color.BLUE
            }
            
            progress.color = levelColor
            progress.shader = null

            val sweep = sweepTotal * (pct / 100f)
            c.drawArc(rect, startAngle, sweep, false, progress)

            setLayerType(LAYER_TYPE_SOFTWARE, pointerGlow)
            drawPointer(c, rect, startAngle + sweep)
            setLayerType(LAYER_TYPE_HARDWARE, null)

            drawLabels(c, rect, startAngle, sweepTotal)

            val gap = 44f
            val socText = label
            val pctText = "$pct%"
            val socW = socPaint.measureText(socText)
            val pctW = pctPaint.measureText(pctText)
            val totalW = socW + gap + pctW
            val y = rect.centerY() - rect.height()*0.18f
            val startX = (w - totalW) / 2f
            val fm = socPaint.fontMetrics
            val baseline = y - (fm.ascent + fm.descent)/2f
            c.drawText(socText, startX, baseline, socPaint)
            c.drawText(pctText, startX + socW + gap, baseline, pctPaint)
        }

        private fun drawTicks(c: Canvas, rect: RectF, start: Float, sweep: Float) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val rOuter = rect.width() / 2f
            val rInnerThin = rOuter - 18f
            val rInnerBold = rOuter - 26f

            for (i in 0..10) {
                val ang = Math.toRadians((start + sweep * (i / 10f)).toDouble())
                val inner = if (i % 5 == 0) rInnerBold else rInnerThin
                val p = if (i % 5 == 0) tickBold else tick
                val sx = (cx + inner * cos(ang)).toFloat()
                val sy = (cy + inner * sin(ang)).toFloat()
                val ex = (cx + rOuter * cos(ang)).toFloat()
                val ey = (cy + rOuter * sin(ang)).toFloat()
                c.drawLine(sx, sy, ex, ey, p)
            }
        }

        private fun drawLabels(c: Canvas, rect: RectF, start: Float, sweep: Float) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val r = rect.width() / 2f + 24f
            val marks = listOf(0, 25, 50, 75, 100)
            for (m in marks) {
                val a = Math.toRadians((start + sweep * (m / 100f)).toDouble())
                val x = (cx + r * cos(a)).toFloat()
                val y = (cy + r * sin(a)).toFloat()
                c.drawText("${m}%", x, y, textLabel)
            }
        }

        private fun drawPointer(c: Canvas, rect: RectF, angleDeg: Float) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val r = rect.width() / 2.25f
            val a = Math.toRadians(angleDeg.toDouble())
            val tipX = (cx + r * cos(a)).toFloat()
            val tipY = (cy + r * sin(a)).toFloat()
            val baseW = 16f
            val back = 42f
            val perp = a + Math.PI / 2
            val b1x = (cx - back * cos(a) + baseW * cos(perp)).toFloat()
            val b1y = (cy - back * sin(a) + baseW * sin(perp)).toFloat()
            val b2x = (cx - back * cos(a) - baseW * cos(perp)).toFloat()
            val b2y = (cy - back * sin(a) - baseW * sin(perp)).toFloat()
            val path = Path()
            path.moveTo(tipX, tipY)
            path.lineTo(b1x, b1y)
            path.lineTo(b2x, b2y)
            path.close()
            
            c.drawPath(path, pointerGlow)
            c.drawPath(path, pointer)
            c.drawCircle(cx, cy, 12f, pointer)
        }
    }
}