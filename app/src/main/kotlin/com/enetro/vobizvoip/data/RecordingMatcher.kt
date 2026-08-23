package com.enetro.vobizvoip.data

import kotlin.math.abs

/**
 * Recordings are produced server-side and cannot carry the app's local call id,
 * so we associate them with a [CallLogEntry] by direction + phone number (last
 * 10 digits) and the closest start time within a tolerance window.
 *
 * Shared by the Recents UI and the [com.enetro.vobizvoip.provider.CallLogProvider]
 * so that both surface the same recording for a given call.
 */
object RecordingMatcher {
    private const val MATCH_TOLERANCE_MS = 5 * 60_000L

    fun match(entry: CallLogEntry, recordings: List<Recording>): Recording? {
        val entryDigits = entry.number.filter(Char::isDigit).takeLast(10)
        if (entryDigits.isEmpty()) return null
        return recordings
            .filter {
                it.direction == entry.direction &&
                    it.number.filter(Char::isDigit).takeLast(10) == entryDigits
            }
            .minByOrNull { abs(it.startedAtEpochMs - entry.startedAt) }
            ?.takeIf { abs(it.startedAtEpochMs - entry.startedAt) <= MATCH_TOLERANCE_MS }
    }
}
