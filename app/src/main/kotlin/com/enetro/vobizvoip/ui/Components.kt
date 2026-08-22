package com.enetro.vobizvoip.ui

import android.app.Activity
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.enetro.vobizvoip.signaling.RegistrationState
import com.enetro.vobizvoip.ui.theme.AnswerGreen
import com.enetro.vobizvoip.ui.theme.AvatarColors
import com.enetro.vobizvoip.ui.theme.DeclineRed
import com.enetro.vobizvoip.ui.theme.WarningAmber

/** Sets the system status-bar color and icon appearance for the current screen. */
@Composable
@Suppress("DEPRECATION")
fun StatusBarColor(color: Color, darkIcons: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val argb = color.toArgb()
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = argb
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkIcons
        }
    }
}

fun avatarColorFor(key: String): Color {
    if (key.isBlank()) return AvatarColors.first()
    val index = (key.hashCode() and 0x7fffffff) % AvatarColors.size
    return AvatarColors[index]
}

fun initialsFor(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.none { it.isLetter() }) return ""
    return trimmed
        .split(' ', '.', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull { char -> char.isLetter() }?.uppercaseChar() }
        .joinToString("")
}

/** Presents a phone number/caller for display, normalizing empty/anonymous values. */
fun displayNumber(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.isEmpty() -> "Unknown"
        trimmed.equals("Unknown caller", ignoreCase = true) -> "Unknown"
        trimmed.equals("anonymous", ignoreCase = true) -> "Unknown"
        else -> trimmed
    }
}

fun formatCallDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

@Composable
fun Avatar(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    background: Color = avatarColorFor(label),
) {
    val initials = initialsFor(label)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (initials.isNotEmpty()) {
            Text(
                text = initials,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.38f).sp,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** Big circular button used for answer / decline / hang up actions. */
@Composable
fun RoundCallButton(
    icon: ImageVector,
    background: Color,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    contentColor: Color = Color.White,
    enabled: Boolean = true,
) {
    val resolvedBackground = if (enabled) background else background.copy(alpha = 0.4f)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(resolvedBackground)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

/** Generic status pill (colored dot + label) matching [ConnectionChip]'s look. */
@Composable
fun StatusChip(label: String, dotColor: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact registration status chip; tap to reconnect when offline. */
@Composable
fun ConnectionChip(state: RegistrationState, onReconnect: () -> Unit, prefix: String? = null) {
    val (statusLabel, dotColor) = when (state) {
        RegistrationState.REGISTERED -> "Connected" to AnswerGreen
        RegistrationState.CONNECTING -> "Connecting" to WarningAmber
        RegistrationState.REGISTERING -> "Registering" to WarningAmber
        RegistrationState.DISCONNECTED -> "Offline" to MaterialTheme.colorScheme.onSurfaceVariant
        RegistrationState.FAILED -> "Retry" to DeclineRed
    }
    val actionable =
        state == RegistrationState.DISCONNECTED || state == RegistrationState.FAILED
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = actionable, onClick = onReconnect)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (prefix != null) "$prefix · $statusLabel" else statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
