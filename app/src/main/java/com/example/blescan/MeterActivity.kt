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

    private lateinit var socGauge: ModernHalfGauge
    private lateinit var tempGauge: TempHalfGauge

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
                LinearLayout.LayoutParams.MATCH_PARENT, 96
            ).apply { setMargins(16, 8, 16, 6) }
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

        // ===== Gauges row: side-by-side (SOC 3/4, Temp 1/4) =====
        val rowGauges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                520
            ).apply { setMargins(12, 6, 12, 6) }
        }

        // Left container (3/4) for SOC
        val leftCol = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.75f).apply {
                setMargins(0, 0, 12, 0)
            }
            setBackgroundColor(Color.WHITE)
        }
        socGauge = ModernHalfGauge(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setLabel("SOC")
            setPercent(0)
        }
        leftCol.addView(socGauge)

        // Right container (1/4) for Temp
        val rightCol = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.25f).apply {
                setMargins(12, 0, 0, 0)
            }
            setBackgroundColor(Color.WHITE)
        }
        tempGauge = TempHalfGauge(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
            )
            setTempC(null)
        }
        rightCol.addView(tempGauge)

        rowGauges.addView(leftCol)
        rowGauges.addView(rightCol)

        fun makeCard(title: String, colorHex: String): Pair<LinearLayout, Pair<TextView, TextView>> {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
                setBackgroundColor(Color.parseColor(colorHex))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(16, 8, 16, 8)
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
                textSize = 24f
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
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.WHITE)
            addView(logo)
            addView(bannerWarn)
            addView(btnScan)
            addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.32f))
            addView(rowGauges) // side-by-side gauges
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
        socGauge.setPercent(0)
        tempGauge.setTempC(null)
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
            handler.removeCallbacks(pollTask)
            handler.postDelayed(pollTask, 300)
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

        // Temperature extraction per JBD (0x03) with null fallback
        val dataStart = 4
        var tempCValue: Double? = null
        if (p.size > dataStart + 22) {
            val ntcCount = p[dataStart + 22].toInt() and 0xFF
            val firstTempIdx = dataStart + 23
            if (ntcCount > 0 && p.size >= firstTempIdx + 2) {
                val tRaw = ((p[firstTempIdx].toInt() and 0xFF) shl 8) or (p[firstTempIdx + 1].toInt() and 0xFF)
                val tempC = (tRaw - 2731) / 10.0
                if (!tempC.isNaN() && tempC > -100 && tempC < 200) tempCValue = tempC
            }
        }

        runOnUiThread {
            socGauge.setPercent(soc.coerceIn(0, 100))
            tvVolt.text = String.format("%.3f V", voltage)
            tvCurr.text = String.format("%.3f A", current)
            tempGauge.setTempC(tempCValue)
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

    // ===== SOC Gauge (half-circle) with POINTER =====
    class ModernHalfGauge(context: Context) : View(context) {
        private var pct = 0
        private var label = "SOC"
        // ↓ smaller by 0.8x
        private val radiusScale = 0.84f

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
        // pointer paints (with glow)
        private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EF4444") // red
            style = Paint.Style.FILL
        }
        private val pointerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80EF4444")
            style = Paint.Style.FILL
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#FFEF4444"))
        }

        fun setPercent(v: Int) { pct = v.coerceIn(0, 100); invalidate() }
        fun setLabel(s: String) { label = s; invalidate() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = max((w * 0.55f).roundToInt(), 240)
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

            // ticks
            val cx = rect.centerX()
            val cy = rect.centerY()
            val rOuter = rect.width() / 2f
            val rInnerThin = rOuter - 18f
            val rInnerBold = rOuter - 26f
            for (i in 0..10) {
                val ang = Math.toRadians((startAngle + sweepTotal * (i / 10f)).toDouble())
                val inner = if (i % 5 == 0) rInnerBold else rInnerThin
                val p = if (i % 5 == 0) tickBold else tick
                val sx = (cx + inner * cos(ang)).toFloat()
                val sy = (cy + inner * sin(ang)).toFloat()
                val ex = (cx + rOuter * cos(ang)).toFloat()
                val ey = (cy + rOuter * sin(ang)).toFloat()
                c.drawLine(sx, sy, ex, ey, p)
            }

            // progress color
            val levelColor = when {
                pct < 15 -> Color.RED
                pct < 30 -> Color.YELLOW
                pct <= 80 -> Color.GREEN
                else -> Color.BLUE
            }
            progress.color = levelColor
            val sweep = sweepTotal * (pct / 100f)
            c.drawArc(rect, startAngle, sweep, false, progress)

            // pointer with glow at end angle
            setLayerType(LAYER_TYPE_SOFTWARE, pointerGlow)
            val angle = startAngle + sweep
            drawPointer(c, rect, angle, pointerGlow)
            drawPointer(c, rect, angle, pointer)
            setLayerType(LAYER_TYPE_HARDWARE, null)

            // centered "SOC   NN%"
            val gap = 44f
            val socText = label
            val pctText = "$pct%"
            val socW = socPaint.measureText(socText)
            val pctW = pctPaint.measureText(pctText)
            val totalW = socW + gap + pctW
            val textY = rect.centerY() - rect.height()*0.18f
            val startX = (w - totalW) / 2f
            val fm = socPaint.fontMetrics
            val baseline = textY - (fm.ascent + fm.descent)/2f
            c.drawText(socText, startX, baseline, socPaint)
            c.drawText(pctText, startX + socW + gap, baseline, pctPaint)
        }

        private fun drawPointer(c: Canvas, rect: RectF, angleDeg: Float, paint: Paint) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val r = rect.width() / 2.15f
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
            val path = Path().apply {
                moveTo(tipX, tipY); lineTo(b1x, b1y); lineTo(b2x, b2y); close()
            }
            c.drawPath(path, paint)
            c.drawCircle(cx, cy, 12f, paint)
        }
    }

    // ===== Temperature Gauge (half-circle, scaled up 2x; shifted up & left; gray arc + black markers + C/N/H + blue/red end zones) =====
    class TempHalfGauge(context: Context) : View(context) {
        private var tempC: Double? = null
        private val minC = -20.0
        private val maxC = 120.0
        // ↑ 2x of previous 0.82f → 1.64f
        private val radiusScale = 1.64f

        // small manual offsets to move the gauge UP and LEFT without crossing the SOC curve
        // (tune these if you need different placement)
        private val offsetXFrac = -0.06f  // negative = move left (as % of size)
        private val offsetYFrac = -0.10f  // negative = move up   (as % of size)

        private val grayArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5E7EB")
            style = Paint.Style.STROKE
            strokeWidth = 28f
            strokeCap = Paint.Cap.ROUND
        }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 7f
        }
        private val tickBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 9f
        }
        private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        private val markBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB")
            style = Paint.Style.STROKE
            strokeWidth = 30f
            strokeCap = Paint.Cap.BUTT
        }
        private val markRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DC2626")
            style = Paint.Style.STROKE
            strokeWidth = 30f
            strokeCap = Paint.Cap.BUTT
        }
        private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB")
            style = Paint.Style.STROKE
            strokeWidth = 9f
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }

        fun setTempC(value: Double?) { tempC = value; invalidate() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = max((w * 0.55f).roundToInt(), 180)
            setMeasuredDimension(w, h)
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val pad = 26f
            val w = width.toFloat()
            val h = height.toFloat()
            val baseSize = min(w - pad * 2, h * 2.0f - pad * 2)
            val size = baseSize * radiusScale

            val rect = RectF(
                (w - size) / 2f, pad + (baseSize - size) / 2f,
                (w + size) / 2f, pad + (baseSize - size) / 2f + size
            )
            // shift up & left
            rect.offset(size * offsetXFrac, size * offsetYFrac)

            val startAngle = 180f
            val sweepTotal = 180f

            // Gray arc with tiny colored ends
            c.drawArc(rect, startAngle, sweepTotal, false, grayArc)
            val z = 22f
            c.drawArc(rect, startAngle, z, false, markBlue)
            c.drawArc(rect, startAngle + sweepTotal - z, z, false, markRed)

            // ticks
            for (i in 0..18) {
                val v = i * 10f
                val angle = startAngle + v
                val a = Math.toRadians(angle.toDouble())
                val cx = rect.centerX()
                val cy = rect.centerY()
                val rOuter = rect.width() / 2f
                val rInner = rOuter - if (i % 5 == 0) 24f else 14f
                val p = if (i % 5 == 0) tickBold else tick
                val sx = (cx + rInner * cos(a)).toFloat()
                val sy = (cy + rInner * sin(a)).toFloat()
                val ex = (cx + rOuter * cos(a)).toFloat()
                val ey = (cy + rOuter * sin(a)).toFloat()
                c.drawLine(sx, sy, ex, ey, p)
            }

            // pointer
            val value = tempC ?: minC
            drawPointer(c, rect, valueToAngle(value, startAngle, sweepTotal))

            // thermometer + waves
            drawThermoSymbolWithBlueWaves(c, rect)

            // labels C N H
            drawCNHLabels(c, rect, startAngle, sweepTotal)
        }

        private fun valueToAngle(v: Double, start: Float, sweep: Float): Float {
            val clamped = v.coerceIn(minC, maxC)
            val frac = ((clamped - minC) / (maxC - minC)).toFloat()
            return start + sweep * frac
        }

        private fun drawPointer(c: Canvas, rect: RectF, angleDeg: Float) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val r = rect.width() / 1.9f
            val a = Math.toRadians(angleDeg.toDouble())
            val tipX = (cx + r * cos(a)).toFloat()
            val tipY = (cy + r * sin(a)).toFloat()
            val baseW = 12f
            val back = 54f
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
            c.drawPath(path, pointer)
            c.drawCircle(cx, cy, 9f, pointer)
        }

        private fun drawThermoSymbolWithBlueWaves(c: Canvas, rect: RectF) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val stemTop = cy - rect.height()*0.14f
            val stemBottom = cy - rect.height()*0.02f
            val stemX = cx
            c.drawLine(stemX, stemTop, stemX, stemBottom, symbolPaint)
            c.drawCircle(stemX, stemBottom + 12f, 12f, symbolPaint)

            // shorter & lower ticks on right
            val tickLen = rect.width()*0.10f
            val yTop = stemTop + (stemBottom - stemTop) * 0.40f
            val gap = (stemBottom - yTop)/3f
            for (i in 0..2) {
                val y = yTop + i*gap
                c.drawLine(stemX, y, stemX + tickLen, y, symbolPaint)
            }

            // waves
            val waveLen = rect.width() * 0.42f
            val startX = cx - waveLen / 2f
            val baseY = stemBottom + 24f
            val amp = 7f
            repeat(2) { i ->
                val y = baseY + i * 12f
                val path = Path().apply {
                    moveTo(startX, y)
                    rQuadTo(waveLen / 4f, -amp, waveLen / 2f, 0f)
                    rQuadTo(waveLen / 4f,  amp, waveLen / 2f, 0f)
                }
                c.drawPath(path, wavePaint)
            }
        }

        private fun drawCNHLabels(c: Canvas, rect: RectF, start: Float, sweep: Float) {
            val r = rect.width()/2f + 20f
            val cx = rect.centerX()
            val cy = rect.centerY()
            fun drawAt(deg: Float, t: String) {
                val a = Math.toRadians(deg.toDouble())
                val x = (cx + r * cos(a)).toFloat()
                val y = (cy + r * sin(a)).toFloat() - 20f
                c.drawText(t, x, y, labelPaint)
            }
            drawAt(180f, "C")
            drawAt(270f, "N")
            drawAt(360f, "H")
        }
    }
}

// --- helpers (file bottom) ---
private fun hex(s: String): ByteArray =
    s.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
private fun uuid(short: String) = UUID.fromString("$short-0000-1000-8000-00805f9b34fb")