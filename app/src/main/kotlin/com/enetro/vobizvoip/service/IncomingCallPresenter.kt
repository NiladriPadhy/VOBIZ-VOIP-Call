package com.enetro.vobizvoip.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.enetro.vobizvoip.MainActivity
import com.enetro.vobizvoip.data.DiagnosticLog
import com.enetro.vobizvoip.telecom.IncomingCallAccount

/**
 * Wakes the process, reports the call to Telecom, and starts the incoming
 * foreground service so a sleeping phone can show Answer/Decline.
 *
 * Ringtone audio is owned by [IncomingCallRinger]. Incoming notifications are
 * silent so lock-screen / killed-process presentation cannot stack two rings.
 */
object IncomingCallPresenter {
    fun present(
        context: Context,
        pendingCallId: String,
        caller: String,
        appInForeground: Boolean,
    ) {
        val app = context.applicationContext
        IncomingCallWake.acquire(app)
        IncomingCallRinger.start(app)
        IncomingCallAccount.reportIncoming(app, pendingCallId, caller)
        startIncomingService(app, pendingCallId, caller)
        if (appInForeground) {
            launchIncomingScreen(app, pendingCallId, caller)
        }
    }

    fun keepAwakeForIncoming(context: Context, caller: String, pendingCallId: String? = null) {
        val app = context.applicationContext
        IncomingCallWake.acquire(app)
        IncomingCallRinger.start(app)
        val alreadyPresentingId = IncomingCallAccount.pendingIdOrNull()
        val reportId = pendingCallId
        if (reportId != null && shouldReportIncomingCall(reportId, alreadyPresentingId)) {
            IncomingCallAccount.reportIncoming(app, reportId, caller)
        }
        startIncomingService(
            app,
            incomingServiceCallId(pendingCallId, alreadyPresentingId),
            caller,
        )
    }

    /**
     * Telecom [android.telecom.Connection.onShowIncomingCallUi] callback.
     * Reuses the incoming FGS notification instead of posting a second heads-up
     * that would ring in parallel on a locked or backgrounded device.
     */
    fun ensureIncomingUi(context: Context, pendingCallId: String, caller: String) {
        val app = context.applicationContext
        IncomingCallWake.acquire(app)
        IncomingCallRinger.start(app)
        startIncomingService(app, pendingCallId, caller)
    }

    fun launchIncomingScreen(context: Context, pendingCallId: String, caller: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SHOW_PENDING
            putExtra(MainActivity.EXTRA_PENDING_CALL_ID, pendingCallId)
            putExtra(MainActivity.EXTRA_CALLER, caller)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }

    fun cancelHeadsUp(context: Context, pendingCallId: String?) {
        pendingCallId?.let {
            context.getSystemService(NotificationManager::class.java).cancel(it.hashCode())
        }
    }

    fun finished(context: Context, pendingCallId: String? = IncomingCallAccount.pendingIdOrNull()) {
        IncomingCallRinger.stop()
        IncomingCallWake.release()
        IncomingCallAccount.disconnect()
        cancelHeadsUp(context, pendingCallId)
    }

    private fun startIncomingService(context: Context, pendingCallId: String, caller: String) {
        val intent = Intent(context, CallForegroundService::class.java).apply {
            action = CallForegroundService.ACTION_START_INCOMING
            putExtra(CallForegroundService.EXTRA_PENDING_CALL_ID, pendingCallId)
            putExtra(CallForegroundService.EXTRA_REMOTE, caller)
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { error ->
                DiagnosticLog.w("VobizCall", "Incoming FGS start failed: ${error.message}")
                notifyIncoming(context, pendingCallId, caller)
            }
    }

    private fun notifyIncoming(context: Context, pendingCallId: String, caller: String) {
        context.getSystemService(NotificationManager::class.java)
            .notify(pendingCallId.hashCode(), IncomingCallNotifications.build(context, pendingCallId, caller))
    }
}

internal fun incomingServiceCallId(requestedId: String?, alreadyPresentingId: String?): String =
    requestedId?.takeIf { it.isNotBlank() }
        ?: alreadyPresentingId?.takeIf { it.isNotBlank() }
        ?: SIP_FALLBACK_CALL_ID

internal fun shouldReportIncomingCall(requestedId: String?, alreadyPresentingId: String?): Boolean {
    val requested = requestedId?.takeIf { it.isNotBlank() } ?: return false
    return requested != alreadyPresentingId
}

private const val SIP_FALLBACK_CALL_ID = "sip-invite"
