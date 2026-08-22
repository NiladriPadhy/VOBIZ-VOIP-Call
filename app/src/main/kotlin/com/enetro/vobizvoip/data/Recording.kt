package com.enetro.vobizvoip.data

/** A server-side call recording (produced by Vobiz) available for playback. */
data class Recording(
    val id: String,
    val number: String,
    val direction: CallDirection,
    val startedAtEpochMs: Long,
    val durationSeconds: Long,
)
