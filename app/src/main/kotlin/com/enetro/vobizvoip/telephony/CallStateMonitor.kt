package com.enetro.vobizvoip.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
// Routes this file's existing Log.w calls through the diagnostic facade so they
// are captured to the on-device log DB (when enabled) as well as logcat.
import com.enetro.vobizvoip.data.DiagnosticLog as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The device's cellular (GSM/CDMA) call state — distinct from the app's VoIP call. */
enum class CellularCallState { IDLE, RINGING, OFFHOOK }

/**
 * Monitors the device's native cellular call state using the API 31+
 * [TelephonyCallback]. This lets the VoIP layer react to a native phone call
 * arriving (e.g. auto-muting the VoIP microphone while a GSM call is off-hook).
 *
 * Requires [Manifest.permission.READ_PHONE_STATE]; [start] is a no-op until the
 * permission is granted.
 */
class CallStateMonitor(private val context: Context) {
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val _state = MutableStateFlow(CellularCallState.IDLE)
    val state: StateFlow<CellularCallState> = _state

    private var callback: TelephonyCallback? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        val manager = telephonyManager ?: return
        if (callback != null || !hasPermission()) return
        val cb = CallStateCallback { raw -> _state.value = raw.toCellularCallState() }
        runCatching {
            manager.registerTelephonyCallback(context.mainExecutor, cb)
            callback = cb
        }.onFailure { Log.w(TAG, "registerTelephonyCallback failed: ${it.message}") }
    }

    fun stop() {
        val cb = callback ?: return
        runCatching { telephonyManager?.unregisterTelephonyCallback(cb) }
        callback = null
        _state.value = CellularCallState.IDLE
    }

    private class CallStateCallback(
        private val onChanged: (Int) -> Unit,
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = onChanged(state)
    }

    private companion object {
        const val TAG = "VobizTelephony"

        fun Int.toCellularCallState(): CellularCallState = when (this) {
            TelephonyManager.CALL_STATE_RINGING -> CellularCallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CellularCallState.OFFHOOK
            else -> CellularCallState.IDLE
        }
    }
}
