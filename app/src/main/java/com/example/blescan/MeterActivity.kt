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
import android.view.Gravity
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

    // Cell voltage protection registers
    private val REG_CELL_UNDERVOLTAGE_PROTECTION = 0x26
    private val REG_CELL_UNDERVOLTAGE_RELEASE = 0x27
    private val REG_PACK_UNDERVOLTAGE_PROTECTION = 0x22  // Corrected from 0x21
    private val REG_PACK_UNDERVOLTAGE_RELEASE = 0x23     // Corrected from 0x22
    private val REG_FET_CONTROL = 0x24                   // FET control register

    // Factory mode commands
    private val CMD_ENTER_FACTORY = hex("DD 5A 00 02 56 78 FF 30 77")
    private val CMD_EXIT_FACTORY = hex("DD 5A 01 02 28 28 FF AD 77")
    
    // FET Control commands
    private val CMD_FET_BOTH_ON = hex("DD 5A E1 02 00 00 FF 1D 77")
    
    // Default voltage settings
    private val DEFAULT_CELL_UNDERVOLTAGE = 2.7f
    private val DEFAULT_CELL_UNDERVOLTAGE_RELEASE = 2.8f
    private val TEMP_CELL_UNDERVOLTAGE = 2.0f
    private val TEMP_CELL_UNDERVOLTAGE_RELEASE = 2.1f

    // Cell count management
    private var cellCount = 0

    // FET Status
    private var fetStatus = "Unknown"

    private fun calculateChecksumForWrite(register: Int, data: ByteArray): Int {
        var sum = register + data.size
        for (byte in data) {
            sum += byte.toInt() and 0xFF
        }
        return (0x10000 - sum) and 0xFFFF
    }

    private fun createWriteCommand(register: Int, data: ByteArray): ByteArray {
        val checksum = calculateChecksumForWrite(register, data)
        
        return byteArrayOf(
            JBD_START,
            JBD_WRITE,
            register.toByte(),
            data.size.toByte(),
            *data,
            ((checksum shr 8) and 0xFF).toByte(),
            (checksum and 0xFF).toByte(),
            JBD_END
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
    private lateinit var btnWriteAndEnable: Button

    private lateinit var adapterLv: ArrayAdapter<String>
    private val rows = mutableListOf<String>()                     // "MAC  Name"
    private val devices = LinkedHashMap<String, BluetoothDevice>() // MAC -> device
    private val advertisedName = HashMap<String, String>()         // MAC -> name from scan

    // --- BLE ---
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var chNotify: BluetoothGattCharacteristic? = null
    private var chWrite: BluetoothGattCharacteristic? = null
    private val rxBuffer = ArrayList<Byte>()

    // EEPROM write state management
    private var isWritingEEPROM = false
    private var eepromWriteStep = 0

    // periodic polling while connected
    private val pollIntervalMs = 1000L
    private val pollTask = object : Runnable {
        override fun run() {
            if (!isWritingEEPROM) {
                chWrite?.let { w ->
                    gatt?.let { g ->
                        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        w.value = CMD_BASIC_INFO
                        g.writeCharacteristic(w)
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

        // Gauge style 3 (modern half-circle)
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

        // Create FET Control & EEPROM card
        fun makeFetControlCard(): Pair<LinearLayout, Pair<TextView, Button>> {
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
            
            // Left side - FET Status
            val leftLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    rightMargin = 16
                }
            }
            
            val statusTitle = TextView(this).apply {
                text = "BMS Status"
                textSize = 16f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            val statusValue = TextView(this).apply {
                text = "Charge: - | Discharge: -"
                textSize = 16f
                setTextColor(Color.WHITE)
            }
            
            leftLayout.addView(statusTitle)
            leftLayout.addView(statusValue)
            
            // Right side - Control Button
            val rightLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER
            }
            
            val controlButton = Button(this).apply {
                text = "Switch ON"
                setBackgroundColor(Color.parseColor("#DC2626"))
                setTextColor(Color.WHITE)
                setPadding(32, 16, 32, 16)
                textSize = 16f
                setOnClickListener {
                    showWriteEepromDialog()
                }
            }
            
            rightLayout.addView(controlButton)
            
            card.addView(leftLayout)
            card.addView(rightLayout)
            
            return card to (statusValue to controlButton)
        }

        val (cardName, nameValue) = makeCard("Device", "#3B82F6")
        val (cardVolt, voltValue) = makeCard("Voltage (V)", "#10B981")
        val (cardCurr, currValue) = makeCard("Current (A)", "#DC143C")
        val (cardTemp, tempPair) = makeThermometerCard()
        val (fetControlCard, fetPair) = makeFetControlCard()
        
        tvVolt = voltValue
        tvCurr = currValue
        tvTemp = tempPair.first
        thermometerView = tempPair.second
        tvName = nameValue
        tvFetStatus = fetPair.first
        btnWriteAndEnable = fetPair.second

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
            addView(fetControlCard)
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
    }

    private fun showWriteEepromDialog() {
        if (gatt == null || chWrite == null) {
            toast("Not connected to BMS")
            return
        }

        if (cellCount == 0) {
            toast("Cell count not available yet")
            return
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Write Cell & Pack Voltage Settings"
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        dialogView.addView(title)

        // Calculate pack voltages based on cell count
        val packUndervoltage = (TEMP_CELL_UNDERVOLTAGE * cellCount * 100).toInt()
        val packUndervoltageRelease = (TEMP_CELL_UNDERVOLTAGE_RELEASE * cellCount * 100).toInt()
        val defaultPackUndervoltage = (DEFAULT_CELL_UNDERVOLTAGE * cellCount * 100).toInt()
        val defaultPackUndervoltageRelease = (DEFAULT_CELL_UNDERVOLTAGE_RELEASE * cellCount * 100).toInt()
/*
        val infoText = TextView(this).apply {
            text = "Detected: ${cellCount}S Configuration\n\n" +
                    "Initial Settings (30 seconds):\n" +
                    "• Cell Low Voltage Cutoff: ${TEMP_CELL_UNDERVOLTAGE}V\n" +
                    "• Cell Low Voltage Release: ${TEMP_CELL_UNDERVOLTAGE_RELEASE}V\n" +
                    "• Pack Low Voltage Cutoff: ${packUndervoltage / 100.0}V\n" +
                    "• Pack Low Voltage Release: ${packUndervoltageRelease / 100.0}V\n\n" +
                    "After 30 seconds, settings will revert to:\n" +
                    "• Cell: ${DEFAULT_CELL_UNDERVOLTAGE}V / ${DEFAULT_CELL_UNDERVOLTAGE_RELEASE}V\n" +
                    "• Pack: ${defaultPackUndervoltage / 100.0}V / ${defaultPackUndervoltageRelease / 100.0}V\n\n" +
                    "Both FETs will be turned ON after completion!"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 16, 0, 16)
        }
*/       
        dialogView.addView(infoText)

        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Write & Enable FETs") { dialog, _ ->
                writeCellVoltageSettings()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        alertDialog.show()
    }

    private fun writeCellVoltageSettings() {
        if (gatt == null || chWrite == null) {
            toast("Not connected to BMS")
            return
        }

        // Set EEPROM write flag to stop polling
        isWritingEEPROM = true
        eepromWriteStep = 1
        
        // Update button state
        runOnUiThread {
            btnWriteAndEnable.isEnabled = false
            btnWriteAndEnable.text = "E.Start..."
        }
        
        // Clear UI values to indicate write mode
        runOnUiThread {
            gauge.setPercent(0)
            tvVolt.text = "-"
            tvCurr.text = "-"
            tvTemp.text = "-"
            tvFetStatus.text = "Charge: - | Discharge: -"
            thermometerView.setTemperature(0.0)
        }
        
        // Start the EEPROM write sequence
        handler.post {
            executeEepromWriteSequence()
        }
    }

    private fun executeEepromWriteSequence() {
        when (eepromWriteStep) {
            1 -> {
                // Enter factory mode
                writeToCharacteristic(CMD_ENTER_FACTORY)
                
                handler.postDelayed({
                    eepromWriteStep = 2
                    executeEepromWriteSequence()
                }, 1000)
            }
            2 -> {
                // Write cell undervoltage protection (2.0V = 2000mV)
                val undervoltageData = byteArrayOf(0x07.toByte(), 0xD0.toByte())
                val undervoltageCmd = createWriteCommand(REG_CELL_UNDERVOLTAGE_PROTECTION, undervoltageData)
                writeToCharacteristic(undervoltageCmd)
                
                handler.postDelayed({
                    eepromWriteStep = 3
                    executeEepromWriteSequence()
                }, 1000)
            }
            3 -> {
                // Write cell undervoltage release (2.1V = 2100mV)
                val undervoltageReleaseData = byteArrayOf(0x08.toByte(), 0x34.toByte())
                val undervoltageReleaseCmd = createWriteCommand(REG_CELL_UNDERVOLTAGE_RELEASE, undervoltageReleaseData)
                writeToCharacteristic(undervoltageReleaseCmd)
                
                handler.postDelayed({
                    eepromWriteStep = 4
                    executeEepromWriteSequence()
                }, 1000)
            }
            4 -> {
                // Calculate and write pack undervoltage protection
                val packUndervoltage = (TEMP_CELL_UNDERVOLTAGE * cellCount * 100).toInt()
                val packUndervoltageData = byteArrayOf(
                    ((packUndervoltage shr 8) and 0xFF).toByte(),
                    (packUndervoltage and 0xFF).toByte()
                )
                val packUndervoltageCmd = createWriteCommand(REG_PACK_UNDERVOLTAGE_PROTECTION, packUndervoltageData)
                writeToCharacteristic(packUndervoltageCmd)
                
                handler.postDelayed({
                    eepromWriteStep = 5
                    executeEepromWriteSequence()
                }, 1000)
            }
            5 -> {
                // Calculate and write pack undervoltage release
                val packUndervoltageRelease = (TEMP_CELL_UNDERVOLTAGE_RELEASE * cellCount * 100).toInt()
                val packUndervoltageReleaseData = byteArrayOf(
                    ((packUndervoltageRelease shr 8) and 0xFF).toByte(),
                    (packUndervoltageRelease and 0xFF).toByte()
                )
                val packUndervoltageReleaseCmd = createWriteCommand(REG_PACK_UNDERVOLTAGE_RELEASE, packUndervoltageReleaseData)
                writeToCharacteristic(packUndervoltageReleaseCmd)
                
                handler.postDelayed({
                    eepromWriteStep = 6
                    executeEepromWriteSequence()
                }, 1000)
            }
            6 -> {
                // Exit factory mode
                writeToCharacteristic(CMD_EXIT_FACTORY)
                
                handler.postDelayed({
                    // TURN BOTH FETs ON
                    controlFets()
                    
                    runOnUiThread {
                        btnWriteAndEnable.text = "B2Normal(30s)..."
                    }
                    toast("Initial settings written. Reverting in 30 seconds...")
                    
                    // Resume polling to update basic info while waiting for revert
                    isWritingEEPROM = false
                    
                    // Schedule the revert operation after 30 seconds
                    handler.postDelayed({
                        revertToDefaultSettings()
                    }, 30000)
                    
                }, 1000)
            }
        }
    }

    private fun revertToDefaultSettings() {
        isWritingEEPROM = true
        eepromWriteStep = 101
        
        runOnUiThread {
            btnWriteAndEnable.text = "Reverting..."
        }
        
        handler.post {
            executeRevertSequence()
        }
    }

    private fun executeRevertSequence() {
        when (eepromWriteStep) {
            101 -> {
                writeToCharacteristic(CMD_ENTER_FACTORY)
                
                handler.postDelayed({
                    eepromWriteStep = 102
                    executeRevertSequence()
                }, 1000)
            }
            102 -> {
                // Write default cell undervoltage protection (2.7V = 2700mV)
                val undervoltageData = byteArrayOf(0x0A.toByte(), 0x8C.toByte())
                val undervoltageCmd = createWriteCommand(REG_CELL_UNDERVOLTAGE_PROTECTION, undervoltageData)
                writeToCharacteristic(undervoltageCmd)
                
                handler.postDelayed({
                    eepromWriteStep = 103
                    executeRevertSequence()
                }, 1000)
            }
            103 -> {
                // Write default cell undervoltage release (2.8V = 2800mV)
                val undervoltageReleaseData = byteArrayOf(0x0A.toByte(), 0xF0.toByte())
                val undervoltageReleaseCmd = createWriteCommand(REG_CELL_UNDERVOLTAGE_RELEASE, undervoltageReleaseData)
                writeToCharacteristic(undervoltageReleaseCmd)
                
                handler.postDelayed({
                    eepromWriteStep = 104
                    executeRevertSequence()
                }, 1000)
            }
            104 -> {
                // Calculate and write default pack undervoltage protection
                val packUndervoltage = (DEFAULT_CELL_UNDERVOLTAGE * cellCount * 100).toInt()
                val packUndervoltageData = byteArrayOf(
                    ((packUndervoltage shr 8) and 0xFF).toByte(),
                    (packUndervoltage and 0xFF).toByte()
                )
                val packUndervoltageCmd = createWriteCommand(REG_PACK_UNDERVOLTAGE_PROTECTION, packUndervoltageData)
                writeToCharacteristic(packUndervoltageCmd)
                
                handler.postDelayed({
                    eepromWriteStep = 105
                    executeRevertSequence()
                }, 1000)
            }
            105 -> {
                // Calculate and write default pack undervoltage release
                val packUndervoltageRelease = (DEFAULT_CELL_UNDERVOLTAGE_RELEASE * cellCount * 100).toInt()
                val packUndervoltageReleaseData = byteArrayOf(
                    ((packUndervoltageRelease shr 8) and 0xFF).toByte(),
                    (packUndervoltageRelease and 0xFF).toByte()
                )
                val packUndervoltageReleaseCmd = createWriteCommand(REG_PACK_UNDERVOLTAGE_RELEASE, packUndervoltageReleaseData)
                writeToCharacteristic(packUndervoltageReleaseCmd)
                
                handler.postDelayed({
                    eepromWriteStep = 106
                    executeRevertSequence()
                }, 1000)
            }
            106 -> {
                writeToCharacteristic(CMD_EXIT_FACTORY)
                
                handler.postDelayed({
                    // TURN BOTH FETs ON again after revert
                    controlFets()
                    
                    // Clear EEPROM write flag to resume polling
                    isWritingEEPROM = false
                    eepromWriteStep = 0
                    
                    // Reset button state
                    runOnUiThread {
                        btnWriteAndEnable.isEnabled = true
                        btnWriteAndEnable.text = "Force ON"
                    }
                    
                    toast("Settings reverted and FETs enabled")
                    
                    // Force immediate update of basic info
                    handler.post {
                        if (!isWritingEEPROM) {
                            chWrite?.let { w ->
                                gatt?.let { g ->
                                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                    w.value = CMD_BASIC_INFO
                                    g.writeCharacteristic(w)
                                }
                            }
                        }
                    }
                    
                }, 1000)
            }
        }
    }

    private fun controlFets() {
        if (gatt == null || chWrite == null) {
            toast("Not connected to BMS")
            return
        }

        writeToCharacteristic(CMD_FET_BOTH_ON)
        
        // Update FET status immediately
        runOnUiThread {
            tvFetStatus.text = "Charge: ON | Discharge: ON"
        }
        
        toast("FETs: Both ON")
        
        // Request basic info update to confirm FET status
        handler.postDelayed({
            if (!isWritingEEPROM) {
                chWrite?.let { w ->
                    gatt?.let { g ->
                        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        w.value = CMD_BASIC_INFO
                        g.writeCharacteristic(w)
                    }
                }
            }
        }, 1000)
    }

    private fun writeToCharacteristic(data: ByteArray) {
        chWrite?.let { w ->
            gatt?.let { g ->
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = data
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
        tvFetStatus.text = "Charge: - | Discharge: -"
        thermometerView.setTemperature(0.0)

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

    private fun connectTo(device: BluetoothDevice) {
        stopScan()
        
        disconnectFromCurrentDevice()
        
        toast("Connecting to ${device.address}...")
        
        gauge.setPercent(0)
        tvVolt.text = "-"
        tvCurr.text = "-"
        tvTemp.text = "-"
        tvFetStatus.text = "Charge: - | Discharge: -"
        thermometerView.setTemperature(0.0)
        
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
        cellCount = 0
        fetStatus = "Charge: - | Discharge: -"
        
        gatt?.let { g ->
            try {
                g.disconnect()
                g.close()
            } catch (e: Exception) {
            }
            gatt = null
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread { toast("State: ${stateName(newState)} (status=$status)") }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(pollTask)
                chNotify = null
                chWrite = null
                rxBuffer.clear()
                cellCount = 0
                fetStatus = "Charge: - | Discharge: -"
                g.close()
                gatt = null
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
                } else {
                    toast("Amitis service ready")
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
            handler.postDelayed(pollTask, 300)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == AMITIS_READ_CH) {
                val data = ch.value ?: return
                onAmitisBytes(data)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == AMITIS_WRITE_CH) {
                // Write confirmation handled
            }
        }
    }

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
        if (p.size < 38) return // Ensure we have enough data for register 0x25
        
        // Extract basic info
        val vRaw = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val iRawU = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        var iRaw = iRawU
        if ((iRaw and 0x8000) != 0) iRaw = -((iRaw xor 0xFFFF) + 1)
        val voltage = vRaw / 100.0
        val current = iRaw / 100.0
        val soc = p[19].toInt() and 0xFF

        // Extract cell count from register 0x25 (position 21 in payload)
        val newCellCount = p[21].toInt() and 0xFF
        
        if (newCellCount > 0 && newCellCount <= 24 && newCellCount != cellCount) {
            cellCount = newCellCount
            toast("Detected ${cellCount}S configuration")
        }

        // Extract FET status (byte 20 in basic info response)
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

        // Extract temperature
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
            
            // Update FET status display with detailed information
            val fetStatusText = buildString {
                append("Charge: ")
                append(if (chargeFET) "ON" else "OFF")
                append(" | Discharge: ")
                append(if (dischargeFET) "ON" else "OFF")
                
                // Optionally show current limit status if needed
                if (chargeCurrentLimit || dischargeCurrentLimit) {
                    append("\nLimits: ")
                    if (chargeCurrentLimit) append("Chg ")
                    if (dischargeCurrentLimit) append("Dischg")
                }
            }
            tvFetStatus.text = fetStatusText
        }
    }

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