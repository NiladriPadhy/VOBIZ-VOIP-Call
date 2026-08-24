package com.enetro.vobizvoip.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enetro.vobizvoip.data.DiagnosticLogEntry
import com.enetro.vobizvoip.data.DiagnosticLogFormatter
import com.enetro.vobizvoip.data.DiagnosticLogStore
import com.enetro.vobizvoip.data.LogLevel
import com.enetro.vobizvoip.ui.theme.AnswerGreen
import com.enetro.vobizvoip.ui.theme.DeclineRed
import com.enetro.vobizvoip.ui.theme.WarningAmber
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HOUR_MILLIS = 60L * 60L * 1000L

private enum class LogRangePreset(val label: String) {
    LAST_1_HOUR("Last 1 hr"),
    LAST_2_HOURS("Last 2 hrs"),
    CUSTOM("Custom"),
}

/**
 * Diagnostic logs viewer rendered as a continuous, logcat-style console: entries
 * stream oldest-to-newest in a dense monospace list that auto-tails to the newest
 * line as logs arrive. A preset/custom time range scopes what is shown; share,
 * clear, and 3-day retention are surfaced in the controls.
 */
@Composable
fun DiagnosticLogsScreen(
    store: DiagnosticLogStore,
    loggingEnabled: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by store.revision.collectAsStateWithLifecycle()

    var preset by rememberSaveable { mutableStateOf(LogRangePreset.LAST_1_HOUR) }
    var customStart by rememberSaveable {
        mutableStateOf(System.currentTimeMillis() - HOUR_MILLIS)
    }
    var customEnd by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var manualRefresh by remember { mutableStateOf(0) }

    // Newest-first from the store; reversed to chronological for the console.
    var entries by remember { mutableStateOf<List<DiagnosticLogEntry>>(emptyList()) }
    val ordered = remember(entries) { entries.asReversed() }
    var rangeStart by remember { mutableStateOf(0L) }
    var rangeEnd by remember { mutableStateOf(0L) }
    var showClearDialog by remember { mutableStateOf(false) }

    // Enforce the 3-day retention window once when the screen opens.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { store.pruneToRetention() }
    }

    // Re-query whenever the range changes or a new entry is written (revision),
    // giving the console its live, continuously-updating feed.
    LaunchedEffect(preset, customStart, customEnd, revision, manualRefresh) {
        val now = System.currentTimeMillis()
        val (start, end) = when (preset) {
            LogRangePreset.LAST_1_HOUR -> (now - HOUR_MILLIS) to now
            LogRangePreset.LAST_2_HOURS -> (now - 2 * HOUR_MILLIS) to now
            LogRangePreset.CUSTOM ->
                minOf(customStart, customEnd) to maxOf(customStart, customEnd)
        }
        rangeStart = start
        rangeEnd = end
        entries = withContext(Dispatchers.IO) { store.query(start, end) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!loggingEnabled) {
                LoggingDisabledCard(onOpenSettings = onOpenSettings)
            }
            RangeSelector(selected = preset, onSelect = { preset = it })
            if (preset == LogRangePreset.CUSTOM) {
                CustomRangeCard(
                    start = customStart,
                    end = customEnd,
                    onStartChange = { customStart = it },
                    onEndChange = { customEnd = it },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (entries.size == 1) "1 entry" else "${entries.size} entries",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${DiagnosticLogFormatter.formatRange(rangeStart, rangeEnd)}  •  " +
                            "kept ${DiagnosticLogStore.RETENTION_DAYS} days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { manualRefresh += 1 }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                val snapshot = store.query(rangeStart, rangeEnd)
                                val text = DiagnosticLogFormatter.buildExport(
                                    snapshot,
                                    rangeStart,
                                    rangeEnd,
                                )
                                writeExportFile(context, text)
                            }
                            shareExport(context, uri)
                        }
                    },
                    enabled = entries.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Share logs",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { showClearDialog = true },
                    enabled = entries.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = "Clear all logs",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (ordered.isEmpty()) {
                EmptyLogs(loggingEnabled = loggingEnabled)
            } else {
                LogConsole(entries = ordered)
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all logs?") },
            text = { Text("This permanently deletes every stored diagnostic log on this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) { store.clear() }
                        }
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The continuous console. Renders entries chronologically in monospace and tails
 * to the newest line, pausing auto-scroll if the user scrolls up (a "Latest"
 * button returns to the live tail).
 */
@Composable
private fun LogConsole(entries: List<DiagnosticLogEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var tail by remember { mutableStateOf(true) }

    // Resume/suspend tailing based on where the user settles after scrolling.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress) {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                tail = last == null || last.index >= info.totalItemsCount - 1
            }
        }
    }

    // Jump to the newest line whenever new entries arrive and we are tailing.
    LaunchedEffect(entries) {
        if (tail && entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(items = entries, key = { it.id }) { entry ->
                LogConsoleRow(entry)
            }
        }
        if (!tail && entries.isNotEmpty()) {
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        listState.scrollToItem(entries.lastIndex)
                        tail = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Latest")
            }
        }
    }
}

@Composable
private fun LogConsoleRow(entry: DiagnosticLogEntry) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tagColor = MaterialTheme.colorScheme.primary
    val levelColor = levelColor(entry.level)
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = muted)) {
                append(DiagnosticLogFormatter.formatClock(entry.timestampMillis))
            }
            append("  ")
            withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold)) {
                append(entry.level.short)
            }
            append(' ')
            withStyle(SpanStyle(color = tagColor)) {
                append(entry.tag)
            }
            append("  ")
            withStyle(SpanStyle(color = onSurface)) {
                append(entry.message)
            }
            entry.stackTrace?.takeIf { it.isNotBlank() }?.let { trace ->
                append('\n')
                withStyle(SpanStyle(color = muted)) { append(trace.trimEnd()) }
            }
        },
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
    )
}

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> DeclineRed
    LogLevel.WARN -> WarningAmber
    LogLevel.INFO -> AnswerGreen
    LogLevel.DEBUG -> MaterialTheme.colorScheme.primary
    LogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun LoggingDisabledCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Diagnostic logging is off",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "Turn it on in Settings to start capturing new logs. Existing logs " +
                    "below are still viewable and shareable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onOpenSettings) { Text("Open settings") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(selected: LogRangePreset, onSelect: (LogRangePreset) -> Unit) {
    val options = LogRangePreset.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(option.label)
            }
        }
    }
}

@Composable
private fun CustomRangeCard(
    start: Long,
    end: Long,
    onStartChange: (Long) -> Unit,
    onEndChange: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DateTimeField(label = "From", millis = start, onChange = onStartChange)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DateTimeField(label = "To", millis = end, onChange = onEndChange)
            if (start >= end) {
                Text(
                    text = "Start must be before end.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeField(label: String, millis: Long, onChange: (Long) -> Unit) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateUtc by remember { mutableStateOf(localDateToUtcMillis(millis)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDatePicker = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = DiagnosticLogFormatter.formatDateTime(millis),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = "Edit $label date and time",
            tint = MaterialTheme.colorScheme.primary,
        )
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = localDateToUtcMillis(millis),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDateUtc = dateState.selectedDateMillis ?: localDateToUtcMillis(millis)
                        showDatePicker = false
                        showTimePicker = true
                    },
                ) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTimePicker) {
        val time = remember(millis) { localTimeOf(millis) }
        val timeState = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = false,
        )
        TimePickerDialog(
            onCancel = { showTimePicker = false },
            onConfirm = {
                onChange(combineDateAndTime(pendingDateUtc, timeState.hour, timeState.minute))
                showTimePicker = false
            },
        ) {
            TimePicker(state = timeState)
        }
    }
}

@Composable
private fun TimePickerDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
        text = { content() },
    )
}

@Composable
private fun EmptyLogs(loggingEnabled: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = "No logs in this range",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = if (loggingEnabled) {
                "Waiting for activity — new logs stream in here as they happen."
            } else {
                "Enable diagnostic logging in Settings, then reproduce the issue."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Sharing + date helpers -------------------------------------------------

private fun writeExportFile(context: Context, text: String): Uri {
    val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
    // Keep only the latest export around.
    dir.listFiles()?.forEach { runCatching { it.delete() } }
    val file = File(dir, "vobiz-diagnostic-logs.txt")
    file.writeText(text)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun shareExport(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Vobiz VoIP diagnostic logs")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "Share diagnostic logs")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

/** UTC midnight for the local calendar day of [millis] (what Material date pickers expect). */
private fun localDateToUtcMillis(millis: Long): Long =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

private fun localTimeOf(millis: Long): LocalTime =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()

/** Combines a UTC-midnight date (from the date picker) with a local wall-clock time. */
private fun combineDateAndTime(dateUtcMillis: Long, hour: Int, minute: Int): Long =
    Instant.ofEpochMilli(dateUtcMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atTime(hour, minute)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
