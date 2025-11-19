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
import android.text.method.ScrollingMovementMethod
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
    private val CMD_CELL_INFO = hex("DD A5 04 00 FF FC 77") // Command to read cell info

    // JBD Protocol Constants
    private val JBD_START: Byte = 0xDD.toByte()
    private val JBD_END: Byte = 0x77.toByte()
    private val JBD_READ: Byte = 0xA5.toByte()
    private val JBD_WRITE: Byte = 0x5A.toByte()

    // Cell voltage protection registers
    private val REG_CELL_UNDERVOLTAGE_PROTECTION = 0x26
    private val REG_CELL_UNDERVOLTAGE_RELEASE = 0x27
    private val REG_PACK_UNDERVOLTAGE_PROTECTION = 0x21
    private val REG_PACK_UNDERVOLTAGE_RELEASE = 0x22

    // Factory mode commands - CORRECTED based on your working command
    private val CMD_ENTER_FACTORY = hex("DD 5A 00 02 56 78 FF 30 77")
    private val CMD_EXIT_FACTORY = hex("DD 5A 01 02 28 28 D0 77")

    // Default voltage settings
    private val DEFAULT_CELL_UNDERVOLTAGE = 2.7f
    private val DEFAULT_CELL_UNDERVOLTAGE_RELEASE = 2.8f
    private val TEMP_CELL_UNDERVOLTAGE = 2.0f
    private val TEMP_CELL_UNDERVOLTAGE_RELEASE = 2.1f

    // Cell count management
    private var cellCount = 0
    private var isReadingCellCount = false

    private fun calculateChecksumForWrite(register: Int, data: ByteArray): Int {
        // For write commands: checksum = 0x10000 - (register + length + data bytes)
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

    private fun parseResponse(data: ByteArray): Triple<Boolean, Int, ByteArray>? {
        if (data.size < 7) {
            addDebugLog("ü7├4 Response too short: ${data.size} bytes")
            return null
        }
        
        if (data[0] != JBD_START || data[data.size - 1] != JBD_END) {
            addDebugLog("ü7├4 Invalid response frame")
            return null
        }
        
        val command = data[1].toInt() and 0xFF
        val status = data[2].toInt() and 0xFF
        val length = data[3].toInt() and 0xFF
        
        if (data.size < 4 + length + 3) {
            addDebugLog("ü7├4 Insufficient data for length $length")
            return null
        }
        
        val payload = data.copyOfRange(4, 4 + length)
        val checksumStart = 4 + length
        val receivedChecksum = ((data[checksumStart].toInt() and 0xFF) shl 8) or (data[checksumStart + 1].toInt() and 0xFF)
        
        // For response: checksum = 0x10000 - (status + length + payload)
        var sum = status + length
        for (byte in payload) {
            sum += byte.toInt() and 0xFF
        }
        val expectedChecksum = (0x10000 - sum) and 0xFFFF
        
        if (receivedChecksum != expectedChecksum) {
            addDebugLog("ü7├4 Checksum mismatch: received=0x${receivedChecksum.toString(16).uppercase()}, expected=0x${expectedChecksum.toString(16).uppercase()}")
            addDebugLog("ü7├4 Calculated from: status=0x${status.toString(16)}, length=0x${length.toString(16)}, payload=${payload.joinToString("") { "%02X".format(it) }}")
            return null
        }
        
        val success = status == 0x00
        return Triple(success, command, payload)
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
//    private lateinit var tvCellCount: TextView
    private lateinit var thermometerView: ThermometerView

    // Debug window components
    private lateinit var debugWindow: ScrollView
    private lateinit var debugText: TextView
    private lateinit var btnShowDebug: Button

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

    // Debug log
    private val debugLog = StringBuilder()

    // EEPROM write state management
    private var isWritingEEPROM = false
    private var eepromWriteStep = 0

    // periodic polling while connected
    private val pollIntervalMs = 1000L
    private val pollTask = object : Runnable {
        override fun run() {
            // Skip polling if we're writing to EEPROM or reading cell count
            if (!isWritingEEPROM && !isReadingCellCount) {
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

        // Debug window
        debugText = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            movementMethod = ScrollingMovementMethod()
            setPadding(8, 8, 8, 8)
        }

        debugWindow = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 200
            ).apply { setMargins(16, 10, 16, 6) }
            addView(debugText)
            visibility = View.GONE
        }

        btnShowDebug = Button(this).apply {
            text = "Show Debug"
            setOnClickListener {
                debugWindow.visibility = if (debugWindow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (debugWindow.visibility == View.VISIBLE) "Hide Debug" else "Show Debug"
            }
        }

        // Gauge style 3 (modern half-circle) with A1: 180ü0Å7ü0Æ1 sweep, start at 180ü0Å7ü0Æ1
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
                text = "Temperature (ü0Å7ü0Æ1C)"
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

        // Create FET Control card
        fun makeFetControlCard(): Pair<LinearLayout, LinearLayout> {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
                setBackgroundColor(Color.parseColor("#8B5CF6"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(16, 10, 16, 10)
                layoutParams = lp
                elevation = 6f
            }
            
            val titleTv = TextView(this).apply {
                text = "FET Control & EEPROM"
                textSize = 16f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            card.addView(titleTv)
            
            val buttonLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER
            }
            
            val btnWriteEEPROM = Button(this).apply {
                text = "Write Cell Voltages"
                setBackgroundColor(Color.parseColor("#DC2626"))
                setTextColor(Color.WHITE)
                setPadding(16, 8, 16, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 8, 8, 8)
                }
                setOnClickListener {
                    readCellCountAndShowDialog()
                }
            }
            
            buttonLayout.addView(btnWriteEEPROM)
            card.addView(buttonLayout)
            
            return card to buttonLayout
        }


        val (cardName, nameValue) = makeCard("Device", "#3B82F6")
        val (cardVolt, voltValue) = makeCard("Voltage (V)", "#10B981")
        val (cardCurr, currValue) = makeCard("Current (A)", "#DC143C")
        val (cardTemp, tempPair) = makeThermometerCard()
        val (fetControlCard, fetButtonLayout) = makeFetControlCard()
        
        tvVolt = voltValue
        tvCurr = currValue
        tvTemp = tempPair.first
        thermometerView = tempPair.second
        tvName = nameValue
//        tvCellCount = cellCountValue

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(logo)
            addView(bannerWarn)
            addView(btnScan)
            addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(btnShowDebug)
            addView(debugWindow)
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

    private fun readCellCountAndShowDialog() {
        if (gatt == null || chWrite == null) {
            toast("Not connected to BMS")
            return
        }

        addDebugLog("Reading cell count from BMS...")
        isReadingCellCount = true

        // Send cell info command to get cell count
        chWrite?.let { w ->
            gatt?.let { g ->
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                w.value = CMD_CELL_INFO
                g.writeCharacteristic(w)
                addDebugLog("Sent cell info command to read cell count")
            }
        }

        // Wait for response and then show dialog
        handler.postDelayed({
            isReadingCellCount = false
            if (cellCount > 0) {
                showWriteEepromDialog()
            } else {
                toast("Failed to read cell count from BMS")
                addDebugLog("Cell count reading failed or timed out")
            }
        }, 3000) // 3 second timeout for cell count reading
    }

    private fun showWriteEepromDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Write Cell & Pack Voltage Settings to EEPROM"
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        dialogView.addView(title)

        // Calculate pack voltages based on cell count
        val packUndervoltage = (TEMP_CELL_UNDERVOLTAGE * cellCount * 10).toInt() // Convert to deciVolts (x10)
        val packUndervoltageRelease = (TEMP_CELL_UNDERVOLTAGE_RELEASE * cellCount * 10).toInt()
        val defaultPackUndervoltage = (DEFAULT_CELL_UNDERVOLTAGE * cellCount * 10).toInt()
        val defaultPackUndervoltageRelease = (DEFAULT_CELL_UNDERVOLTAGE_RELEASE * cellCount * 10).toInt()

        val infoText = TextView(this).apply {
            text = "Detected: ${cellCount}S Configuration\n\n" +
                    "Initial Settings (30 seconds):\n" +
                    "ü6”1 Cell Low Voltage Cutoff: ${TEMP_CELL_UNDERVOLTAGE}V\n" +
                    "ü6”1 Cell Low Voltage Release: ${TEMP_CELL_UNDERVOLTAGE_RELEASE}V\n" +
                    "ü6”1 Pack Low Voltage Cutoff: ${packUndervoltage / 10.0}V (${cellCount}S × ${TEMP_CELL_UNDERVOLTAGE}V)\n" +
                    "ü6”1 Pack Low Voltage Release: ${packUndervoltageRelease / 10.0}V (${cellCount}S × ${TEMP_CELL_UNDERVOLTAGE_RELEASE}V)\n\n" +
                    "After 30 seconds, settings will revert to:\n" +
                    "ü6”1 Cell: ${DEFAULT_CELL_UNDERVOLTAGE}V / ${DEFAULT_CELL_UNDERVOLTAGE_RELEASE}V\n" +
                    "ü6”1 Pack: ${defaultPackUndervoltage / 10.0}V / ${defaultPackUndervoltageRelease / 10.0}V\n\n" +
                    "Make sure BMS is connected!"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 16, 0, 16)
        }
        dialogView.addView(infoText)

        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Write to EEPROM") { dialog, _ ->
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

        addDebugLog("Starting EEPROM write process...")
        addDebugLog("Stopping periodic polling to avoid data conflicts...")
        
        // Set EEPROM write flag to stop polling
        isWritingEEPROM = true
        eepromWriteStep = 1
        
        // Clear UI values to indicate write mode
        runOnUiThread {
            gauge.setPercent(0)
            tvVolt.text = "-"
            tvCurr.text = "-"
            tvTemp.text = "-"
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
                addDebugLog("Step 1: Entering factory mode...")
                
                // Enter factory mode first - using the exact command you provided
                writeToCharacteristic(CMD_ENTER_FACTORY)
                addDebugLog("Sent: Enter Factory Mode")
                addDebugLog("Command: ${bytesToHex(CMD_ENTER_FACTORY)}")
                
                // Wait 2 seconds for response and BMS processing
                handler.postDelayed({
                    eepromWriteStep = 2
                    executeEepromWriteSequence()
                }, 2000)
            }
            2 -> {
                addDebugLog("Step 2: Writing cell undervoltage protection...")
                
                // Write cell undervoltage protection (2.0V = 2000mV)
                val undervoltageData = byteArrayOf(0x07.toByte(), 0xD0.toByte()) // 2000 in big-endian
                val undervoltageCmd = createWriteCommand(REG_CELL_UNDERVOLTAGE_PROTECTION, undervoltageData)
                writeToCharacteristic(undervoltageCmd)
                addDebugLog("Sent: Cell Undervoltage Protection = 2.0V")
                addDebugLog("Command: ${bytesToHex(undervoltageCmd)}")
                
                // Wait 2 seconds for response
                handler.postDelayed({
                    eepromWriteStep = 3
                    executeEepromWriteSequence()
                }, 2000)
            }
            3 -> {
                addDebugLog("Step 3: Writing cell undervoltage release...")
                
                // Write cell undervoltage release (2.1V = 2100mV)
                val undervoltageReleaseData = byteArrayOf(0x08.toByte(), 0x34.toByte()) // 2100 in big-endian
                val undervoltageReleaseCmd = createWriteCommand(REG_CELL_UNDERVOLTAGE_RELEASE, undervoltageReleaseData)
                writeToCharacteristic(undervoltageReleaseCmd)
                addDebugLog("Sent: Cell Undervoltage Release = 2.1V")
                addDebugLog("Command: ${bytesToHex(undervoltageReleaseCmd)}")
                
                // Wait 2 seconds for response
                handler.postDelayed({
                    eepromWriteStep = 4
                    executeEepromWriteSequence()
                }, 2000)
            }
            4 -> {
                addDebugLog("Step 4: Writing pack undervoltage protection...")
                
                // Calculate and write pack undervoltage protection
                val packUndervoltage = (TEMP_CELL_UNDERVOLTAGE * cellCount * 10).toInt() // Convert to deciVolts
                val packUndervoltageData = byteArrayOf(
                    ((packUndervoltage shr 8) and 0xFF).toByte(),
                    (packUndervoltage and 0xFF).toByte()
                )
                val packUndervoltageCmd = createWriteCommand(REG_PACK_UNDERVOLTAGE_PROTECTION, packUndervoltageData)
                writeToCharacteristic(packUndervoltageCmd)
                addDebugLog("Sent: Pack Undervoltage Protection = ${packUndervoltage / 10.0}V")
                addDebugLog("Command: ${bytesToHex(packUndervoltageCmd)}")
                
                // Wait 2 seconds for response
                handler.postDelayed({
                    eepromWriteStep = 5
                    executeEepromWriteSequence()
                }, 2000)
            }
            5 -> {
                addDebugLog("Step 5: Writing pack undervoltage release...")
                
                // Calculate and write pack undervoltage release
                val packUndervoltageRelease = (TEMP_CELL_UNDERVOLTAGE_RELEASE * cellCount * 10).toInt() // Convert to deciVolts
                val packUndervoltageReleaseData = byteArrayOf(
                    ((packUndervoltageRelease shr 8) and 0xFF).toByte(),
                    (packUndervoltageRelease and 0xFF).toByte()
                )
                val packUndervoltageReleaseCmd = createWriteCommand(REG_PACK_UNDERVOLTAGE_RELEASE, packUndervoltageReleaseData)
                writeToCharacteristic(packUndervoltageReleaseCmd)
                addDebugLog("Sent: Pack Undervoltage Release = ${packUndervoltageRelease / 10.0}V")
                addDebugLog("Command: ${bytesToHex(packUndervoltageReleaseCmd)}")
                
                // Wait 2 seconds for response
                handler.postDelayed({
                    eepromWriteStep = 6
                    executeEepromWriteSequence()
                }, 2000)
            }
            6 -> {
                addDebugLog("Step 6: Exiting factory mode...")
                
                writeToCharacteristic(CMD_EXIT_FACTORY)
                addDebugLog("Sent: Exit Factory Mode")
                addDebugLog("Command: ${bytesToHex(CMD_EXIT_FACTORY)}")
                
                // Wait 2 seconds before starting countdown to revert settings
                handler.postDelayed({
                    addDebugLog("Initial EEPROM write process completed!")
                    addDebugLog("Starting 30-second countdown to revert settings...")
                    
                    toast("Initial settings written. Reverting in 30 seconds...")
                    
                    // Schedule the revert operation after 30 seconds
                    handler.postDelayed({
                        revertToDefaultSettings()
                    }, 30000) // 30 seconds
                    
                }, 2000)
            }
        }
    }

    private fun revertToDefaultSettings() {
        addDebugLog("Starting revert to default settings...")
        isWritingEEPROM = true
        eepromWriteStep = 101 // Start from step 101 for revert process
        
        handler.post {
            executeRevertSequence()
        }
    }

    private fun executeRevertSequence() {
        when (eepromWriteStep) {
            101 -> {
                addDebugLog("Revert Step 1: Entering factory mode...")
                
                writeToCharacteristic(CMD_ENTER_FACTORY)
                addDebugLog("Sent: Enter Factory Mode for revert")
                
                handler.postDelayed({
                    eepromWriteStep = 102
                    executeRevertSequence()
                }, 2000)
            }
            102 -> {
                addDebugLog("Revert Step 2: Writing default cell undervoltage protection...")
                
                // Write default cell undervoltage protection (2.7V = 2700mV)
                val undervoltageData = byteArrayOf(0x0A.toByte(), 0x8C.toByte()) // 2700 in big-endian
                val undervoltageCmd = createWriteCommand(REG_CELL_UNDERVOLTAGE_PROTECTION, undervoltageData)
                writeToCharacteristic(undervoltageCmd)
                addDebugLog("Sent: Default Cell Undervoltage Protection = 2.7V")
                
                handler.postDelayed({
                    eepromWriteStep = 103
                    executeRevertSequence()
                }, 2000)
            }
            103 -> {
                addDebugLog("Revert Step 3: Writing default cell undervoltage release...")
                
                // Write default cell undervoltage release (2.8V = 2800mV)
                val undervoltageReleaseData = byteArrayOf(0x0A.toByte(), 0xF0.toByte()) // 2800 in big-endian
                val undervoltageReleaseCmd = createWriteCommand(REG_CELL_UNDERVOLTAGE_RELEASE, undervoltageReleaseData)
                writeToCharacteristic(undervoltageReleaseCmd)
                addDebugLog("Sent: Default Cell Undervoltage Release = 2.8V")
                
                handler.postDelayed({
                    eepromWriteStep = 104
                    executeRevertSequence()
                }, 2000)
            }
            104 -> {
                addDebugLog("Revert Step 4: Writing default pack undervoltage protection...")
                
                // Calculate and write default pack undervoltage protection
                val packUndervoltage = (DEFAULT_CELL_UNDERVOLTAGE * cellCount * 10).toInt()
                val packUndervoltageData = byteArrayOf(
                    ((packUndervoltage shr 8) and 0xFF).toByte(),
                    (packUndervoltage and 0xFF).toByte()
                )
                val packUndervoltageCmd = createWriteCommand(REG_PACK_UNDERVOLTAGE_PROTECTION, packUndervoltageData)
                writeToCharacteristic(packUndervoltageCmd)
                addDebugLog("Sent: Default Pack Undervoltage Protection = ${packUndervoltage / 10.0}V")
                
                handler.postDelayed({
                    eepromWriteStep = 105
                    executeRevertSequence()
                }, 2000)
            }
            105 -> {
                addDebugLog("Revert Step 5: Writing default pack undervoltage release...")
                
                // Calculate and write default pack undervoltage release
                val packUndervoltageRelease = (DEFAULT_CELL_UNDERVOLTAGE_RELEASE * cellCount * 10).toInt()
                val packUndervoltageReleaseData = byteArrayOf(
                    ((packUndervoltageRelease shr 8) and 0xFF).toByte(),
                    (packUndervoltageRelease and 0xFF).toByte()
                )
                val packUndervoltageReleaseCmd = createWriteCommand(REG_PACK_UNDERVOLTAGE_RELEASE, packUndervoltageReleaseData)
                writeToCharacteristic(packUndervoltageReleaseCmd)
                addDebugLog("Sent: Default Pack Undervoltage Release = ${packUndervoltageRelease / 10.0}V")
                
                handler.postDelayed({
                    eepromWriteStep = 106
                    executeRevertSequence()
                }, 2000)
            }
            106 -> {
                addDebugLog("Revert Step 6: Exiting factory mode...")
                
                writeToCharacteristic(CMD_EXIT_FACTORY)
                addDebugLog("Sent: Exit Factory Mode after revert")
                
                handler.postDelayed({
                    addDebugLog("Revert to default settings completed!")
                    
                    // Clear EEPROM write flag to resume polling
                    isWritingEEPROM = false
                    eepromWriteStep = 0
                    
                    toast("Settings reverted to default values")
                    
                    // Send basic info command to refresh data after 1 second
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
                    
                }, 2000)
            }
        }
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

    private fun addDebugLog(message: String) {
        debugLog.append("${Date().toString().substring(11, 19)}: $message\n")
        runOnUiThread {
            debugText.text = debugLog.toString()
            // Auto-scroll to bottom
            debugWindow.post {
                debugWindow.fullScroll(View.FOCUS_DOWN)
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
        //tvCellCount.text = "-"
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
        //tvCellCount.text = "-"
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
                addDebugLog("Connected to BMS")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(pollTask)
                chNotify = null
                chWrite = null
                rxBuffer.clear()
                cellCount = 0
                g.close()
                gatt = null
                addDebugLog("Disconnected from BMS")
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
                    addDebugLog("Amitis service discovered")
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
                addDebugLog("Received: ${bytesToHex(data)}")
                
                // Parse response for EEPROM write operations
                if (isWritingEEPROM) {
                    val response = parseResponse(data)
                    if (response != null) {
                        val (success, command, payload) = response
                        if (success) {
                            addDebugLog("ü7╝3 Write successful for command: 0x${command.toString(16).uppercase()}")
                        } else {
                            addDebugLog("ü7├4 Write failed for command: 0x${command.toString(16).uppercase()}, status: 0x${if (payload.isNotEmpty()) payload[0].toString(16).uppercase() else "unknown"}")
                        }
                    }
                }
                
                onAmitisBytes(data)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == AMITIS_WRITE_CH) {
                val data = characteristic.value ?: return
                addDebugLog("Write confirmed: ${bytesToHex(data)} (BLE status=$status)")
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
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

                when (reg) {
                    0x03 -> handleBasicInfo(payload)
                    0x04 -> handleCellInfo(payload) // Handle cell info response
                }
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
        }
    }

    private fun handleCellInfo(p: ByteArray) {
        if (p.size < 3) return
        
        // The cell count is typically at the beginning of cell info response
        val newCellCount = p[0].toInt() and 0xFF
        
        if (newCellCount > 0 && newCellCount <= 24) { // Reasonable cell count range
            cellCount = newCellCount
            addDebugLog("Cell count detected: $cellCount cells")
            
            //runOnUiThread {
//                tvCellCount.text = "$cellCount"
                toast("Detected ${cellCount}S configuration")
            }
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