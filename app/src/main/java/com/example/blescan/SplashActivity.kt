package com.example.blescan

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val SPLASH_MS = 4000L    // 6 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)   // white BG
            setPadding(dp(24), dp(36), dp(24), dp(24))
        }

        val fade = AlphaAnimation(0f, 1f).apply { duration = 2000 }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(160)
            )
            startAnimation(fade)
        }

        val t1 = neon("Lithium ion Technology", 20f, "#00A0A0", fade)
        val t2 = neon("Power On Forever", 32f, "#2563EB", fade)
        val t3 = neon("by Amitis Battery", 18f, "#475569", fade)

        val website = TextView(this).apply {
            text = "https://amitisbattery.com"
            setTextColor(Color.parseColor("#374151"))
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            startAnimation(fade)
        }

        root.addView(logo)
        root.addView(space(8))
        root.addView(t1)
        root.addView(space(14))
        root.addView(t2)
        root.addView(t3)
        root.addView(website)

        setContentView(root)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MeterActivity::class.java))
            finish()
        }, SPLASH_MS)
    }

    private fun neon(txt:String, sp:Float, color:String, fade:AlphaAnimation):TextView {
        return TextView(this).apply {
            text = txt
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setShadowLayer(12f, 0f, 0f, Color.parseColor("#8899AA"))
            gravity = Gravity.CENTER_HORIZONTAL
            startAnimation(fade)
        }
    }

    private fun space(h:Int)=TextView(this).apply{height=dp(h)}
    private fun dp(v:Int)=TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,v.toFloat(),resources.displayMetrics).toInt()
}
