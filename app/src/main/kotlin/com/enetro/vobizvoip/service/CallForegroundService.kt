package com.enetro.vobizvoip.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.enetro.vobizvoip.MainActivity
import com.enetro.vobizvoip.VobizApplication

class CallForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(intent.getStringExtra(EXTRA_REMOTE).orEmpty()),
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(remote: String): Notification {
        val hangupIntent = PendingIntent.getActivity(
            this,
            31,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_HANGUP
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            32,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, VobizApplication.ACTIVE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Enetro call in progress")
            .setContentText(remote)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, "Hang up", hangupIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.enetro.vobizvoip.action.START_CALL_SERVICE"
        const val ACTION_STOP = "com.enetro.vobizvoip.action.STOP_CALL_SERVICE"
        const val EXTRA_REMOTE = "remote"
        private const val NOTIFICATION_ID = 4102
    }
}
