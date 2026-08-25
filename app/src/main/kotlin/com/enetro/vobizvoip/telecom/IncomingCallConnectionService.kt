package com.enetro.vobizvoip.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.enetro.vobizvoip.VobizApplication
import com.enetro.vobizvoip.data.DiagnosticLog
import com.enetro.vobizvoip.service.IncomingCallPresenter

class IncomingCallConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val extras = request?.extras
        val pendingCallId = extras?.getString(EXTRA_PENDING_CALL_ID)
            ?: IncomingCallSession.pendingCallId
            ?: "unknown"
        val caller = extras?.getString(EXTRA_CALLER)
            ?: IncomingCallSession.caller
            ?: "Unknown caller"
        IncomingCallSession.bind(pendingCallId, caller)
        val connection = IncomingConnection(pendingCallId, caller)
        val address = request?.address ?: IncomingCallAccount.addressUri(caller)
        connection.setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(caller, TelecomManager.PRESENTATION_ALLOWED)
        connection.setRinging()
        IncomingCallSession.attach(connection)
        DiagnosticLog.i(TAG, "Telecom incoming connection created for $pendingCallId")
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        DiagnosticLog.w(TAG, "Telecom incoming connection failed: ${request?.address}")
    }

    private inner class IncomingConnection(
        private val pendingCallId: String,
        private val caller: String,
    ) : Connection() {
        init {
            connectionProperties = PROPERTY_SELF_MANAGED
            connectionCapabilities = CAPABILITY_MUTE
            audioModeIsVoip = true
        }

        override fun onShowIncomingCallUi() {
            IncomingCallPresenter.notifyIncoming(this@IncomingCallConnectionService, pendingCallId, caller)
        }

        override fun onAnswer() {
            setActive()
            val coordinator = (application as VobizApplication).containerOrNull()?.coordinator
            if (coordinator == null) {
                DiagnosticLog.w(TAG, "Telecom answer ignored: coordinator not ready")
                return
            }
            coordinator.showPendingInbound(pendingCallId, caller)
            coordinator.acceptPendingInbound()
        }

        override fun onReject() {
            val coordinator = (application as VobizApplication).containerOrNull()?.coordinator
            coordinator?.showPendingInbound(pendingCallId, caller)
            coordinator?.declinePendingInbound()
            setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
            destroy()
            IncomingCallSession.connection = null
        }

        override fun onDisconnect() {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
            IncomingCallSession.connection = null
        }
    }

    companion object {
        const val EXTRA_PENDING_CALL_ID = "pendingCallId"
        const val EXTRA_CALLER = "caller"
        private const val TAG = "VobizTelecom"
    }
}
