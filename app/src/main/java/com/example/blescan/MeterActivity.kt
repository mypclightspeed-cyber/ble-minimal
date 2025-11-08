package com.example.blescan

import android.Manifest
import android.bluetooth.*
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

/**
 * MeterActivity: NO scanning here. It only connects to the device provided by ScanActivity via intent extras:
 *   - "mac"  (String) device address
 *   - "name" (String) display name
 */
class MeterActivity : AppCompatActivity() {

    // --- Amitis BLE UUIDs ---
    private val AMITIS_SERVICE = uuid("0000ff00")
    private val AMITIS_READ_CH = uuid("0000ff01")
    private val AMITIS_WRITE_CH = uuid("0000ff02")
    private val CMD_BASIC_INFO = hex("DD A5 03 00 FF FD 77")

    // --- UI --- (no scan/list elements)
    private lateinit var bannerWarn: TextView
    private lateinit var btnBack: Button
    private lateinit var tvDevice: TextView
    private lateinit var tvVolt: TextView
    private lateinit var tvCurr: TextView
    private lateinit var tvTemp: TextView
    private lateinit var gaugeSOC: ModernHalfGauge
    private lateinit var miniVolt: MiniGauge
    private lateinit var miniCurr: MiniGauge

    // --- BLE state ---
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var chNotify: BluetoothGattCharacteristic? = null
    private var chWrite: BluetoothGattCharacteristic? = null
    private val rxBuffer = ArrayList<Byte>()
    private val handler = Handler(Looper.getMainLooper())

    private val REQ_CONNECT = 2101
    private var pendingMac: String? = null

    private val pollIntervalMs = 1000L
    private val pollTask = object : Runnable {
        override fun run() {
            chWrite?.let { w ->
                gatt?.let { g ->
                    try {
                        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        w.value = CMD_BASIC_INFO
                        g.writeCharacteristic(w)
                    } catch (_: SecurityException) {}
                }
            }
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Build compact UI programmatically ---
        bannerWarn = TextView(this).apply {
            setPadding(20,14,20,14); textSize = 14f
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#DC2626"))
            visibility = View.GONE
        }
        btnBack = Button(this).apply { text = "← Back to Scan" }
        tvDevice = TextView(this).apply { textSize = 16f; setTextColor(Color.BLACK) }

        gaugeSOC = ModernHalfGauge(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 360
            ).apply { setMargins(16, 8, 16, 8) }
            setLabel("SOC"); setPercent(0)
        }
        miniVolt = MiniGauge(this).apply { setTitle("V"); setUnit("V"); setRange(0.0, 60.0) }
        miniCurr = MiniGauge(this).apply { setTitle("A"); setUnit("A"); setRange(-200.0, 200.0) }

        fun makeCard(title: String, color: String): Pair<LinearLayout, TextView> {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
                setBackgroundColor(Color.parseColor(color))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(16, 8, 16, 8); layoutParams = lp; elevation = 6f
            }
            val titleTv = TextView(this).apply {
                text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            }
            val valueTv = TextView(this).apply { text = "-"; textSize = 26f; setTextColor(Color.WHITE) }
            card.addView(titleTv); card.addView(valueTv)
            return card to valueTv
        }

        val (cardVolt, tvV) = makeCard("Voltage (V)", "#10B981"); tvVolt = tvV
        val (cardCurr, tvC) = makeCard("Current (A)", "#F59E0B"); tvCurr = tvC
        val (cardTemp, tvT) = makeCard("Temperature (°C)", "#8B5CF6"); tvTemp = tvT
        val (cardDev, tvD)  = makeCard("Device", "#3B82F6"); tvDevice = tvD

        val miniRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 200)
            lp.setMargins(8,8,8,0); layoutParams = lp
            addView(miniVolt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(8,8,8,8) })
            addView(miniCurr, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(8,8,8,8) })
        }

        val root = ScrollView(this).apply {
            addView(LinearLayout(this@MeterActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12,12,12,12)
                addView(bannerWarn)
                addView(btnBack)
                addView(tvDevice)
                addView(miniRow)
                addView(gaugeSOC)
                addView(cardVolt)
                addView(cardCurr)
                addView(cardTemp)
                addView(cardDev)
            })
        }
        setContentView(root)
        btnBack.setOnClickListener { finish() }

        // --- DIRECT CONNECT MODE ---
        val mac = intent.getStringExtra("mac")
        val name = intent.getStringExtra("name") ?: "Unknown"
        tvDevice.text = name
        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        if (mac.isNullOrBlank()) {
            Toast.makeText(this, "No device MAC provided", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        if (!hasConnectPermission()) {
            pendingMac = mac
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_CONNECT)
            return
        }
        connectTo(bluetoothAdapter!!.getRemoteDevice(mac))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CONNECT) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                val mac = pendingMac; pendingMac = null
                if (!mac.isNullOrBlank()) {
                    try { connectTo(bluetoothAdapter!!.getRemoteDevice(mac)) }
                    catch (e: Exception) { Toast.makeText(this, "Connect failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            } else {
                Toast.makeText(this, "BLUETOOTH_CONNECT permission is required to connect.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollTask); gatt?.close(); super.onDestroy()
    }

    // ---- BLE connect/notify ----
    private fun connectTo(device: BluetoothDevice) {
        if (!ensurePrereqs()) return
        gatt?.close()
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(this, false, gattCb)
        } catch (_: SecurityException) {
            Toast.makeText(this, "Missing BLUETOOTH_CONNECT permission", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Connect error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensurePrereqs(): Boolean {
        var ok = true
        val btOn = bluetoothAdapter?.isEnabled == true
        val locOn = isLocationEnabled(this)
        if (!btOn) {
            ok = false
            AlertDialog.Builder(this)
                .setTitle("Bluetooth is OFF").setMessage("Turn on Bluetooth to connect.")
                .setPositiveButton("Open Bluetooth Settings") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Cancel", null).show()
        }
        if (!locOn) {
            ok = false
            AlertDialog.Builder(this)
                .setTitle("Location is OFF").setMessage("Location must be ON for BLE on many Android versions.")
                .setPositiveButton("Open Location Settings") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Cancel", null).show()
        }
        bannerWarn.visibility = if (!btOn || !locOn) View.VISIBLE else View.GONE
        bannerWarn.text = when {
            !btOn && !locOn -> "Bluetooth and Location are OFF"
            !btOn -> "Bluetooth is OFF"
            !locOn -> "Location is OFF"
            else -> ""
        }
        return ok
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(pollTask); chNotify = null; chWrite = null; rxBuffer.clear(); g.close()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            try {
                val svc = g.getService(AMITIS_SERVICE)
                if (svc == null) {
                    runOnUiThread { Toast.makeText(this@MeterActivity, "Amitis service not found", Toast.LENGTH_SHORT).show() }
                    return
                }
                chNotify = svc.getCharacteristic(AMITIS_READ_CH)
                chWrite  = svc.getCharacteristic(AMITIS_WRITE_CH)
                if (chNotify == null || chWrite == null) {
                    runOnUiThread { Toast.makeText(this@MeterActivity, "Amitis characteristics missing", Toast.LENGTH_SHORT).show() }
                    return
                }
                g.setCharacteristicNotification(chNotify, true)
                val cccd = chNotify!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
                handler.removeCallbacks(pollTask); handler.postDelayed(pollTask, 300)
            } catch (_: SecurityException) {
                runOnUiThread { Toast.makeText(this@MeterActivity, "Missing BLUETOOTH_CONNECT permission", Toast.LENGTH_LONG).show() }
            }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == AMITIS_READ_CH) onAmitisBytes(ch.value ?: return)
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

                val cmd = (rxBuffer[1].toInt() and 0xFF)
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

                if (cmd == 0x03) handleBasicInfo(payload)
            }
        }
    }

    private fun handleBasicInfo(p: ByteArray) {
        if (p.size < 29) return
        val vRaw = ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)
        val iU = ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)
        var iS = iU; if ((iS and 0x8000) != 0) iS = -((iS xor 0xFFFF) + 1)
        val voltage = vRaw / 100.0; val current = iS / 100.0
        val soc = (p[23].toInt() and 0xFF).coerceIn(0,100)

        var tempC: Double? = null
        if (p.size > 28) {
            val ntcCount = p[26].toInt() and 0xFF
            if (ntcCount > 0) {
                val rawT = ((p[27].toInt() and 0xFF) shl 8) or (p[28].toInt() and 0xFF)
                tempC = (rawT - 2731.5) / 10.0
            }
        }

        runOnUiThread {
            gaugeSOC.setPercent(soc)
            tvVolt.text = String.format("%.3f V", voltage)
            tvCurr.text = String.format("%.3f A", current)
            tvTemp.text = if (tempC != null) String.format("%.1f °C", tempC) else "-"
            miniVolt.setValue(voltage)
            miniCurr.setValue(current)
        }
    }

    // ---- Utils ----
    private fun hex(s: String): ByteArray =
        s.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun uuid(short: String) = UUID.fromString("$short-0000-1000-8000-00805f9b34fb")
    private fun isLocationEnabled(ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { false }
    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else true

    // ==== Gauges ====
    class ModernHalfGauge(context: Context) : View(context) {
        private var pct = 0
        private var label = "SOC"
        private val radiusScale = 0.75f

        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5E7EB")
            style = Paint.Style.STROKE; strokeWidth = 30f; strokeCap = Paint.Cap.ROUND
        }
        private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 30f; strokeCap = Paint.Cap.ROUND
        }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9CA3AF"); style = Paint.Style.STROKE; strokeWidth = 4f
        }
        private val tickBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6B7280"); style = Paint.Style.STROKE; strokeWidth = 6f
        }
        private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EF4444"); style = Paint.Style.FILL
        }
        private val socPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB"); textAlign = Paint.Align.LEFT; textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB"); textAlign = Paint.Align.LEFT; textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        private val textLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#374151"); textAlign = Paint.Align.CENTER; textSize = 32f
        }

        fun setPercent(v: Int) { pct = v.coerceIn(0, 100); invalidate() }
        fun setLabel(s: String) { label = s; invalidate() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = kotlin.math.max((w * 0.55f).roundToInt(), 240)
            setMeasuredDimension(w, h)
        }
        override fun onDraw(c: Canvas) {
            val pad = 36f
            val w = width.toFloat(); val h = height.toFloat()
            val baseSize = kotlin.math.min(w - pad * 2, h * 2.0f - pad * 2)
            val size = baseSize * radiusScale
            val rect = RectF((w - size)/2f, pad + (baseSize - size)/2f, (w + size)/2f, pad + (baseSize - size)/2f + size)

            val startAngle = 180f; val sweepTotal = 180f

            c.drawArc(rect, startAngle, sweepTotal, false, track)
            drawTicks(c, rect, startAngle, sweepTotal)
            val levelColor = when { pct >= 80 -> Color.parseColor("#22C55E"); pct >= 30 -> Color.parseColor("#F59E0B"); else -> Color.parseColor("#EF4444") }
            progress.color = levelColor
            val sweep = sweepTotal * (pct / 100f); c.drawArc(rect, startAngle, sweep, false, progress)
            drawPointer(c, rect, startAngle + sweep)
            drawLabels(c, rect, startAngle, sweepTotal)

            val gap = 44f
            val socText = label; val pctText = "$pct%"
            val socW = socPaint.measureText(socText); val pctW = pctPaint.measureText(pctText)
            val totalW = socW + gap + pctW
            val y = rect.centerY() - rect.height()*0.18f
            val startX = (w - totalW)/2f
            val fm = socPaint.fontMetrics; val baseline = y - (fm.ascent + fm.descent)/2f
            c.drawText(socText, startX, baseline, socPaint); c.drawText(pctText, startX + socW + gap, baseline, pctPaint)
        }

        private fun drawTicks(c: Canvas, rect: RectF, start: Float, sweep: Float) {
            val cx = rect.centerX(); val cy = rect.centerY()
            val rOuter = rect.width()/2f
            val rThin = rOuter - 18f; val rBold = rOuter - 26f; val rMid = rOuter - 22f
            for (i in 0..10) {
                val ang = Math.toRadians((start + sweep * (i / 10f)).toDouble())
                val p = when (i) { 0,5,10 -> tickBold; 2,8 -> tickBold; else -> tick }
                val inner = when (i) { 0,5,10 -> rBold; 2,8 -> rMid; else -> rThin }
                val sx = (cx + inner * cos(ang)).toFloat(); val sy = (cy + inner * sin(ang)).toFloat()
                val ex = (cx + rOuter * cos(ang)).toFloat(); val ey = (cy + rOuter * sin(ang)).toFloat()
                c.drawLine(sx, sy, ex, ey, p)
            }
        }
        private fun drawLabels(c: Canvas, rect: RectF, start: Float, sweep: Float) {
            val cx = rect.centerX(); val cy = rect.centerY()
            val r = rect.width()/2f + 24f
            val marks = listOf(0, 25, 50, 75, 100)
            for (m in marks) {
                val a = Math.toRadians((start + sweep * (m / 100f)).toDouble())
                val x = (cx + r * cos(a)).toFloat(); val y = (cy + r * sin(a)).toFloat()
                c.drawText("${m}%", x, y, textLabel)
            }
        }
        private fun drawPointer(c: Canvas, rect: RectF, angleDeg: Float) {
            val cx = rect.centerX(); val cy = rect.centerY()
            val r = rect.width()/2.25f; val a = Math.toRadians(angleDeg.toDouble())
            val tipX = (cx + r * cos(a)).toFloat(); val tipY = (cy + r * sin(a)).toFloat()
            val baseW = 16f; val back = 42f; val perp = a + Math.PI/2
            val b1x = (cx - back * cos(a) + baseW * cos(perp)).toFloat()
            val b1y = (cy - back * sin(a) + baseW * sin(perp)).toFloat()
            val b2x = (cx - back * cos(a) - baseW * cos(perp)).toFloat()
            val b2y = (cy - back * sin(a) - baseW * sin(perp)).toFloat()
            val path = Path(); path.moveTo(tipX, tipY); path.lineTo(b1x, b1y); path.lineTo(b2x, b2y); path.close()
            c.drawPath(path, pointer); c.drawCircle(cx, cy, 12f, pointer)
        }
    }

    class MiniGauge(context: Context) : FrameLayout(context) {
        private var title = ""
        private var unit = ""
        private var min = 0.0
        private var max = 100.0
        private var value = 0.0

        private val titleTv = TextView(context).apply {
            textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#374151"))
        }
        private val canvasView = object : View(context) {
            private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E5E7EB"); style = Paint.Style.STROKE; strokeWidth = 18f; strokeCap = Paint.Cap.ROUND
            }
            private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 18f; strokeCap = Paint.Cap.ROUND
            }
            private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#111827"); textAlign = Paint.Align.CENTER; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            }
            override fun onDraw(c: Canvas) {
                val pad = 16f
                val w = width.toFloat(); val h = height.toFloat()
                val size = kotlin.math.min(w, h) - pad*2
                val rect = RectF((w - size)/2f, pad, (w + size)/2f, pad + size)
                val start = 180f; val sweep = 180f
                c.drawArc(rect, start, sweep, false, track)
                val t = ((value - min)/(max - min)).coerceIn(0.0,1.0).toFloat()
                val levelColor = if (title == "A" && value < 0) Color.parseColor("#3B82F6") else Color.parseColor("#10B981")
                progress.color = levelColor
                c.drawArc(rect, start, sweep * t, false, progress)
                c.drawText(String.format("%.2f %s", value, unit), w/2f, rect.centerY()+12f, text)
            }
        }

        init {
            setPadding(8,8,8,8)
            addView(titleTv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { setMargins(8,0,0,0) })
            addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun setTitle(t: String) { title = t; titleTv.text = t }
        fun setUnit(u: String) { unit = u }
        fun setRange(mi: Double, ma: Double) { min = mi; max = ma; canvasView.invalidate() }
        fun setValue(v: Double) { value = v; canvasView.invalidate() }
    }
}
