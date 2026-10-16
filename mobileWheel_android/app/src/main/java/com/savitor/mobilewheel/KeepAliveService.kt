package com.savitor.mobilewheel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 后台保活服务：监测 / 局域网开启期间以前台服务方式运行。
 *
 * 1. 前台服务提高进程优先级，App 退到后台后不会被系统（尤其 MIUI）冻结或回收，
 *    Activity 虽在后台，传感器与 TCP 服务仍能继续工作；
 * 2. 持有 PARTIAL_WAKE_LOCK，熄屏后 CPU 不挂起，传感器回调与 socket 写入得以继续。
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台传输", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持倾斜角数据持续发送到局域网客户端"
            }
        )
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在后台传输倾斜角数据")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mobilewheel_keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "mobileWheel::KeepAlive"

        /** 启动保活服务（重复调用无副作用） */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, KeepAliveService::class.java))
        }

        /** 停止保活服务 */
        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}