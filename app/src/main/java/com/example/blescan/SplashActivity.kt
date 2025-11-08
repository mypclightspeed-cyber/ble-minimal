package com.example.blescan

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    // Show for ~6 seconds as requested
    private val SPLASH_MS = 6000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Background: Neon Battery Blue-Green (dark navy → black gradient) ---
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#0B1220"), Color.parseColor("#05080F"))
        )
        bg.gradientType = GradientDrawable.LINEAR_GRADIENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            background = bg
            setPadding(dp(24), dp(36), dp(24), dp(24))
        }

        // Logo (medium top)
        val logo = ImageView(this).apply {
            // Ensure you have app/src/main/res/drawable/logo.png (or vector)
            setImageResource(R.drawable.logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(160) // medium height
            ).apply {
                bottomMargin = dp(24)
            }
        }

        // Title lines with neon glow
        val line1 = neonText("Lithium Ion Technology", 20f, "#A7F3D0")  // mint
        val line2 = neonText("Power On Forever", 32f, "#22D3EE")         // cyan (bigger)
        val line3 = neonText("by Amitis Battery", 18f, "#86EFAC")        // green

        // extra spacing
        val spacer1 = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(6)) }
        val spacer2 = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(14)) }

        root.addView(logo)
        root.addView(spacer1)
        root.addView(line1)
        root.addView(spacer2)
        root.addView(line2)
        root.addView(line3)

        setContentView(root)

        // Continue to ScanActivity after delay
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, ScanActivity::class.java))
            finish()
        }, SPLASH_MS)
    }

    private fun neonText(text: String, sp: Float, colorHex: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(colorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            // Subtle neon glow
            setShadowLayer(12f, 0f, 0f, adjustAlpha(Color.parseColor(colorHex), 0.55f))
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt()
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Color.argb(a, r, g, b)
    }
}
