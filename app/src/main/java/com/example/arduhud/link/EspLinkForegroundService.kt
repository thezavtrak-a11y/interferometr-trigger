package com.example.arduhud.link

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.arduhud.MainActivity
import com.example.arduhud.R

/**
 * Keeps SoftAP/TCP + motion sensors alive while the UI is backgrounded.
 * Started whenever the ESP link is connected.
 */
class EspLinkForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCallback?.onStopRequested()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val notification = buildNotification(
            clicksArmed = intent?.getBooleanExtra(EXTRA_CLICKS_ARMED, false) == true,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.link_fg_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.link_fg_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(clicksArmed: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, EspLinkForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (clicksArmed) {
            getString(R.string.link_fg_text_armed)
        } else {
            getString(R.string.link_fg_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_click_arm)
            .setContentTitle(getString(R.string.link_fg_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.link_fg_stop), stop)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    fun interface StopCallback {
        fun onStopRequested()
    }

    companion object {
        private const val CHANNEL_ID = "arduhud_link"
        private const val NOTIF_ID = 42
        private const val WAKE_TAG = "arduhud:link"
        private const val WAKE_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        private const val ACTION_STOP = "com.example.arduhud.action.STOP_LINK_FG"
        private const val EXTRA_CLICKS_ARMED = "clicks_armed"

        @Volatile
        var stopCallback: StopCallback? = null

        fun start(context: Context, clicksArmed: Boolean) {
            val intent = Intent(context, EspLinkForegroundService::class.java)
                .putExtra(EXTRA_CLICKS_ARMED, clicksArmed)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EspLinkForegroundService::class.java))
        }
    }
}
