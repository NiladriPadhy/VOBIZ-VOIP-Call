package com.enetro.vobizvoip.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.util.Log
import com.enetro.vobizvoip.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Severity of a captured diagnostic entry. The [priority] values mirror
 * [android.util.Log] so the same integer can drive both logcat and persistence.
 */
enum class LogLevel(val priority: Int, val short: String, val label: String) {
    VERBOSE(Log.VERBOSE, "V", "Verbose"),
    DEBUG(Log.DEBUG, "D", "Debug"),
    INFO(Log.INFO, "I", "Info"),
    WARN(Log.WARN, "W", "Warn"),
    ERROR(Log.ERROR, "E", "Error"),
    ;

    companion object {
        fun fromPriority(priority: Int): LogLevel =
            entries.firstOrNull { it.priority == priority } ?: INFO
    }
}

/** A single persisted diagnostic log line. */
data class DiagnosticLogEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val thread: String?,
    val stackTrace: String?,
)

/**
 * Process-wide diagnostic logging facade. Always mirrors to logcat; additionally
 * persists to [DiagnosticLogStore] on a background thread when [enabled] is true
 * (driven by the user's Settings toggle). Method names/signatures mirror
 * [android.util.Log] so call sites can route through it by aliasing the import:
 *
 * ```
 * import com.enetro.vobizvoip.data.DiagnosticLog as Log
 * ```
 */
object DiagnosticLog {
    /** When true, entries are written to the on-device database as well as logcat. */
    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var sink: DiagnosticLogStore? = null

    // Serializes DB writes off the calling (often main) thread while keeping the
    // captured timestamp accurate to when the event actually happened.
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostic-log-writer").apply { isDaemon = true }
    }

    fun install(store: DiagnosticLogStore) {
        sink = store
    }

    fun v(tag: String, message: String): Int = log(LogLevel.VERBOSE, tag, message, null)

    fun d(tag: String, message: String): Int = log(LogLevel.DEBUG, tag, message, null)

    fun i(tag: String, message: String): Int = log(LogLevel.INFO, tag, message, null)

    fun w(tag: String, message: String): Int = log(LogLevel.WARN, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable?): Int =
        log(LogLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String): Int = log(LogLevel.ERROR, tag, message, null)

    fun e(tag: String, message: String, throwable: Throwable?): Int =
        log(LogLevel.ERROR, tag, message, throwable)

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?): Int {
        val logcatMessage = if (throwable == null) {
            message
        } else {
            "$message\n${Log.getStackTraceString(throwable)}"
        }
        val result = Log.println(level.priority, tag, logcatMessage)
        val store = sink
        if (enabled && store != null) {
            // Capture identity now; the insert runs asynchronously.
            val timestamp = System.currentTimeMillis()
            val thread = Thread.currentThread().name
            val trace = throwable?.let { Log.getStackTraceString(it) }
            writer.execute { store.insert(timestamp, level, tag, message, thread, trace) }
        }
        return result
    }
}

/**
 * On-device store for diagnostic logs, backed by a dedicated SQLite database so it
 * can hold a high volume of append-only entries cheaply. Entries older than
 * [RETENTION_DAYS] days are pruned automatically. [revision] increments on every
 * mutation so observers can refresh.
 */
class DiagnosticLogStore(context: Context) {
    private val helper = OpenHelper(context.applicationContext)
    private val _revision = MutableStateFlow(0L)

    /** Bumped whenever the table changes, so the UI can reload the current view. */
    val revision: StateFlow<Long> = _revision

    @Volatile
    private var lastPruneAtMillis = 0L

    fun insert(
        timestampMillis: Long,
        level: LogLevel,
        tag: String,
        message: String,
        thread: String?,
        stackTrace: String?,
    ) {
        runCatching {
            val values = ContentValues(6).apply {
                put(COL_TS, timestampMillis)
                put(COL_LEVEL, level.priority)
                put(COL_TAG, tag)
                put(COL_MESSAGE, message)
                put(COL_THREAD, thread)
                put(COL_TRACE, stackTrace)
            }
            helper.writableDatabase.insert(TABLE, null, values)
            maybePrune(timestampMillis)
        }
        _revision.value += 1
    }

    /** Returns entries whose timestamp falls within [startMillis]..[endMillis], newest first. */
    fun query(
        startMillis: Long,
        endMillis: Long,
        limit: Int = MAX_QUERY,
    ): List<DiagnosticLogEntry> = runCatching {
        helper.readableDatabase.query(
            TABLE,
            null,
            "$COL_TS BETWEEN ? AND ?",
            arrayOf(startMillis.toString(), endMillis.toString()),
            null,
            null,
            "$COL_TS DESC, $COL_ID DESC",
            limit.toString(),
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(COL_ID)
            val tsIndex = cursor.getColumnIndexOrThrow(COL_TS)
            val levelIndex = cursor.getColumnIndexOrThrow(COL_LEVEL)
            val tagIndex = cursor.getColumnIndexOrThrow(COL_TAG)
            val messageIndex = cursor.getColumnIndexOrThrow(COL_MESSAGE)
            val threadIndex = cursor.getColumnIndexOrThrow(COL_THREAD)
            val traceIndex = cursor.getColumnIndexOrThrow(COL_TRACE)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DiagnosticLogEntry(
                            id = cursor.getLong(idIndex),
                            timestampMillis = cursor.getLong(tsIndex),
                            level = LogLevel.fromPriority(cursor.getInt(levelIndex)),
                            tag = cursor.getString(tagIndex),
                            message = cursor.getString(messageIndex),
                            thread = if (cursor.isNull(threadIndex)) null else cursor.getString(threadIndex),
                            stackTrace = if (cursor.isNull(traceIndex)) null else cursor.getString(traceIndex),
                        ),
                    )
                }
            }
        }
    }.getOrElse { emptyList() }

    fun clear() {
        runCatching { helper.writableDatabase.delete(TABLE, null, null) }
        _revision.value += 1
    }

    /** Deletes entries older than [cutoffMillis]; returns the number removed. */
    fun pruneOlderThan(cutoffMillis: Long): Int {
        val deleted = runCatching {
            helper.writableDatabase.delete(TABLE, "$COL_TS < ?", arrayOf(cutoffMillis.toString()))
        }.getOrDefault(0)
        if (deleted > 0) _revision.value += 1
        return deleted
    }

    /** Enforces the [RETENTION_DAYS]-day window relative to [nowMillis]. */
    fun pruneToRetention(nowMillis: Long = System.currentTimeMillis()): Int =
        pruneOlderThan(nowMillis - RETENTION_MILLIS)

    private fun maybePrune(nowMillis: Long) {
        if (nowMillis - lastPruneAtMillis < PRUNE_INTERVAL_MILLIS) return
        lastPruneAtMillis = nowMillis
        runCatching {
            helper.writableDatabase.delete(
                TABLE,
                "$COL_TS < ?",
                arrayOf((nowMillis - RETENTION_MILLIS).toString()),
            )
        }
    }

    private class OpenHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            // Allow concurrent reads (UI queries) while the writer thread inserts.
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_TS INTEGER NOT NULL, " +
                    "$COL_LEVEL INTEGER NOT NULL, " +
                    "$COL_TAG TEXT NOT NULL, " +
                    "$COL_MESSAGE TEXT NOT NULL, " +
                    "$COL_THREAD TEXT, " +
                    "$COL_TRACE TEXT)",
            )
            db.execSQL("CREATE INDEX idx_${TABLE}_ts ON $TABLE ($COL_TS)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        const val RETENTION_DAYS = 3
        val RETENTION_MILLIS = RETENTION_DAYS * 24L * 60L * 60L * 1000L
        private const val PRUNE_INTERVAL_MILLIS = 15L * 60L * 1000L
        private const val MAX_QUERY = 5000
        private const val DB_NAME = "diagnostic_logs.db"
        private const val DB_VERSION = 1
        private const val TABLE = "diagnostic_logs"
        private const val COL_ID = "id"
        private const val COL_TS = "ts"
        private const val COL_LEVEL = "level"
        private const val COL_TAG = "tag"
        private const val COL_MESSAGE = "message"
        private const val COL_THREAD = "thread"
        private const val COL_TRACE = "trace"
    }
}

/**
 * Formats diagnostic entries for on-screen display and for the shared/export text
 * file. The export carries app + device metadata up front so a developer or an LLM
 * reading it has the full runtime context needed to locate a problem.
 */
object DiagnosticLogFormatter {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val rangeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

    /** Wall-clock time only, e.g. `00:46:12.345` — used for compact log rows. */
    fun formatClock(millis: Long): String =
        clockFormatter.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Full date + time, e.g. `2026-08-25 00:46:12.345`. */
    fun formatDateTime(millis: Long): String =
        dateTimeFormatter.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Compact range label for the summary line, e.g. `Aug 24, 23:46 – Aug 25, 00:46`. */
    fun formatRange(startMillis: Long, endMillis: Long): String {
        val start = rangeFormatter.format(Instant.ofEpochMilli(startMillis).atZone(zone))
        val end = rangeFormatter.format(Instant.ofEpochMilli(endMillis).atZone(zone))
        return "$start \u2013 $end"
    }

    /** Renders one entry as a single export line (stack trace, if any, follows indented). */
    fun formatLine(entry: DiagnosticLogEntry): String {
        val thread = entry.thread?.let { " ($it)" }.orEmpty()
        val base = "${formatDateTime(entry.timestampMillis)} ${entry.level.short} " +
            "[${entry.tag}]$thread ${entry.message}"
        val trace = entry.stackTrace?.takeIf { it.isNotBlank() }?.let { stack ->
            "\n" + stack.trimEnd().lines().joinToString("\n") { "    $it" }
        }.orEmpty()
        return base + trace
    }

    /**
     * Builds the full shareable report for [entries] captured within
     * [startMillis]..[endMillis].
     */
    fun buildExport(
        entries: List<DiagnosticLogEntry>,
        startMillis: Long,
        endMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): String = buildString {
        appendLine("# Vobiz VoIP diagnostic logs")
        appendLine("# Exported: ${formatDateTime(nowMillis)} (${zone.id})")
        appendLine("# Range: ${formatDateTime(startMillis)} .. ${formatDateTime(endMillis)}")
        appendLine(
            "# App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} " +
                "(build ${BuildConfig.VERSION_CODE}${if (BuildConfig.DEBUG) ", debug" else ""})",
        )
        appendLine(
            "# Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        )
        appendLine("# Retention: ${DiagnosticLogStore.RETENTION_DAYS} days")
        appendLine("# Entries: ${entries.size}")
        appendLine("# Format: <yyyy-MM-dd HH:mm:ss.SSS> <LEVEL> [<tag>] (<thread>) <message>")
        appendLine("#".repeat(80))
        // Oldest-first reads naturally as a timeline for a human or an LLM.
        entries.asReversed().forEach { appendLine(formatLine(it)) }
    }
}
