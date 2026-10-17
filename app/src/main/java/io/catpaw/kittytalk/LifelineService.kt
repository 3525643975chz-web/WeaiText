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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 读取网页传过来的开关状态，默认 false（显示通知）
        val isHide = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
            .getBoolean("bg_mode_hide", false)

        if (isHide) {
            // 🟢 开启了隐藏：直接让服务进入普通后台模式，不弹出通知
            stopForeground(true)
            // 注意：不调用 startForeground，系统就不会显示通知
        } else {
            // 🔴 没开启隐藏：正常启用前台保活并显示通知
            val notification = createNotification()
            startForeground(1001, notification)
        }

        // 如果服务被系统杀了，自动重启
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "lifeline_channel",
                "WeaiText 运行状态",
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
            .setContentText("正在后台守护您的输入...")
            .setSmallIcon(android.R.drawable.ic_menu_compass) // 可以换成你自己的图标
            .build()
    }
}
