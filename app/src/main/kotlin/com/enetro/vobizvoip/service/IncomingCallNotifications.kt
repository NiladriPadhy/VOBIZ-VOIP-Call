package com.enetro.vobizvoip.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.enetro.vobizvoip.MainActivity
import com.enetro.vobizvoip.VobizApplication

/**
 * Incoming-call UI only. The channel is silent; [IncomingCallRinger] plays the ring
 * so lock-screen Telecom + FGS notifications cannot stack two sounds.
 */
object IncomingCallNotifications {
    fun build(context: Context, pendingCallId: String, caller: String): Notification {
        val showIntent = callIntent(context, MainActivity.ACTION_SHOW_PENDING, pendingCallId, caller)
        val answerIntent = callIntent(context, MainActivity.ACTION_ANSWER_PENDING, pendingCallId, caller)
        val declineIntent = callIntent(context, MainActivity.ACTION_DECLINE_PENDING, pendingCallId, caller)
        return NotificationCompat.Builder(context, VobizApplication.INCOMING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming Enetro call")
            .setContentText(caller)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setFullScreenIntent(showIntent, true)
            .setContentIntent(showIntent)
            .addAction(0, "Decline", declineIntent)
            .addAction(0, "Answer", answerIntent)
            .setTimeoutAfter(INCOMING_TIMEOUT_MS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun callIntent(
        context: Context,
        action: String,
        pendingCallId: String,
        caller: String,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        (pendingCallId + action).hashCode(),
        Intent(context, MainActivity::class.java).apply {
            this.action = action
            putExtra(MainActivity.EXTRA_PENDING_CALL_ID, pendingCallId)
            putExtra(MainActivity.EXTRA_CALLER, caller)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private const val INCOMING_TIMEOUT_MS = 30_000L
}
