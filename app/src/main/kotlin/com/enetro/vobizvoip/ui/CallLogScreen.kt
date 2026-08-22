package com.enetro.vobizvoip.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enetro.vobizvoip.data.CallDirection
import com.enetro.vobizvoip.data.CallLogEntry
import com.enetro.vobizvoip.data.CallResult
import com.enetro.vobizvoip.data.Recording
import com.enetro.vobizvoip.ui.theme.AnswerGreen
import kotlin.math.abs

@Composable
fun CallLogScreen(
    entries: List<CallLogEntry>,
    recordings: List<Recording>,
    player: RecordingPlayer,
    onDial: (String) -> Unit,
    onOpenInKeypad: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        EmptyRecents(modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(entries, key = { it.id }) { entry ->
            CallLogRow(
                entry = entry,
                recording = recordingForEntry(entry, recordings),
                player = player,
                onOpen = { onOpenInKeypad(entry.number) },
                onDial = { onDial(entry.number) },
            )
        }
    }
}

@Composable
private fun CallLogRow(
    entry: CallLogEntry,
    recording: Recording?,
    player: RecordingPlayer,
    onOpen: () -> Unit,
    onDial: () -> Unit,
) {
    val missed = entry.result == CallResult.MISSED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(label = displayNumber(entry.number), size = 46.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = displayNumber(entry.number),
                style = MaterialTheme.typography.titleMedium,
                color = if (missed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = directionIcon(entry),
                    contentDescription = null,
                    tint = directionTint(entry),
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = subtitleFor(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recording != null) {
                RecordingButton(recordingId = recording.id, player = player)
            }
            if (entry.number.startsWith("+")) {
                IconButton(onClick = onDial) {
                    Icon(
                        imageVector = Icons.Filled.Call,
                        contentDescription = "Call ${displayNumber(entry.number)}",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingButton(recordingId: String, player: RecordingPlayer) {
    val preparing = player.preparingId == recordingId
    val playing = player.playingId == recordingId
    IconButton(onClick = { player.toggle(recordingId) }) {
        when {
            preparing -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            playing -> Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = "Stop recording",
                tint = MaterialTheme.colorScheme.primary,
            )
            else -> Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play recording",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EmptyRecents(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "No recent calls",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Calls you make and receive will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun subtitleFor(entry: CallLogEntry): String {
    val label = when (entry.result) {
        CallResult.COMPLETED -> if (entry.direction == CallDirection.INCOMING) "Incoming" else "Outgoing"
        CallResult.MISSED -> "Missed"
        CallResult.DECLINED -> "Declined"
        CallResult.CANCELED -> "Cancelled"
        CallResult.FAILED -> "Failed"
    }
    val time = relativeTime(entry.startedAt)
    return if (entry.result == CallResult.COMPLETED && entry.durationSeconds > 0) {
        "$label · ${formatCallDuration(entry.durationSeconds)} · $time"
    } else {
        "$label · $time"
    }
}

private fun directionIcon(entry: CallLogEntry): ImageVector = when {
    entry.result == CallResult.MISSED -> Icons.AutoMirrored.Filled.CallMissed
    entry.direction == CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
    else -> Icons.AutoMirrored.Filled.CallMade
}

@Composable
private fun directionTint(entry: CallLogEntry) = when (entry.result) {
    CallResult.MISSED, CallResult.FAILED -> MaterialTheme.colorScheme.error
    CallResult.DECLINED, CallResult.CANCELED -> MaterialTheme.colorScheme.onSurfaceVariant
    CallResult.COMPLETED -> if (entry.direction == CallDirection.INCOMING) {
        AnswerGreen
    } else {
        MaterialTheme.colorScheme.primary
    }
}

private const val RECORDING_MATCH_TOLERANCE_MS = 5 * 60_000L

/**
 * Recordings are produced server-side and can't carry the app's local call id,
 * so match by direction + phone number (last 10 digits) and the closest start
 * time within a tolerance window.
 */
private fun recordingForEntry(entry: CallLogEntry, recordings: List<Recording>): Recording? {
    val entryDigits = entry.number.filter(Char::isDigit).takeLast(10)
    if (entryDigits.isEmpty()) return null
    return recordings
        .filter {
            it.direction == entry.direction &&
                it.number.filter(Char::isDigit).takeLast(10) == entryDigits
        }
        .minByOrNull { abs(it.startedAtEpochMs - entry.startedAt) }
        ?.takeIf { abs(it.startedAtEpochMs - entry.startedAt) <= RECORDING_MATCH_TOLERANCE_MS }
}
