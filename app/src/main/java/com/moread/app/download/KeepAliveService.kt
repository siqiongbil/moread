package com.moread.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.moread.app.MainActivity

/**
 * 下载保活前台服务：缓存期间常驻通知（dataSync 类型），进程优先级提升到前台，
 * 持有带超时的唤醒锁（兼容 Android 14 的 6 小时 dataSync 上限与 Doze）。
 * 各 ROM 兼容点：
 * - 8+：通知渠道必须先建，否则 startForeground 崩溃
 * - 13+：POST_NOTIFICATIONS 未授权时通知不显示但服务照常运行（不请求、不崩溃）
 * - 14+：startForeground 必须带 foregroundServiceType=dataSync
 * - 29 以下：startForeground 无类型重载
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoRead:download").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        // 唤醒锁上限 30 分钟（低于系统 FGS 各类上限），到点自动释放
        runCatching { wakeLock?.acquire(30 * 60 * 1000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "在线缓存", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "书籍缓存进行中"
                    setShowBadge(false)
                },
            )
        }
        // SINGLE_TOP + REORDER_TO_FRONT：返回时复用已有 Activity，不创建新实例
        // （新实例会创建新 ViewModelStore，导致旧 ViewModel 被清空、下载协程被杀）
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("墨阅正在缓存书籍")
            .setContentText("后台下载中，点按打开书架")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "download_keepalive"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(Intent(context, KeepAliveService::class.java))
                } else {
                    context.startService(Intent(context, KeepAliveService::class.java))
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }
}
