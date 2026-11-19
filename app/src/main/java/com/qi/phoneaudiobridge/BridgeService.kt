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
        isRunning = true
        AudioEngine.attachContext(applicationContext)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
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
            ACTION_REFRESH_FG -> {
                // camera relay started/stopped (wave 4): re-post the
                // foreground notification with the FGS type set that
                // matches current engine state (Android 14 camera type).
                showForeground()
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
            .setContentTitle(if (paused) getString(R.string.notif_title_paused) else getString(R.string.notif_title_running))
            .setContentText(
                if (paused) getString(R.string.notif_text_paused)
                else getString(R.string.notif_text_running)
            )
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentIntent(openIntent)
            .setOngoing(true)
        if (paused) {
            b.addAction(Notification.Action.Builder(null, getString(R.string.notif_action_resume), actionIntent(ACTION_RESUME, 12)).build())
        } else {
            b.addAction(Notification.Action.Builder(null, getString(R.string.notif_action_pause), actionIntent(ACTION_PAUSE, 11)).build())
        }
        b.addAction(Notification.Action.Builder(null, getString(R.string.notif_action_disconnect), actionIntent(ACTION_DISCONNECT, 13)).build())
        return b.build()
    }

    private fun showForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            // Android 14 (API 34): while the camera relay is live, the
            // service must also carry the camera type or the system
            // blocks camera access from the background. Only added when
            // the CAMERA permission is actually granted — declaring a
            // type without its permission throws.
            if (Build.VERSION.SDK_INT >= 34 && AudioEngine.cameraRelayActive &&
                checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIF_ID, notification, types)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        isRunning = false
        // When the user switches 后台运行 off in Settings, the Activity sets
        // stopEngineOnDestroy=false first so the engine survives the switch
        // to Activity-bound mode instead of being killed with the service.
        if (stopEngineOnDestroy) AudioEngine.stop()
        stopEngineOnDestroy = true
        super.onDestroy()
    }

    companion object {
        @Volatile var stopEngineOnDestroy = true
        /** True while the service instance exists (onCreate..onDestroy);
         * the engine uses it to ask for a foreground-type refresh only
         * when there is a service to refresh. */
        @Volatile var isRunning = false
        private const val CHANNEL_ID = "phone_audio_bridge"
        private const val NOTIF_ID = 1
        const val ACTION_PAUSE = "com.qi.phoneaudiobridge.action.PAUSE"
        const val ACTION_RESUME = "com.qi.phoneaudiobridge.action.RESUME"
        const val ACTION_DISCONNECT = "com.qi.phoneaudiobridge.action.DISCONNECT"
        const val ACTION_RETURN_CONNECT = "com.qi.phoneaudiobridge.action.RETURN_CONNECT"
        const val ACTION_RETURN_IGNORE = "com.qi.phoneaudiobridge.action.RETURN_IGNORE"
        const val ACTION_REFRESH_FG = "com.qi.phoneaudiobridge.action.REFRESH_FG"
    }
}
