package io.catpaw.kittytalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class LifelineService : Service() {

    companion object {
        fun start(context: Context) {
            try {
                val intent = Intent(context, LifelineService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LifelineService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = createNotification()
            startForeground(1001, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "lifeline_channel",
                "WeaiText 魔法运行中",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "保持魔法服务在后台运行"
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "lifeline_channel")
        } else {
            Notification.Builder(this)
        }
        
        return builder
            .setContentTitle("WeaiText 魔法运行中")
            .setContentText("本喵正在后台为你施法...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }
}
