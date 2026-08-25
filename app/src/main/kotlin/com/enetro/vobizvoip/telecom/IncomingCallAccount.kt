package com.enetro.vobizvoip.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.enetro.vobizvoip.R
import com.enetro.vobizvoip.data.DiagnosticLog

/**
 * Registers a self-managed [PhoneAccount] and reports inbound calls to Telecom
 * so the OS treats them as real calls (lock-screen / full-screen policy).
 */
object IncomingCallAccount {
    private const val ACCOUNT_ID = "enetro_voip"
    private const val TAG = "VobizTelecom"

    fun handle(context: Context): PhoneAccountHandle = PhoneAccountHandle(
        ComponentName(context, IncomingCallConnectionService::class.java),
        ACCOUNT_ID,
    )

    fun register(context: Context) {
        val app = context.applicationContext
        val telecom = app.getSystemService(TelecomManager::class.java) ?: return
        val account = PhoneAccount.builder(handle(app), app.getString(R.string.app_name))
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .build()
        runCatching { telecom.registerPhoneAccount(account) }
            .onFailure { DiagnosticLog.w(TAG, "PhoneAccount register failed: ${it.message}") }
    }

    fun reportIncoming(context: Context, pendingCallId: String, caller: String) {
        val app = context.applicationContext
        register(app)
        IncomingCallSession.bind(pendingCallId, caller)
        val telecom = app.getSystemService(TelecomManager::class.java) ?: return
        val phoneAccount = handle(app)
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccount)
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, addressUri(caller))
            putString(IncomingCallConnectionService.EXTRA_PENDING_CALL_ID, pendingCallId)
            putString(IncomingCallConnectionService.EXTRA_CALLER, caller)
        }
        runCatching { telecom.addNewIncomingCall(phoneAccount, extras) }
            .onFailure { DiagnosticLog.w(TAG, "addNewIncomingCall failed: ${it.message}") }
    }

    fun setActive() {
        IncomingCallSession.setActive()
    }

    fun disconnect() {
        IncomingCallSession.disconnect()
    }

    fun pendingIdOrNull(): String? = IncomingCallSession.pendingCallId

    fun addressUri(caller: String): Uri =
        Uri.fromParts(PhoneAccount.SCHEME_TEL, sanitizedCallerNumber(caller), null)

    fun sanitizedCallerNumber(caller: String): String =
        caller.filter { it.isDigit() || it == '+' }.ifBlank { "0" }
}
