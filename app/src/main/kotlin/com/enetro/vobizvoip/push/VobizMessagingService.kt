package com.enetro.vobizvoip.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.enetro.vobizvoip.MainActivity
import com.enetro.vobizvoip.VobizApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class VobizMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        applicationContainer().coordinator.registerInstallation(installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "inbound_call") return
        val pendingCallId = message.data["pendingCallId"] ?: return
        val expiresAt = message.data["expiresAt"]?.toLongOrNull() ?: 0L
        if (expiresAt <= System.currentTimeMillis()) return
        val caller = message.data["caller"].orEmpty().ifBlank { "Unknown caller" }
        if ((application as VobizApplication).isAppInForeground) {
            // App is visible: skip the Answer/Decline notification and bring the
            // in-app calling screen to the front. CallCoordinator plays the
            // default ringtone once the INCOMING state is shown.
            launchIncomingCallScreen(pendingCallId, caller)
        } else {
            showIncomingCall(pendingCallId, caller)
        }
    }

    private fun launchIncomingCallScreen(pendingCallId: String, caller: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SHOW_PENDING
            putExtra(MainActivity.EXTRA_PENDING_CALL_ID, pendingCallId)
            putExtra(MainActivity.EXTRA_CALLER, caller)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun showIncomingCall(pendingCallId: String, caller: String) {
        val showIntent = callIntent(MainActivity.ACTION_SHOW_PENDING, pendingCallId, caller)
        val answerIntent = callIntent(MainActivity.ACTION_ANSWER_PENDING, pendingCallId, caller)
        val declineIntent = callIntent(MainActivity.ACTION_DECLINE_PENDING, pendingCallId, caller)
        val notification = NotificationCompat.Builder(this, VobizApplication.INCOMING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming Enetro call")
            .setContentText(caller)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(showIntent, true)
            .setContentIntent(showIntent)
            .addAction(0, "Decline", declineIntent)
            .addAction(0, "Answer", answerIntent)
            .setTimeoutAfter(INCOMING_TIMEOUT_MS)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(pendingCallId.hashCode(), notification)
    }

    private fun callIntent(
        action: String,
        pendingCallId: String,
        caller: String,
    ): PendingIntent = PendingIntent.getActivity(
        this,
        (pendingCallId + action).hashCode(),
        Intent(this, MainActivity::class.java).apply {
            this.action = action
            putExtra(MainActivity.EXTRA_PENDING_CALL_ID, pendingCallId)
            putExtra(MainActivity.EXTRA_CALLER, caller)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun applicationContainer() =
        (application as VobizApplication).container

    private companion object {
        const val INCOMING_TIMEOUT_MS = 30_000L
    }
}
