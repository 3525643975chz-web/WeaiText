package io.catpaw.kittytalk

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

class LifelineService : Service() {

    companion object {
        fun start(context: Context) {
            try {
                val intent = Intent(context, LifelineService::class.java)
                context.startService(intent)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 只要不被杀，就一直待着，绝不弹任何通知
        return START_STICKY
    }
}
