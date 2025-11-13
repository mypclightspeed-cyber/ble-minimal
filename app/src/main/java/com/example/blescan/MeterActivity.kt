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
    private lateinit var gaugeSOC: ModernHalfGauge
    private lateinit var gaugeTemp: ModernHalfGaugeTemp

    private lateinit var tvVolt: TextView
    private lateinit var tvCurr: TextView
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

        btnScan = Button(this).apply { text = "Start Scan (20s)" }
        list = ListView(this)

        // Create horizontal layout for gauges
        val gaugeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 380
            ).apply { setMargins(16, 10, 16, 6) }
            weightSum = 2f
        }

        // SOC Gauge
        gaugeSOC = ModernHalfGauge(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            ).apply { setMargins(8, 0, 8, 0) }
            setLabel("SOC")
            setPercent(0)
        }

        // Temperature Gauge
        gaugeTemp = ModernHalfGaugeTemp(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            ).apply { setMargins(8, 0, 8, 0) }
            setLabel("Temp")
            setPercent(0)
        }

        gaugeLayout.addView(gaugeSOC)
        gaugeLayout.addView(gaugeTemp)

        fun makeCard(title: String, colorHex: String): Pair<LinearLayout, Pair<TextView, TextView>> {
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
            return card to (titleTv to valueTv)
        }

        val (cardVolt, pairVolt) = makeCard("Voltage (V)", "#10B981")
        val (cardCurr, pairCurr) = makeCard("Current (A)", "#F59E0B")
        val (cardName, pairName) = makeCard("Device",      "#3B82F6")
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
            addView(gaugeLayout)    // Both gauges above parameters
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
            // Use advertiser name directly
            tvName.text = advertisedName[mac] ?: "Unknown"
            connectTo(dev)
        }
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
        gaugeSOC.setPercent(0)
        gaugeTemp.setPercent(0)
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
        // >>> FIX: always refresh scanner instance after Bluetooth may have been toggled
        scanner = bluetoothAdapter?.bluetoothLeScanner
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
            runOnUiThread { toast("State: ${stateName(newState)} (status=$status)") }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(pollTask)
                chNotify = null; chWrite = null; rxBuffer.clear()
                g.close()
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

    // payload: voltage(2) current(2s) ... soc (byte) at offset 19
    private fun handleBasicInfo(p: ByteArray) {
        if (p.size < 24) return
        val vRaw = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val iRawU = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        var iRaw = iRawU
        if ((iRaw and 0x8000) != 0) iRaw = -((iRaw xor 0xFFFF) + 1)
        val voltage = vRaw / 100.0
        val current = iRaw / 100.0
        val soc = p[19].toInt() and 0xFF

        // Temperature extraction per JBD (0x03) with null fallback
        val dataStart = 4
        var tempValue = 0
        if (p.size > dataStart + 22) {
            val ntcCount = p[dataStart + 22].toInt() and 0xFF
            val firstTempIdx = dataStart + 23
            if (ntcCount > 0 && p.size >= firstTempIdx + 2) {
                val tRaw = ((p[firstTempIdx].toInt() and 0xFF) shl 8) or (p[firstTempIdx + 1].toInt() and 0xFF)
                val tempC = (tRaw - 2731) / 10.0
                if (!tempC.isNaN() && tempC > -100 && tempC < 200) {
                    tempValue = tempC.toInt().coerceIn(-5, 95)
                }
            }
        }

        runOnUiThread {
            gaugeSOC.setPercent(soc.coerceIn(0, 100))
            gaugeTemp.setPercent(tempValue)
            tvVolt.text = String.format("%.3f V", voltage)
            tvCurr.text = String.format("%.3f A", current)
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

    // ===== Gauge Style 3 (Modern half-circle): A1 sweep 180°, start at 180°, radius shrink 0.75, red pointer with glowing red shadow, blue SOC text upper-middle =====
    class ModernHalfGauge(context: Context) : View(context) {
        private var pct = 0
        private var label = "SOC"

        // radius shrink factor (B1)
        private val radiusScale = 0.95f

        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5E7EB") // gray-200
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
            color = Color.parseColor("#EF4444") // bright red
            style = Paint.Style.FILL
        }
        // Glowing red shadow paint for pointer
        private val pointerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80EF4444") // semi-transparent red
            style = Paint.Style.FILL
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#FFEF4444")) // Strong red glow
        }
        // SOC text — bigger and blue, drawn upper-middle with extra gap
        private val socPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB") // blue
            textAlign = Paint.Align.LEFT
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB") // blue
            textAlign = Paint.Align.LEFT
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        // Arc labels — large
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

            // A1: sweep 180°, start at left horizon (180°), clockwise
            val startAngle = 180f
            val sweepTotal = 180f

            // track
            c.drawArc(rect, startAngle, sweepTotal, false, track)

            // ticks (bold at 0/50/100, thin each 10%)
            drawTicks(c, rect, startAngle, sweepTotal)

            // progress color based on SOC - using pure solid colors
            val levelColor = when {
                pct < 15 -> Color.RED // Pure red
                pct < 30 -> Color.YELLOW // Pure yellow
                pct <= 80 -> Color.GREEN // Pure green
                else -> Color.BLUE // Pure blue
            }
            
            // Use solid color without gradient
            progress.color = levelColor
            progress.shader = null

            val sweep = sweepTotal * (pct / 100f)
            c.drawArc(rect, startAngle, sweep, false, progress)

            // Enable shadow layer for glowing red effect
            setLayerType(LAYER_TYPE_SOFTWARE, pointerGlow)
            
            // pointer with glowing red shadow
            drawPointer(c, rect, startAngle + sweep)

            // Disable shadow layer after drawing pointer
            setLayerType(LAYER_TYPE_HARDWARE, null)

            // labels at 0/25/50/75/100
            drawLabels(c, rect, startAngle, sweepTotal)

            // SOC text in upper-middle: draw "SOC" and "<pct>%" with extra gap, centered
            val gap = 44f // extra spacing
            val socText = label
            val pctText = "$pct%"
            val socW = socPaint.measureText(socText)
            val pctW = pctPaint.measureText(pctText)
            val totalW = socW + gap + pctW
            val y = rect.centerY() - rect.height()*0.18f  // upper placement
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
            val r = rect.width() / 2f-40f
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
            
            // Draw glowing red shadow (same path, but the shadow layer creates the glow)
            c.drawPath(path, pointerGlow)
            
            // Then draw the bright red pointer on top
            c.drawPath(path, pointer)
            c.drawCircle(cx, cy, 12f, pointer)
        }
    }

    // ===== Temperature Gauge (Modern half-circle): Same as SOC gauge but with temperature range -5 to 95°C =====
    class ModernHalfGaugeTemp(context: Context) : View(context) {
        private var temp = 0
        private var label = "Temp"

        // radius shrink factor (B1)
        private val radiusScale = 0.8f

        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5E7EB") // gray-200
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
            color = Color.parseColor("#EF4444") // bright red
            style = Paint.Style.FILL
        }
        // Glowing red shadow paint for pointer
        private val pointerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80EF4444") // semi-transparent red
            style = Paint.Style.FILL
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#FFEF4444")) // Strong red glow
        }
        // Temp text — bigger and blue, drawn above the arc
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB") // blue
            textAlign = Paint.Align.CENTER
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB") // blue
            textAlign = Paint.Align.CENTER
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        // Arc labels — large
        private val textLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#374151")
            textAlign = Paint.Align.CENTER
            textSize = 28f
        }

        fun setPercent(v: Int) { temp = v.coerceIn(-5, 95); invalidate() }
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

            // A1: sweep 180°, start at left horizon (180°), clockwise
            val startAngle = 180f
            val sweepTotal = 180f

            // track
            c.drawArc(rect, startAngle, sweepTotal, false, track)

            // ticks (bold at -5/50/95, thin each 10°C)
            drawTicks(c, rect, startAngle, sweepTotal)

            // progress color based on temperature - using pure solid colors
            val levelColor = when {
                temp < 0 -> Color.BLUE // Blue for cold
                temp < 15 -> Color.CYAN // Cyan for cool
                temp < 30 -> Color.GREEN // Green for normal
                temp < 50 -> Color.YELLOW // Yellow for warm
                else -> Color.RED // Red for hot
            }
            
            // Use solid color without gradient
            progress.color = levelColor
            progress.shader = null

            // Convert temperature to percentage for gauge display
            val tempPercent = ((temp + 5) / 100.0f) * 100f
            val sweep = sweepTotal * (tempPercent / 100f)
            c.drawArc(rect, startAngle, sweep, false, progress)

            // Enable shadow layer for glowing red effect
            setLayerType(LAYER_TYPE_SOFTWARE, pointerGlow)
            
            // pointer with glowing red shadow
            drawPointer(c, rect, startAngle + sweep)

            // Disable shadow layer after drawing pointer
            setLayerType(LAYER_TYPE_HARDWARE, null)

            // labels at -5/20/45/70/95
            drawLabels(c, rect, startAngle, sweepTotal)

            // Label and value above the arc
            val labelY = rect.top - 60f
            val valueY = rect.top - 20f
            
            c.drawText(label, rect.centerX(), labelY, labelPaint)
            c.drawText("$temp°C", rect.centerX(), valueY, valuePaint)
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
            val r = rect.width() / 2f - 40f
            val marks = listOf(-5, 20, 45, 70, 95)
            
            for (m in marks) {
                // Convert temperature to percentage position
                val position = ((m + 5) / 100.0f)
                val a = Math.toRadians((start + sweep * position).toDouble())
                val x = (cx + r * cos(a)).toFloat()
                val y = (cy + r * sin(a)).toFloat()
                c.drawText("${m}°C", x, y, textLabel)
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
            
            // Draw glowing red shadow (same path, but the shadow layer creates the glow)
            c.drawPath(path, pointerGlow)
            
            // Then draw the bright red pointer on top
            c.drawPath(path, pointer)
            c.drawCircle(cx, cy, 12f, pointer)
        }
    }
}