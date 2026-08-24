package com.enetro.vobizvoip.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import com.enetro.vobizvoip.AppContainer
import com.enetro.vobizvoip.VobizApplication
import com.enetro.vobizvoip.data.AppConfig
import com.enetro.vobizvoip.data.CallDirection
import com.enetro.vobizvoip.data.CallLogEntry
import com.enetro.vobizvoip.data.CallResult
// Routes this file's existing Log.w calls through the diagnostic facade so they
// are captured to the on-device log DB (when enabled) as well as logcat.
import com.enetro.vobizvoip.data.DiagnosticLog as Log
import com.enetro.vobizvoip.data.Recording
import com.enetro.vobizvoip.data.RecordingMatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileNotFoundException

/**
 * Exposes this app's VoIP call history (and, when call recording is enabled, the
 * associated recording) to other apps.
 *
 * This is the app's *own* log — not the system [android.provider.CallLog] — because
 * VoIP calls never touch the telephony stack. The provider runs in-process, so it
 * reads the same in-memory stores the app writes through [VobizApplication.container].
 *
 * URIs:
 * - `content://com.enetro.vobizvoip.provider.calllog/calls`            all entries;
 * - `content://com.enetro.vobizvoip.provider.calllog/calls/{entryId}`  one entry;
 * - `content://com.enetro.vobizvoip.provider.calllog/recordings/{id}/audio`
 *   an openable audio stream (proxied from the backend so the auth token never leaves
 *   this app). Referenced by the `recording_path` column.
 *
 * Reads are gated by the `com.enetro.vobizvoip.permission.READ_CALL_LOG` permission
 * declared in the manifest.
 */
class CallLogProvider : ContentProvider() {
    private val httpClient by lazy { OkHttpClient() }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "calls", CODE_CALLS)
        addURI(AUTHORITY, "calls/*", CODE_CALL_ITEM)
        addURI(AUTHORITY, "recordings/*/audio", CODE_RECORDING_AUDIO)
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_CALLS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.call"
        CODE_CALL_ITEM -> "vnd.android.cursor.item/vnd.$AUTHORITY.call"
        CODE_RECORDING_AUDIO -> "audio/*"
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val container = container() ?: return null
        val entries = when (matcher.match(uri)) {
            CODE_CALLS -> container.callLogStore.entries.value
            CODE_CALL_ITEM -> {
                val id = uri.lastPathSegment
                container.callLogStore.entries.value.filter { it.id == id }
            }
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }
        val config = container.coordinator.state.value.config
        val recordings = container.coordinator.recordings.value
        val columns = projection ?: ALL_COLUMNS
        val cursor = MatrixCursor(columns)
        entries.forEachIndexed { index, entry ->
            val values = rowValues(entry, index, config, recordings) { number ->
                container.contactsRepository.nameFor(number)
            }
            cursor.addRow(columns.map { values[it] })
        }
        context?.contentResolver?.let { cursor.setNotificationUri(it, CALLS_URI) }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (matcher.match(uri) != CODE_RECORDING_AUDIO) {
            throw FileNotFoundException("Unsupported URI: $uri")
        }
        if (mode != "r") throw FileNotFoundException("Recordings are read-only: $uri")
        val container = container() ?: throw FileNotFoundException("App not initialized")
        val config = container.coordinator.state.value.config
        if (!config.recordingEnabled) throw FileNotFoundException("Call recording is disabled")
        if (!config.backendUrl.startsWith("http")) {
            throw FileNotFoundException("Backend is not configured")
        }
        val recordingId = uri.pathSegments.getOrNull(1)
            ?: throw FileNotFoundException("Missing recording id: $uri")
        return streamRecording(config, recordingId)
    }

    /** Streams the backend recording into a pipe, keeping the auth token in-app. */
    private fun streamRecording(config: AppConfig, recordingId: String): ParcelFileDescriptor {
        val url = "${config.backendUrl.removeSuffix("/")}/recordings/$recordingId/audio"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.backendToken}")
            .header("X-Vobiz-Endpoint", config.sipUsername)
            .build()
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        Thread({
            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) {
                        writeSide.closeWithError("Backend HTTP ${response.code}")
                        return@use
                    }
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Recording stream failed: ${error.message}")
                runCatching { writeSide.closeWithError(error.message ?: "stream error") }
            }
        }, "calllog-recording-stream").start()
        return readSide
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Call log is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Call log is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Call log is read-only")

    private fun container(): AppContainer? {
        val app = context?.applicationContext as? VobizApplication ?: return null
        return app.containerOrNull()
    }

    private fun rowValues(
        entry: CallLogEntry,
        index: Int,
        config: AppConfig,
        recordings: List<Recording>,
        nameFor: (String) -> String?,
    ): Map<String, Any?> {
        val recording = if (config.recordingEnabled) {
            RecordingMatcher.match(entry, recordings)
        } else {
            null
        }
        val recordingPath = recording?.let {
            CALLS_URI.buildUpon()
                .path("recordings/${it.id}/audio")
                .build()
                .toString()
        }
        return mapOf(
            BaseColumns._ID to index.toLong(),
            COLUMN_ENTRY_ID to entry.id,
            COLUMN_NUMBER to entry.number,
            COLUMN_DISPLAY_NAME to (nameFor(entry.number) ?: ""),
            COLUMN_DIRECTION to entry.direction.name,
            COLUMN_TYPE to entry.type(),
            COLUMN_RESULT to entry.result.name,
            COLUMN_DATE to entry.startedAt,
            COLUMN_DURATION to entry.durationSeconds,
            COLUMN_RECORDING_AVAILABLE to if (recording != null) 1 else 0,
            COLUMN_RECORDING_PATH to recordingPath,
        )
    }

    private fun CallLogEntry.type(): Int = when {
        result == CallResult.MISSED -> TYPE_MISSED
        direction == CallDirection.INCOMING -> TYPE_INCOMING
        else -> TYPE_OUTGOING
    }

    companion object {
        const val AUTHORITY = "com.enetro.vobizvoip.provider.calllog"
        val CALLS_URI: Uri = Uri.parse("content://$AUTHORITY/calls")

        const val COLUMN_ENTRY_ID = "entry_id"
        const val COLUMN_NUMBER = "number"
        const val COLUMN_DISPLAY_NAME = "display_name"
        const val COLUMN_DIRECTION = "direction"
        const val COLUMN_TYPE = "type"
        const val COLUMN_RESULT = "result"
        const val COLUMN_DATE = "date"
        const val COLUMN_DURATION = "duration"
        const val COLUMN_RECORDING_AVAILABLE = "recording_available"
        const val COLUMN_RECORDING_PATH = "recording_path"

        // Mirrors android.provider.CallLog.Calls type constants for familiarity.
        const val TYPE_INCOMING = 1
        const val TYPE_OUTGOING = 2
        const val TYPE_MISSED = 3

        val ALL_COLUMNS = arrayOf(
            BaseColumns._ID,
            COLUMN_ENTRY_ID,
            COLUMN_NUMBER,
            COLUMN_DISPLAY_NAME,
            COLUMN_DIRECTION,
            COLUMN_TYPE,
            COLUMN_RESULT,
            COLUMN_DATE,
            COLUMN_DURATION,
            COLUMN_RECORDING_AVAILABLE,
            COLUMN_RECORDING_PATH,
        )

        private const val CODE_CALLS = 1
        private const val CODE_CALL_ITEM = 2
        private const val CODE_RECORDING_AUDIO = 3
        private const val TAG = "VobizCallLogProvider"
    }
}
