package com.enetro.vobizvoip.service

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import com.enetro.vobizvoip.data.DiagnosticLog

/**
 * Sole incoming-call ringtone owner. Notification channels stay silent so
 * foreground, background, killed, sleep, and lock-screen incoming never stack
 * a channel sound on top of [Ringtone.play].
 */
object IncomingCallRinger {
    @Volatile
    private var ringtone: Ringtone? = null

    @Synchronized
    fun start(context: Context) {
        if (ringtone?.isPlaying == true) return
        stopLocked()
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val tone = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            tone.isLooping = true
            tone.play()
            ringtone = tone
            DiagnosticLog.i(TAG, "Incoming ringtone started")
        }.onFailure {
            DiagnosticLog.w(TAG, "Incoming ringtone failed: ${it.message}")
        }
    }

    @Synchronized
    fun stop() {
        if (ringtone == null) return
        stopLocked()
        DiagnosticLog.i(TAG, "Incoming ringtone stopped")
    }

    private fun stopLocked() {
        ringtone?.let { current ->
            runCatching { current.stop() }
        }
        ringtone = null
    }

    private const val TAG = "VobizCall"
}
