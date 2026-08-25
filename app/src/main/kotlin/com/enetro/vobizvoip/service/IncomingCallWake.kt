package com.enetro.vobizvoip.service

import android.content.Context
import android.os.PowerManager
import com.enetro.vobizvoip.data.DiagnosticLog

/**
 * Holds a short CPU wake lock so Doze cannot freeze the process between the
 * inbound FCM callback and the incoming-call UI / SIP join.
 */
object IncomingCallWake {
    private const val TAG = "vobizvoip:incoming"
    private const val TIMEOUT_MS = 35_000L

    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        val existing = wakeLock
        if (existing?.isHeld == true) return
        val lock = context.applicationContext
            .getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            .apply { setReferenceCounted(false) }
        lock.acquire(TIMEOUT_MS)
        wakeLock = lock
        DiagnosticLog.i("VobizCall", "Incoming wake lock acquired")
    }

    @Synchronized
    fun release() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
            DiagnosticLog.i("VobizCall", "Incoming wake lock released")
        }
        wakeLock = null
    }
}
