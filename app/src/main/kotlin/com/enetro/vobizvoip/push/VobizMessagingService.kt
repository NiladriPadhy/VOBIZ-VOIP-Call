package com.enetro.vobizvoip.push

import com.enetro.vobizvoip.VobizApplication
import com.enetro.vobizvoip.data.DiagnosticLog
import com.enetro.vobizvoip.service.IncomingCallPresenter
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class VobizMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        DiagnosticLog.i(TAG, "FCM onRegistered; installation id refreshed")
        applicationContainer().coordinator.registerInstallation(installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"]
        if (type != "inbound_call") {
            DiagnosticLog.d(TAG, "FCM message ignored (type=$type)")
            return
        }
        val pendingCallId = message.data["pendingCallId"]
        if (pendingCallId == null) {
            DiagnosticLog.w(TAG, "Inbound FCM missing pendingCallId; ignoring")
            return
        }
        val expiresAt = message.data["expiresAt"]?.toLongOrNull() ?: 0L
        if (expiresAt <= System.currentTimeMillis()) {
            DiagnosticLog.w(
                TAG,
                "Inbound FCM already expired; ignoring pendingCallId=$pendingCallId",
            )
            return
        }
        val caller = message.data["caller"].orEmpty().ifBlank { "Unknown caller" }
        val foreground = (application as VobizApplication).isAppInForeground
        DiagnosticLog.i(
            TAG,
            "Inbound call push; pendingCallId=$pendingCallId; caller=$caller; " +
                "foreground=$foreground",
        )
        IncomingCallPresenter.present(
            context = this,
            pendingCallId = pendingCallId,
            caller = caller,
            appInForeground = foreground,
        )
    }

    private fun applicationContainer() =
        (application as VobizApplication).container

    private companion object {
        const val TAG = "VobizPush"
    }
}
