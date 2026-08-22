package com.enetro.vobizvoip.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Streams a call recording from the backend proxy (`/recordings/{id}/audio`)
 * with the device auth headers. Only one recording plays at a time. Exposes
 * Compose-observable [playingId] / [preparingId] so rows can reflect state.
 */
class RecordingPlayer(
    private val context: Context,
    private val baseUrl: String,
    private val authToken: String,
    private val endpoint: String,
) {
    var playingId by mutableStateOf<String?>(null)
        private set
    var preparingId by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null

    fun toggle(recordingId: String) {
        if (recordingId == playingId || recordingId == preparingId) {
            stop()
            return
        }
        stop()
        preparingId = recordingId
        val headers = mapOf(
            "Authorization" to "Bearer $authToken",
            "X-Vobiz-Endpoint" to endpoint,
        )
        val uri = "$baseUrl/recordings/$recordingId/audio".toUri()
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        runCatching {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mediaPlayer.setDataSource(context, uri, headers)
            mediaPlayer.setOnPreparedListener { prepared ->
                if (player === prepared) {
                    preparingId = null
                    playingId = recordingId
                    prepared.start()
                }
            }
            mediaPlayer.setOnCompletionListener { stop() }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            mediaPlayer.prepareAsync()
        }.onFailure { stop() }
    }

    fun stop() {
        player?.let { mediaPlayer ->
            runCatching { if (mediaPlayer.isPlaying) mediaPlayer.stop() }
            runCatching { mediaPlayer.reset() }
            runCatching { mediaPlayer.release() }
        }
        player = null
        playingId = null
        preparingId = null
    }
}
