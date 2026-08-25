package com.enetro.vobizvoip.telecom

import android.telecom.Connection
import android.telecom.DisconnectCause

internal object IncomingCallSession {
    @Volatile
    var pendingCallId: String? = null

    @Volatile
    var caller: String? = null

    @Volatile
    var connection: Connection? = null

    fun bind(pendingCallId: String, caller: String) {
        this.pendingCallId = pendingCallId
        this.caller = caller
    }

    fun attach(connection: Connection) {
        this.connection = connection
    }

    fun setActive() {
        connection?.setActive()
    }

    fun disconnect(cause: Int = DisconnectCause.LOCAL) {
        connection?.let { current ->
            runCatching {
                current.setDisconnected(DisconnectCause(cause))
                current.destroy()
            }
        }
        connection = null
        pendingCallId = null
        caller = null
    }
}
