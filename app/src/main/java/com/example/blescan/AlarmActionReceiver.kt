package com.example.blescan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.widget.Toast
import androidx.core.app.NotificationCompat

class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val notificationId = intent.getIntExtra("notification_id", 0)
        val alarmType = intent.getStringExtra("alarm_type")
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        when (action) {
            "SILENCE_ALARM" -> {
                // Store silence preference in shared preferences
                val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("sound_enabled", false)
                    .putBoolean("vibration_enabled", false)
                    .apply()
                
                Toast.makeText(context, "Alarm sound and vibration disabled", Toast.LENGTH_SHORT).show()
                
                // Update the notification to show it's silenced
                updateNotificationToSilenced(context, notificationManager, notificationId, alarmType)
            }
            "DISMISS_NOTIFICATION" -> {
                // Simply dismiss the notification
                notificationManager.cancel(notificationId)
                Toast.makeText(context, "Notification dismissed", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateNotificationToSilenced(
        context: Context,
        notificationManager: NotificationManager,
        notificationId: Int,
        alarmType: String?
    ) {
        val title = when (alarmType) {
            "low_battery" -> "🔇 Low Battery (Silenced)"
            "high_temp" -> "🔇 High Temperature (Silenced)"
            else -> "🔇 Alarm Silenced"
        }
        
        val message = when (alarmType) {
            "low_battery" -> "Battery SOC is low - Sound/Vibration disabled"
            "high_temp" -> "Temperature is high - Sound/Vibration disabled"
            else -> "Alarm condition active - Sound/Vibration disabled"
        }
        
        val intent = Intent(context, MeterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "bms_alerts_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setOngoing(false)
            .setColor(android.graphics.Color.GRAY)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
