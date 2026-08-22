package com.enetro.vobizvoip.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enetro.vobizvoip.domain.CallPhase
import com.enetro.vobizvoip.domain.CallUiState
import com.enetro.vobizvoip.ui.theme.AnswerGreen
import com.enetro.vobizvoip.ui.theme.CallBackdropBottom
import com.enetro.vobizvoip.ui.theme.CallBackdropTop
import com.enetro.vobizvoip.ui.theme.DeclineRed
import kotlinx.coroutines.delay

@Composable
fun IncomingCallScreen(
    number: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    CallScreenContainer {
        Spacer(Modifier.weight(0.9f))
        Text(
            text = "Incoming call",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(28.dp))
        PulsingAvatar(number)
        Spacer(Modifier.height(24.dp))
        Text(
            text = displayNumber(number),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            LabeledCallAction("Decline", Icons.Filled.CallEnd, DeclineRed, onDecline)
            LabeledCallAction("Answer", Icons.Filled.Call, AnswerGreen, onAnswer)
        }
        Spacer(Modifier.height(44.dp))
    }
}

@Composable
fun ActiveCallScreen(
    state: CallUiState,
    onToggleMute: (Boolean) -> Unit,
    onToggleSpeaker: (Boolean) -> Unit,
    onSendDtmf: (Char) -> Unit,
    onHangup: () -> Unit,
) {
    var showKeypad by remember { mutableStateOf(false) }
    val controlsEnabled = state.phase == CallPhase.ACTIVE
    CallScreenContainer {
        Spacer(Modifier.weight(0.7f))
        Text(
            text = statusLabelFor(state.phase),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(24.dp))
        Avatar(label = displayNumber(state.remoteNumber), size = 108.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = displayNumber(state.remoteNumber),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        CallTimer(state)
        Spacer(Modifier.weight(1f))
        if (showKeypad && controlsEnabled) {
            InCallDtmfPad(onDigit = onSendDtmf)
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = { showKeypad = false }) {
                Text("Hide keypad", color = Color.White.copy(alpha = 0.85f))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallToggle(
                    label = "Mute",
                    icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    active = state.muted,
                    enabled = controlsEnabled,
                ) { onToggleMute(!state.muted) }
                CallToggle(
                    label = "Keypad",
                    icon = Icons.Filled.Dialpad,
                    active = false,
                    enabled = controlsEnabled,
                ) { showKeypad = true }
                CallToggle(
                    label = "Speaker",
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    active = state.speakerEnabled,
                    enabled = controlsEnabled,
                ) { onToggleSpeaker(!state.speakerEnabled) }
            }
        }
        Spacer(Modifier.height(28.dp))
        RoundCallButton(
            icon = Icons.Filled.CallEnd,
            background = DeclineRed,
            contentDescription = "Hang up",
            onClick = onHangup,
            size = 74.dp,
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun CallScreenContainer(content: @Composable ColumnScope.() -> Unit) {
    StatusBarColor(CallBackdropTop, darkIcons = false)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallBackdropTop, CallBackdropBottom))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
private fun PulsingAvatar(number: String) {
    val transition = rememberInfiniteTransition(label = "incoming-pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "scale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = ringAlpha)),
        )
        Avatar(label = displayNumber(number), size = 112.dp)
    }
}

@Composable
private fun LabeledCallAction(
    label: String,
    icon: ImageVector,
    background: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundCallButton(
            icon = icon,
            background = background,
            contentDescription = label,
            onClick = onClick,
            size = 74.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun CallToggle(
    label: String,
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> Color.White.copy(alpha = 0.08f)
        active -> Color.White
        else -> Color.White.copy(alpha = 0.15f)
    }
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.4f)
        active -> CallBackdropBottom
        else -> Color.White
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun CallTimer(state: CallUiState) {
    val connectedAt = state.connectedAtMillis
    if (state.phase == CallPhase.ACTIVE && connectedAt != null) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(connectedAt) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
        val elapsed = ((now - connectedAt) / 1000).coerceAtLeast(0)
        Text(
            text = formatCallDuration(elapsed),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}

private val dtmfRows = listOf("123", "456", "789", "*0#")

@Composable
private fun InCallDtmfPad(onDigit: (Char) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        dtmfRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { digit ->
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { onDigit(digit) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = digit.toString(),
                            fontSize = 24.sp,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private fun statusLabelFor(phase: CallPhase): String = when (phase) {
    CallPhase.OUTGOING -> "Calling…"
    CallPhase.RINGING -> "Ringing…"
    CallPhase.CONNECTING -> "Connecting…"
    CallPhase.ACTIVE -> "In call"
    CallPhase.ENDING -> "Ending…"
    else -> "Call"
}
