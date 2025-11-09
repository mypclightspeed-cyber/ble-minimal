package com.example.blescan

import android.Manifest
import android.bluetooth.*
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.math.*

/**
 * MeterActivity – main SOC half gauge + two mini half gauges (Voltage left, Current right).
 * Style: M1 (all gauges with a red needle pointer). Adds a temperature text box above device box.
 * Expects "mac" and "name" in intent extras from ScanActivity.
 * Temperature decoded as: ((payload[8] << 8) | payload[9]) / 10.0
 */
class MeterActivity : AppCompatActivity() {

    // ---- UUID helpers ----
    private fun uuid16(short: String) = UUID.fromString("${short}-0000-1000-8000-00805f9b34fb")

    // ---- JBD/Amitis UUIDs ----
    private val AMITIS_SERVICE = uuid16("0000ff00")
    private val AMITIS_READ_CH = uuid16("0000ff01")
    private val AMITIS_WRITE_CH = uuid16("0000ff02")
    private val CMD_BASIC_INFO = hex("DD A5 03 00 FF FD 77")

    // ---- UI refs ----
    private lateinit var tvTemp: TextView       // NEW: temperature box
    private lateinit var tvDevice: TextView
    private lateinit var socGauge: ModernHalfGauge
    private lateinit var vGauge: MiniNeedleGauge
    private lateinit var aGauge: MiniNeedleGauge

    // ---- BLE ----
    private var gatt: BluetoothGatt? = null
    private var chNotify: BluetoothGattCharacteristic? = null
    private var chWrite: BluetoothGattCharacteristic? = null
    private val rx = ArrayList<Byte>()
    private val handler = Handler(Looper.getMainLooper())

    private val REQ_CONNECT = 2101
    private var pendingMac: String? = null

    private val pollMs = 1000L
    private val pollTask = object : Runnable {
        override fun run() {
            val g = gatt
            val w = chWrite
            if (g != null && w != null) {
                try {
                    w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    w.value = CMD_BASIC_INFO
                    g.writeCharacteristic(w)
                } catch (_: SecurityException) { /* permission missing */ }
            }
            handler.postDelayed(this, pollMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---------- Layout (programmatic) ----------
        val title = TextView(this).apply {
            text = "Amitis BMS"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        }

        // NEW: temperature text box (placed above device box)
        tvTemp = TextView(this).apply {
            text = "Temp: -"
            textSize = 16f
            setTextColor(Color.parseColor("#374151"))
        }

        tvDevice = TextView(this).apply {
            text = "Device: -"
            textSize = 16f
            setTextColor(Color.parseColor("#374151"))
        }

        // Mini gauges (left: V, right: A) – needle style
        vGauge = MiniNeedleGauge(this).apply {
            setTitle("V"); setUnit("V"); setRange(0.0, 60.0)
        }
        aGauge = MiniNeedleGauge(this).apply {
            setTitle("A"); setUnit("A"); setRange(-200.0, 200.0)
        }

        // Main SOC half gauge
        socGauge = ModernHalfGauge(this).apply {
            setLabel("SOC")
            setPercent(0)
        }

        val threeCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 10f
            addView(vGauge, LinearLayout.LayoutParams(0, 220, 3f).apply { setMargins(8, 8, 8, 8) })
            addView(socGauge, LinearLayout.LayoutParams(0, 320, 4f).apply { setMargins(8, 8, 8, 8) })
            addView(aGauge, LinearLayout.LayoutParams(0, 220, 3f).apply { setMargins(8, 8, 8, 8) })
        }

        val root = ScrollView(this).apply {
            addView(LinearLayout(this@MeterActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
                addView(title)
                addView(tvTemp)    // NEW: add temp box above device
                addView(tvDevice)
                addView(threeCol)
            })
        }
        setContentView(root)

        // ---------- Connect using extras ----------
        val mac  = intent.getStringExtra("mac")
        val name = intent.getStringExtra("name") ?: "Unknown"
        tvDevice.text = "Device: $name ($mac)"
        if (mac.isNullOrBlank()) { toast("No device MAC provided"); finish(); return }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            pendingMac = mac
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_CONNECT)
        } else {
            connect(mac)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CONNECT) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                pendingMac?.let { connect(it) }; pendingMac = null
            } else {
                toast("BLUETOOTH_CONNECT permission is required"); finish()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollTask)
        gatt?.close(); gatt = null
        super.onDestroy()
    }

    // ---------- BLE ----------
    private fun connect(mac: String) {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) { toast("Bluetooth unavailable"); finish(); return }
        try {
            val device = adapter.getRemoteDevice(mac)
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(this, false, gattCb)
        } catch (e: Exception) { toast("Connect error: ${e.message}"); finish() }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { g.discoverServices() }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(pollTask); chNotify = null; chWrite = null; rx.clear(); g.close()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(AMITIS_SERVICE) ?: run { toast("Amitis service not found"); return }
            chNotify = svc.getCharacteristic(AMITIS_READ_CH)
            chWrite  = svc.getCharacteristic(AMITIS_WRITE_CH)
            if (chNotify == null || chWrite == null) { toast("Characteristics missing"); return }
            try {
                g.setCharacteristicNotification(chNotify, true)
                val cccd = chNotify!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            } catch (_: SecurityException) { toast("Missing BLUETOOTH_CONNECT"); return }
            handler.removeCallbacks(pollTask); handler.postDelayed(pollTask, 300)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == AMITIS_READ_CH) onAmitisBytes(ch.value ?: return)
        }
    }

    // ---------- Parse JBD/Amitis "basic info" ----------
    private fun onAmitisBytes(chunk: ByteArray) {
        synchronized(rx) {
            chunk.forEach { rx.add(it) }
            while (true) {
                val start = rx.indexOfFirst { it == 0xDD.toByte() }
                if (start < 0) { rx.clear(); return }
                if (start > 0) repeat(start) { rx.removeAt(0) }
                if (rx.size < 7) return

                val cmd = (rx[1].toInt() and 0xFF)
                val status = (rx[2].toInt() and 0xFF)
                val length = (rx[3].toInt() and 0xFF)
                val total = 1 + 1 + 1 + 1 + length + 2 + 1
                if (rx.size < total) return

                val frame = ByteArray(total) { i -> rx[i] }
                repeat(total) { rx.removeAt(0) }
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
        val voltage = vRaw / 100.0
        val current = iS / 100.0
        val soc = (p[23].toInt() and 0xFF).coerceIn(0, 100)

        // NEW: temperature from payload[8..9] / 10.0
        val tRaw = ((p[8].toInt() and 0xFF) shl 8) or (p[9].toInt() and 0xFF)
        val temperature = tRaw / 10.0

        runOnUiThread {
            socGauge.setPercent(soc)
            vGauge.setValue(voltage)
            aGauge.setValue(current)
            tvTemp.text = String.format("Temp: %.1f °C", temperature)   // NEW: show temperature
        }
    }

    // ---- Utils ----
    private fun hex(s: String): ByteArray =
        s.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    // ---------- Custom Views ----------
    /** Main center SOC half gauge (with pointer) */
    class ModernHalfGauge(context: Context) : View(context) {
        private var pct = 0
        private var label = "SOC"
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5E7EB")
            style = Paint.Style.STROKE
            strokeWidth = 28f
            strokeCap = Paint.Cap.ROUND
        }
        private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 28f
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
        private val socPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB"); textAlign = Paint.Align.LEFT; textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB"); textAlign = Paint.Align.LEFT; textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        private val textLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#374151"); textAlign = Paint.Align.CENTER; textSize = 26f
        }

        fun setPercent(v: Int) { pct = v.coerceIn(0,100); invalidate() }
        fun setLabel(s: String) { label = s; invalidate() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = max((w * 0.44f).roundToInt(), 240)
            setMeasuredDimension(w, h)
        }

        override fun onDraw(c: Canvas) {
            val pad = 24f
            val w = width.toFloat(); val h = height.toFloat()
            val size = min(w - pad*2, h*2 - pad*2)
            val rect = RectF((w - size)/2f, pad, (w + size)/2f, pad + size)
            val start = 180f; val total = 180f

            // Track + progress
            c.drawArc(rect, start, total, false, track)
            val col = when { pct >= 80 -> Color.parseColor("#22C55E"); pct >= 30 -> Color.parseColor("#F59E0B"); else -> Color.parseColor("#EF4444") }
            progress.color = col
            val sweep = total * (pct / 100f)
            c.drawArc(rect, start, sweep, false, progress)

            // Ticks (0,25,50,75,100) – bold all five
            val marksBold = setOf(0,25,50,75,100)
            for (i in 0..10) {
                val m = i*10
                val a = Math.toRadians((start + total * (m/100f)).toDouble())
                val rOut = rect.width()/2f
                val rIn = rOut - (if (m in marksBold) 26f else 18f)
                val paint = if (m in marksBold) tickBold else tick
                val cx = rect.centerX(); val cy = rect.centerY()
                val sx = (cx + rIn  * cos(a)).toFloat(); val sy = (cy + rIn  * sin(a)).toFloat()
                val ex = (cx + rOut * cos(a)).toFloat(); val ey = (cy + rOut * sin(a)).toFloat()
                c.drawLine(sx, sy, ex, ey, paint)
            }
            // Pointer
            val angle = start + sweep
            val cx = rect.centerX(); val cy = rect.centerY()
            val r = rect.width()/2.25f; val ang = Math.toRadians(angle.toDouble())
            val tipX = (cx + r * cos(ang)).toFloat(); val tipY = (cy + r * sin(ang)).toFloat()
            val baseW = 16f; val back = 42f; val perp = ang + Math.PI/2
            val b1x = (cx - back * cos(ang) + baseW * cos(perp)).toFloat()
            val b1y = (cy - back * sin(ang) + baseW * sin(perp)).toFloat()
            val b2x = (cx - back * cos(ang) - baseW * cos(perp)).toFloat()
            val b2y = (cy - back * sin(ang) - baseW * sin(perp)).toFloat()
            val path = Path(); path.moveTo(tipX, tipY); path.lineTo(b1x, b1y); path.lineTo(b2x, b2y); path.close()
            c.drawPath(path, pointer); c.drawCircle(cx, cy, 12f, pointer)

            // Labels (0,25,50,75,100)
            val marks = listOf(0,25,50,75,100)
            for (m in marks) {
                val a = Math.toRadians((start + total * (m/100f)).toDouble())
                val rr = rect.width()/2f + 20f
                val x = (cx + rr * cos(a)).toFloat(); val y = (cy + rr * sin(a)).toFloat()
                c.drawText("${m}%", x, y, textLabel)
            }

            // Center texts
            val gap = 40f
            val socT = label; val pctT = "$pct%"
            val socW = socPaint.measureText(socT); val pctW = pctPaint.measureText(pctT)
            val totalW = socW + gap + pctW
            val y0 = rect.centerY() - rect.height()*0.18f
            val fm = socPaint.fontMetrics; val baseline = y0 - (fm.ascent + fm.descent)/2f
            val startX = (w - totalW)/2f
            c.drawText(socT, startX, baseline, socPaint)
            c.drawText(pctT, startX + socW + gap, baseline, pctPaint)
        }
    }

    /** Mini half gauge with needle (used for V and A) */
    class MiniNeedleGauge(context: Context) : View(context) {
        private var title = ""
        private var unit = ""
        private var min = 0.0
        private var max = 100.0
        private var value = 0.0

        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5E7EB"); style = Paint.Style.STROKE; strokeWidth = 16f; strokeCap = Paint.Cap.ROUND
        }
        private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 16f; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#10B981")
        }
        private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EF4444"); style = Paint.Style.FILL
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#374151"); textAlign = Paint.Align.CENTER; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111827"); textAlign = Paint.Align.CENTER; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        }

        fun setTitle(t: String) { title = t; invalidate() }
        fun setUnit(u: String) { unit = u; invalidate() }
        fun setRange(mi: Double, ma: Double) { min = mi; max = ma; invalidate() }
        fun setValue(v: Double) { value = v; invalidate() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val hh = max(h, 200) // keep some height
            setMeasuredDimension(w, hh)
        }

        override fun onDraw(c: Canvas) {
            val pad = 18f
            val w = width.toFloat(); val h = height.toFloat()
            val size = min(w - pad*2, h*2 - pad*2)
            val rect = RectF((w - size)/2f, pad, (w + size)/2f, pad + size)
            val start = 180f; val total = 180f

            // Track + progress
            c.drawArc(rect, start, total, false, track)
            val t = ((value - min)/(max - min)).coerceIn(0.0,1.0).toFloat()
            c.drawArc(rect, start, total * t, false, progress)

            // Needle
            val angle = start + total * t
            val cx = rect.centerX(); val cy = rect.centerY()
            val r = rect.width()/2.25f; val ang = Math.toRadians(angle.toDouble())
            val tipX = (cx + r * cos(ang)).toFloat(); val tipY = (cy + r * sin(ang)).toFloat()
            val baseW = 12f; val back = 34f; val perp = ang + Math.PI/2
            val b1x = (cx - back * cos(ang) + baseW * cos(perp)).toFloat()
            val b1y = (cy - back * sin(ang) + baseW * sin(perp)).toFloat()
            val b2x = (cx - back * cos(ang) - baseW * cos(perp)).toFloat()
            val b2y = (cy - back * sin(ang) - baseW * sin(perp)).toFloat()
            val path = Path(); path.moveTo(tipX, tipY); path.lineTo(b1x, b1y); path.lineTo(b2x, b2y); path.close()
            c.drawPath(path, pointer); c.drawCircle(cx, cy, 10f, pointer)

            // Title and numeric value
            c.drawText(title, w/2f, rect.top - 8f, labelPaint)
            c.drawText(String.format("%.2f %s", value, unit), w/2f, rect.centerY()+16f, valuePaint)
        }
    }
}
