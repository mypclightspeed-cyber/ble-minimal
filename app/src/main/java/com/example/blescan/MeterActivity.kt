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
import android.os.ParcelUuid
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.roundToInt

class MeterActivity : AppCompatActivity() {

    // --- scan/config ---
    private val SCAN_MS = 20_000L
    private val PERM_REQUEST = 1001

    // --- Amitis BMS (FF00) ---
    private val AMITIS_SERVICE = uuid("0000ff00")
    private val AMITIS_READ_CH = uuid("0000ff01")   // notify
    private val AMITIS_WRITE_CH = uuid("0000ff02")  // write
    private val CMD_BASIC_INFO = hex("DD A5 03 00 FF FD 77")

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

    private lateinit var adapterLv: ArrayAdapter<String>
    private val rows = mutableListOf<String>()                     // "MAC  Name"
    private val devices = LinkedHashMap<String, BluetoothDevice>() // MAC -> device
    private val advertisedName = HashMap<String, String>()         // MAC -> name from scan

    // --- BLE ---
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false

    private val handler = Handler(Looper.getMainLooper())

    private fun uuid(short: String): UUID = UUID.fromString("0000${short}-0000-1000-8000-00805f9b34fb")

    // --- lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- header/logo ---
        val logo = TextView(this).apply {
            text = "Amitis BMS"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(8, 12, 8, 20)
        }

        // --- warning banner ---
        bannerWarn = TextView(this).apply {
            setPadding(20, 14, 20, 14)
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DC2626"))
            visibility = View.GONE
        }

        btnScan = Button(this).apply { text = "Start Scan (20s)" }
        list = ListView(this)

        // Gauge
        gauge = ModernHalfGauge(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(160)
            ).also { it.setMargins(16, 8, 16, 16) }
        }

        // --- helper to build a colored info box without CardView ---
        fun makeCard(title: String, color: String): Pair<LinearLayout, Pair<TextView, TextView>> {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor(color))
                setPadding(24, 18, 24, 18)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(16, 10, 16, 10)
                layoutParams = lp
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
            card.addView(titleTv)
            card.addView(valueTv)
            return card to (titleTv to valueTv)
        }

        val (cardTemp, pairTemp) = makeCard("Temperature (°C)", "#EF4444")
        val (cardVolt, pairVolt) = makeCard("Voltage (V)", "#10B981")
        val (cardCurr, pairCurr) = makeCard("Current (A)", "#F59E0B")
        val (cardName, pairName) = makeCard("Device",      "#3B82F6")
        tvTemp = pairTemp.second
        tvVolt = pairVolt.second
        tvCurr = pairCurr.second
        tvName = pairName.second

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(logo)
            addView(bannerWarn)
            addView(btnScan)
            addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(gauge)          // gauge ABOVE parameters
            addView(cardTemp)       // temperature card ABOVE voltage
            addView(cardVolt)
            addView(cardCurr)
            addView(cardName)
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
            connectTo(mac, dev, advertisedName[mac])
        }

        updateWarningBanner()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun updateWarningBanner() {
        val needLoc = Build.VERSION.SDK_INT < 31
        val locOn = isLocationEnabled()
        val btOn = bluetoothAdapter?.isEnabled == true
        bannerWarn.text = when {
            !btOn -> "Bluetooth is OFF"
            needLoc && !locOn -> "Location needs to be ON for BLE scanning"
            else -> ""
        }
        bannerWarn.visibility = if (bannerWarn.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }
    }

    // --- permissions ---
    private fun checkAndRequestPermissions(): Boolean {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), PERM_REQUEST)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScan()
        } else updateWarningBanner()
    }

    private fun ensurePrereqs(): Boolean {
        var ok = true
        val ad = bluetoothAdapter
        if (ad == null || !ad.isEnabled) {
            ok = false
            toast("Turn ON Bluetooth")
        }
        val needLoc = Build.VERSION.SDK_INT < 31
        val locOn = isLocationEnabled()
        if (needLoc && !locOn) {
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

    // --- scan ---
    private fun startScan() {
        if (scanning) return
        val ad = bluetoothAdapter
        if (ad == null || !ad.isEnabled) { toast("Turn ON Bluetooth"); updateWarningBanner(); return }

        // reset on each new scan
        devices.clear(); rows.clear(); adapterLv.clear(); advertisedName.clear()
        gauge.setPercent(0)
        tvTemp.text = "-"
        tvVolt.text = "-"
        tvCurr.text = "-"
        tvName.text = ""

        scanning = true
        toast("Scanning for ${SCAN_MS/1000}s…")
        handler.postDelayed({
            stopScan()
            toast("Scan done: ${rows.size} device(s) found")
        }, SCAN_MS)

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(AMITIS_SERVICE)).build()
        )
        scanner?.startScan(filters, settings, scanCb)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val mac = dev.address ?: return
            if (!devices.containsKey(mac)) {
                devices[mac] = dev
                val name = result.scanRecord?.deviceName ?: dev.name ?: "(unknown)"
                advertisedName[mac] = name
                val row = "$mac  $name"
                rows += row
                adapterLv.add(row)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }
        override fun onScanFailed(errorCode: Int) { toast("Scan failed: $errorCode") }
    }

    private fun connectTo(mac: String, dev: BluetoothDevice, name: String?) {
        stopScan()
        tvName.text = name ?: mac
        toast("Connecting $mac…")
        dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        private var gatt: BluetoothGatt? = null
        private var readCh: BluetoothGattCharacteristic? = null
        private var writeCh: BluetoothGattCharacteristic? = null

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            this.gatt = gatt
            if (status != BluetoothGatt.GATT_SUCCESS) { toast("Connect failed: $status"); return }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                toast("Discovering services…")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                toast("Disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { toast("Service discovery failed") ; return }
            val svc = gatt.getService(AMITIS_SERVICE)
            readCh = svc?.getCharacteristic(AMITIS_READ_CH)
            writeCh = svc?.getCharacteristic(AMITIS_WRITE_CH)
            if (readCh == null || writeCh == null) { toast("BMS chars not found"); return }

            // enable notifications
            gatt.setCharacteristicNotification(readCh, true)
            val ccc = readCh!!.getDescriptor(uuid("00002902-0000-1000-8000-00805f9b34fb"))
            ccc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (ccc != null) gatt.writeDescriptor(ccc) else toast("CCC not found")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Request basic info once (or periodically)
                writeCh?.value = CMD_BASIC_INFO
                gatt.writeCharacteristic(writeCh)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == AMITIS_READ_CH) {
                val p = characteristic.value ?: return
                handleBasicInfo(p)
            }
        }
    }

    private fun handleBasicInfo(p: ByteArray) {
        if (p.size < 0x1A) return  // ensure we have at least up to temp bytes

        // Example mapping from bytes (based on common BMS frames)
        val vRawU = ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)
        val iRawU = ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)
        var iRaw = iRawU
        if ((iRaw and 0x8000) != 0) iRaw = -((iRaw xor 0xFFFF) + 1)
        val voltage = vRawU / 100.0
        val current = iRaw / 100.0
        val soc = (if (p.size > 19) p[19].toInt() and 0xFF else 0)

        // Temperature extraction (bytes 0x18..0x19), value is in deciKelvin
        val rawTemp = ((p[0x18].toInt() and 0xFF) shl 8) or (p[0x19].toInt() and 0xFF)
        val tempC = (rawTemp / 10.0) - 273.1

        runOnUiThread {
            gauge.setPercent(soc.coerceIn(0, 100))
            tvVolt.text = String.format("%.3f V", voltage)
            tvCurr.text = String.format("%.3f A", current)
            tvTemp.text = String.format("%.1f °C", tempC)
        }
    }

    // --- helpers / utils ---
    private fun hex(s: String): ByteArray =
        s.split(Regex("\\s+")).filter { it.isNotBlank() }
            .map { it.removePrefix("0x").toInt(16).toByte() }.toByteArray()

    private fun toast(s: String) = runOnUiThread {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // --- simple gauge view (half circle) ---
    class ModernHalfGauge(context: Context) : View(context) {
        private var percent = 0
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 24f
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        fun setPercent(p: Int) { percent = p; invalidate() }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (w * 0.6f).toInt())
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width; val h = height
            val pad = 32f
            val rect = RectF(pad, pad, w - pad, h * 2f - pad)
            c.drawArc(rect, 180f, 180f, false, arcPaint)
            c.drawText("$percent%", w / 2f, h - 24f, textPaint)
        }
    }
}
