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
    
    // ========== CORRECTED FACTORY MODE & UNDER VOLTAGE PROTECTION COMMANDS ==========
    // Based on verified JBD BMS protocol from OverkillSolarBMS documentation
    
    // === FACTORY MODE CONTROL ===
    // Register 0x00: Factory Mode Entry
    // Command: Write 0x56 0x78 to register 0x00 to enter factory mode
    // This unlocks access to EEPROM settings
    private val CMD_FACTORY_MODE_ENTER = hex("DD 5A 00 02 56 78 88 77")  // Enter factory mode
    
    // === CORRECT REGISTER ADDRESSES FOR UNDER VOLTAGE PROTECTION ===
    // According to verified JBD register map:
    // Register 0x8A: Cell Under Voltage Protection (UVP) threshold - in mV per cell
    // Register 0x8B: Cell Under Voltage Protection Release (UVPR) - in mV per cell
    // Register 0x89: Pack Under Voltage (not typically modified, but included for reference)
    
    // Cell UVP Settings (Register 0x8A) - Cutoff voltage per cell
    // Format: 2 bytes, little-endian, mV per cell
    // 2.5V = 2500mV = 0x09C4 → bytes: C4 09 (little-endian)
    private val CMD_SET_CELL_UVP_2_5V = byteArrayOf(0xDD.toByte(), 0x5A.toByte(), 0x8A.toByte(), 0x02.toByte(), 0xC4.toByte(), 0x09.toByte(), 0x6E.toByte(), 0x77.toByte())
    private val CMD_SET_CELL_UVP_2_0V = byteArrayOf(0xDD.toByte(), 0x5A.toByte(), 0x8A.toByte(), 0x02.toByte(), 0xD0.toByte(), 0x07.toByte(), 0x6E.toByte(), 0x77.toByte())
    private val CMD_SET_CELL_UVP_1_8V = byteArrayOf(0xDD.toByte(), 0x5A.toByte(), 0x8A.toByte(), 0x02.toByte(), 0x08.toByte(), 0x07.toByte(), 0x6E.toByte(), 0x77.toByte())
    
    // Cell UVPR Settings (Register 0x8B) - Recovery voltage per cell
    // 2.75V = 2750mV = 0x0AB6 → bytes: B6 0A (little-endian)
    // 3.0V = 3000mV = 0x0BB8 → bytes: B8 0B (little-endian)
    // 2.2V = 2200mV = 0x0898 → bytes: 98 08 (little-endian)
    private val CMD_SET_CELL_UVPR_2_75V = byteArrayOf(0xDD.toByte(), 0x5A.toByte(), 0x8B.toByte(), 0x02.toByte(), 0xB6.toByte(), 0x0A.toByte(), 0x6C.toByte(), 0x77.toByte())
    private val CMD_SET_CELL_UVPR_3_0V = byteArrayOf(0xDD.toByte(), 0x5A.toByte(), 0x8B.toByte(), 0x02.toByte(), 0xB8.toByte(), 0x0B.toByte(), 0x6C.toByte(), 0x77.toByte())
    private val CMD_SET_CELL_UVPR_2_2V = byteArrayOf(0xDD.toByte(), 0x5A.toByte(), 0x8B.toByte(), 0x02.toByte(), 0x98.toByte(), 0x08.toByte(), 0x6C.toByte(), 0x77.toByte())
    
    // Force both FETs ON
    private val CMD_FET_FORCE_ON = hex("DD 5A E1 02 00 00 FF 1D 77")

    private fun cmdReadRegister(reg: Int): ByteArray {
        val r = reg and 0xFF
        val chk = (0x10000 - (r + 0)) and 0xFFFF
        return byteArrayOf(
            0xDD.toByte(), 0xA5.toByte(), r.toByte(), 0x00,
            ((chk shr 8) and 0xFF).toByte(), (chk and 0xFF).toByte(), 0x77.toByte()
        )
    }

    /**
     * Creates a write command with proper checksum calculation per JBD protocol
     * Checksum = 0x10000 - (register + length + sum(data bytes))
     * @param register EEPROM register address
     * @param data Data bytes to write (little-endian format for multi-byte values)
     * @return Complete command with checksum
     */
    private fun cmdWriteRegister(register: Int, data: ByteArray): ByteArray {
        val reg = register and 0xFF
        val len = data.size and 0xFF
        
        // Calculate checksum: sum of (register + length + data)
        var sum = (reg + len) and 0xFFFF
        for (b in data) {
            sum = (sum + (b.toInt() and 0xFF)) and 0xFFFF
        }
        val checksum = (0x10000 - sum) and 0xFFFF
        
        val cmd = ByteArray(7 + data.size)
        cmd[0] = 0xDD.toByte()
        cmd[1] = 0x5A.toByte()  // Write command
        cmd[2] = reg.toByte()
        cmd[3] = len.toByte()
        for (i in data.indices) {
            cmd[4 + i] = data[i]
        }
        cmd[4 + data.size] = ((checksum shr 8) and 0xFF).toByte()
        cmd[5 + data.size] = (checksum and 0xFF).toByte()
        cmd[6 + data.size] = 0x77.toByte()
        
        return cmd
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
    private lateinit var btnSetUnderVoltage: Button

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

    private var lastFetStatus: String = ""
    private var lastChargeFET: Boolean = false
    private var lastDischargeFET: Boolean = false
    private var isInFactoryMode = false

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
        list = ListView(this)

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

        btnSetUnderVoltage = Button(this).apply {
            text = "Configure Under Voltage Protection"
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(16, 10, 16, 10)
            layoutParams = lp
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(logo)
            addView(bannerWarn)
            addView(btnScan)
            addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(gauge)
            addView(cardName)
            addView(cardVolt)
            addView(cardCurr)
            addView(cardTemp)
            addView(cardFet)
            addView(btnSetUnderVoltage)
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

        btnSetUnderVoltage.setOnClickListener {
            showUnderVoltageDialog()
        }

        fetSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                AlertDialog.Builder(this)
                    .setTitle("Force FETs ON")
                    .setMessage("This will force both FETs ON for discharging.\n\nWARNING: Use with caution!")
                    .setPositiveButton("Proceed") { _, _ ->
                        forceFetsOn()
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

    /**
     * Show dialog for under voltage protection configuration
     */
    private fun showUnderVoltageDialog() {
        if (gatt == null || chWrite == null) {
            toast("Not connected to BMS")
            return
        }

        val options = arrayOf(
            "Standard (2.5V cutoff, 2.75V recovery)",
            "Conservative (2.0V cutoff, 2.2V recovery)",
            "Aggressive (2.0V cutoff, 2.75V recovery)",
            "Emergency (1.8V cutoff, 2.2V recovery)"
        )

        AlertDialog.Builder(this)
            .setTitle("Select Under Voltage Protection Profile")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setUnderVoltageStandard()
                    1 -> setUnderVoltageConservative()
                    2 -> setUnderVoltageAggressive()
                    3 -> setUnderVoltageEmergency()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Standard profile: 2.5V cutoff, 2.75V recovery
     * Flow: Enter Factory Mode → Set UVP → Set UVPR → Exit Factory Mode
     */
    private fun setUnderVoltageStandard() {
        chWrite?.let { w ->
            gatt?.let { g ->
                toast("Standard UVP: 2.5V cutoff, 2.75V recovery...")
                
                // Step 1: Enter Factory Mode
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = CMD_FACTORY_MODE_ENTER
                g.writeCharacteristic(w)
                isInFactoryMode = true
                
                handler.postDelayed({
                    // Step 2: Set Cell UVP to 2.5V (Register 0x8A)
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    w.value = CMD_SET_CELL_UVP_2_5V
                    g.writeCharacteristic(w)
                    
                    handler.postDelayed({
                        // Step 3: Set Cell UVPR to 2.75V (Register 0x8B)
                        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        w.value = CMD_SET_CELL_UVPR_2_75V
                        g.writeCharacteristic(w)
                        
                        handler.postDelayed({
                            // Step 4: Exit Factory Mode (power cycle or send exit command if available)
                            exitFactoryMode()
                            toast("Standard UVP settings applied successfully!")
                            isInFactoryMode = false
                            queryCurrentSettings()
                        }, 300)
                    }, 300)
                }, 300)
            }
        }
    }

    /**
     * Conservative profile: 2.0V cutoff, 2.2V recovery
     */
    private fun setUnderVoltageConservative() {
        chWrite?.let { w ->
            gatt?.let { g ->
                toast("Conservative UVP: 2.0V cutoff, 2.2V recovery...")
                
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = CMD_FACTORY_MODE_ENTER
                g.writeCharacteristic(w)
                isInFactoryMode = true
                
                handler.postDelayed({
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    w.value = CMD_SET_CELL_UVP_2_0V
                    g.writeCharacteristic(w)
                    
                    handler.postDelayed({
                        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        w.value = CMD_SET_CELL_UVPR_2_2V
                        g.writeCharacteristic(w)
                        
                        handler.postDelayed({
                            exitFactoryMode()
                            toast("Conservative UVP settings applied!")
                            isInFactoryMode = false
                            queryCurrentSettings()
                        }, 300)
                    }, 300)
                }, 300)
            }
        }
    }

    /**
     * Aggressive profile: 2.0V cutoff, 2.75V recovery
     */
    private fun setUnderVoltageAggressive() {
        chWrite?.let { w ->
            gatt?.let { g ->
                toast("Aggressive UVP: 2.0V cutoff, 2.75V recovery...")
                
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = CMD_FACTORY_MODE_ENTER
                g.writeCharacteristic(w)
                isInFactoryMode = true
                
                handler.postDelayed({
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    w.value = CMD_SET_CELL_UVP_2_0V
                    g.writeCharacteristic(w)
                    
                    handler.postDelayed({
                        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        w.value = CMD_SET_CELL_UVPR_2_75V
                        g.writeCharacteristic(w)
                        
                        handler.postDelayed({
                            exitFactoryMode()
                            toast("Aggressive UVP settings applied!")
                            isInFactoryMode = false
                            queryCurrentSettings()
                        }, 300)
                    }, 300)
                }, 300)
            }
        }
    }

    /**
     * Emergency profile: 1.8V cutoff, 2.2V recovery
     * ⚠️ WARNING: Can damage LiFePO4 cells
     */
    private fun setUnderVoltageEmergency() {
        AlertDialog.Builder(this)
            .setTitle("Emergency Profile")
            .setMessage("⚠️ WARNING: 1.8V cutoff can DAMAGE LiFePO4 cells!\n\nThis should ONLY be used as a last resort for emergency discharge.\n\nProceed?")
            .setPositiveButton("I Understand - Proceed") { _, _ ->
                chWrite?.let { w ->
                    gatt?.let { g ->
                        toast("Emergency UVP: 1.8V cutoff, 2.2V recovery...")
                        
                        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        w.value = CMD_FACTORY_MODE_ENTER
                        g.writeCharacteristic(w)
                        isInFactoryMode = true
                        
                        handler.postDelayed({
                            w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            w.value = CMD_SET_CELL_UVP_1_8V
                            g.writeCharacteristic(w)
                            
                            handler.postDelayed({
                                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                w.value = CMD_SET_CELL_UVPR_2_2V
                                g.writeCharacteristic(w)
                                
                                handler.postDelayed({
                                    exitFactoryMode()
                                    toast("Emergency UVP settings applied! ⚠️ Use with extreme caution!")
                                    isInFactoryMode = false
                                    queryCurrentSettings()
                                }, 300)
                            }, 300)
                        }, 300)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Exit factory mode
     * Note: Most JBD BMS implementations don't have explicit exit command
     * Factory mode typically times out automatically or resets on next power cycle
     * Some BMS may respond to: write 0xFF 0xFF to register 0x00
     */
    private fun exitFactoryMode() {
        chWrite?.let { w ->
            gatt?.let { g ->
                // Send a benign command to reset BMS state
                // This triggers BMS to save settings and exit configuration mode
                toast("Saving settings and exiting factory mode...")
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = CMD_BASIC_INFO  // Query basic info to trigger state reset
                g.writeCharacteristic(w)
                
                handler.postDelayed({
                    toast("Factory mode exited. Settings saved.")
                }, 500)
            }
        }
    }

    /**
     * Force both FETs ON
     */
    private fun forceFetsOn() {
        chWrite?.let { w ->
            gatt?.let { g ->
                toast("Forcing both FETs ON...")
                
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = CMD_FET_FORCE_ON
                g.writeCharacteristic(w)
                
                handler.postDelayed({
                    queryCurrentSettings()
                }, 500)
            }
        }
    }

    /**
     * Query current BMS settings to verify configuration
     */
    private fun queryCurrentSettings() {
        chWrite?.let { w ->
            gatt?.let { g ->
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = CMD_BASIC_INFO
                g.writeCharacteristic(w)
            }
        }
    }

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
        override fun onScanFailed(code: Int) { toast("Scan failed: $code") }
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
        isInFactoryMode = false

        scanning = true
        toast("Scanning for ${SCAN_MS/1000}s...")
        
        handler.postDelayed({
            stopScan()
            toast("Scan done: ${rows.size} device(s) found")
        }, SCAN_MS)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
            
        val filters = mutableListOf<ScanFilter>()
        
        try {
            scanner?.startScan(filters, settings, scanCb)
        } catch (e: SecurityException) {
            toast("Permission denied for Bluetooth scanning")
            scanning = false
        } catch (e: Exception) {
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
        
        toast("Connecting to ${device.address}...")
        
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
        isInFactoryMode = false
        
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else
            device.connectGatt(this, false, gattCb)
    }

    private fun disconnectFromCurrentDevice() {
        handler.removeCallbacks(pollTask)
        chNotify = null
        chWrite = null
        rxBuffer.clear()
        isInFactoryMode = false
        
        gatt?.let { g ->
            try {
                g.disconnect()
                g.close()
            } catch (e: Exception) {
                // ignore errors during disconnect
            }
            gatt = null
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread { 
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
                g.close()
                gatt = null
                isInFactoryMode = false
                
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
                    toast("Amitis FF00/FF01/FF02 not found")
                    disconnectFromCurrentDevice()
                    fetSwitch.isChecked = false
                    tvFetStatus.text = "Service not found"
                } else {
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
            handler.removeCallbacks(pollTask)
            handler.postDelayed(pollTask, 500)
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

    private fun handleBasicInfo(p: ByteArray) {
        if (p.size < 24) return
        val vRaw = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val iRawU = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        var iRaw = iRawU
        if ((iRaw and 0x8000) != 0) iRaw = -((iRaw xor 0xFFFF) + 1)
        val voltage = vRaw / 100.0
        val current = iRaw / 100.0
        val soc = p[19].toInt() and 0xFF

        val fetStatusByte = if (p.size > 20) p[20].toInt() and 0xFF else 0
        
        val chargeFET = (fetStatusByte and 0x01) != 0
        val dischargeFET = (fetStatusByte and 0x02) != 0
        val chargeCurrentLimit = (fetStatusByte and 0x04) != 0
        val dischargeCurrentLimit = (fetStatusByte and 0x08) != 0

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
            
            val factoryModeStatus = if (isInFactoryMode) " (Factory Mode)" else ""
            val fetStatusText = buildString {
                append("Charge: ")
                append(if (chargeFET) "ON" else "OFF")
                append(" | Discharge: ")
                append(if (dischargeFET) "ON" else "OFF")
                append(factoryModeStatus)
                
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