package com.qi.phoneaudiobridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service keeping the audio bridge alive with the screen off.
 * v2.1: the persistent notification carries 暂停/断开 action buttons (and
 * 继续 while paused), and this service also receives the 连接/忽略 actions
 * from the re-entry prompt notification posted by the engine.
 */
class BridgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "手机音频桥", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                AudioEngine.pauseAll()
                showForeground()
                return START_STICKY
            }
            ACTION_RESUME -> {
                AudioEngine.resumeAll()
                showForeground()
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                AudioEngine.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RETURN_CONNECT -> {
                AudioEngine.acceptReturnAsk()
                cancelAskNotification()
                return START_STICKY
            }
            ACTION_RETURN_IGNORE -> {
                AudioEngine.dismissReturnAsk()
                cancelAskNotification()
                return START_STICKY
            }
        }
        showForeground()
        AudioEngine.start(applicationContext)
        return START_STICKY
    }

    private fun cancelAskNotification() {
        try { getSystemService(NotificationManager::class.java)?.cancel(3) } catch (_: Exception) {}
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val i = Intent(this, BridgeService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val paused = AudioEngine.userPaused
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (paused) "声桥 AirBridge 已暂停" else "声桥 AirBridge 运行中")
            .setContentText(
                if (paused) "已暂停：点「继续」恢复自动连接"
                else "手机麦克风 → 电脑 · 电脑声音 → 手机"
            )
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentIntent(openIntent)
            .setOngoing(true)
        if (paused) {
            b.addAction(Notification.Action.Builder(null, "继续", actionIntent(ACTION_RESUME, 12)).build())
        } else {
            b.addAction(Notification.Action.Builder(null, "暂停", actionIntent(ACTION_PAUSE, 11)).build())
        }
        b.addAction(Notification.Action.Builder(null, "断开", actionIntent(ACTION_DISCONNECT, 13)).build())
        return b.build()
    }

    private fun showForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        // When the user switches 后台运行 off in Settings, the Activity sets
        // stopEngineOnDestroy=false first so the engine survives the switch
        // to Activity-bound mode instead of being killed with the service.
        if (stopEngineOnDestroy) AudioEngine.stop()
        stopEngineOnDestroy = true
        super.onDestroy()
    }

    companion object {
        @Volatile var stopEngineOnDestroy = true
        private const val CHANNEL_ID = "phone_audio_bridge"
        private const val NOTIF_ID = 1
        const val ACTION_PAUSE = "com.qi.phoneaudiobridge.action.PAUSE"
        const val ACTION_RESUME = "com.qi.phoneaudiobridge.action.RESUME"
        const val ACTION_DISCONNECT = "com.qi.phoneaudiobridge.action.DISCONNECT"
        const val ACTION_RETURN_CONNECT = "com.qi.phoneaudiobridge.action.RETURN_CONNECT"
        const val ACTION_RETURN_IGNORE = "com.qi.phoneaudiobridge.action.RETURN_IGNORE"
    }
}
