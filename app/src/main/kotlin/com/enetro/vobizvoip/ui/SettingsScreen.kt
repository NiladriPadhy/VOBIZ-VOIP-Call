package com.enetro.vobizvoip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.enetro.vobizvoip.data.AppConfig
import com.enetro.vobizvoip.data.CountryCodes
import com.enetro.vobizvoip.data.DiagnosticLogStore
import com.enetro.vobizvoip.domain.BackendHealth
import com.enetro.vobizvoip.domain.BackendHealthState
import com.enetro.vobizvoip.service.InboundCallGuards
import com.enetro.vobizvoip.signaling.RegistrationState
import com.enetro.vobizvoip.ui.theme.AnswerGreen
import com.enetro.vobizvoip.ui.theme.DeclineRed
import com.enetro.vobizvoip.ui.theme.WarningAmber

@Composable
fun SettingsScreen(
    initial: AppConfig,
    registration: RegistrationState,
    backendHealth: BackendHealth,
    onReconnect: () -> Unit,
    onCheckBackend: () -> Unit,
    onSave: (AppConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by rememberSaveable(initial) { mutableStateOf(initial.sipUsername) }
    var password by rememberSaveable(initial) { mutableStateOf(initial.sipPassword) }
    var registrar by rememberSaveable(initial) { mutableStateOf(initial.registrarUrl) }
    var domain by rememberSaveable(initial) { mutableStateOf(initial.sipDomain) }
    var backend by rememberSaveable(initial) { mutableStateOf(initial.backendUrl) }
    var backendToken by rememberSaveable(initial) { mutableStateOf(initial.backendToken) }
    var callerId by rememberSaveable(initial) { mutableStateOf(initial.callerId) }
    var recordingEnabled by rememberSaveable(initial) { mutableStateOf(initial.recordingEnabled) }
    var diagnosticLoggingEnabled by rememberSaveable(initial) {
        mutableStateOf(initial.diagnosticLoggingEnabled)
    }
    var countryIso by rememberSaveable(initial) { mutableStateOf(initial.defaultCountryIso) }

    val isValid = username.isNotBlank() &&
        password.isNotBlank() &&
        registrar.startsWith("wss://") &&
        backend.startsWith("https://") &&
        backendToken.isNotBlank() &&
        callerId.startsWith("+")

    LaunchedEffect(initial.backendUrl) {
        if (initial.backendUrl.startsWith("http")) onCheckBackend()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        SectionCard(title = "STATUS") {
            SipStatusRow(registration = registration, onReconnect = onReconnect)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BackendStatusRow(health = backendHealth, onRefresh = onCheckBackend)
        }

        InboundSleepCard()

        SectionCard(title = "SIP ENDPOINT") {
            SettingsField("SIP username", username) { username = it }
            SettingsSecretField("SIP password", password) { password = it }
            SettingsField("Registrar WSS URL", registrar) { registrar = it }
            SettingsField("SIP domain", domain) { domain = it }
        }

        SectionCard(title = "BACKEND") {
            SettingsField("Public backend HTTPS URL", backend) { backend = it }
            SettingsSecretField("POC device token", backendToken) { backendToken = it }
            SettingsField("Vobiz caller ID (E.164)", callerId) { callerId = it }
        }

        SectionCard(title = "DIALING") {
            CountryPickerRow(selectedIso = countryIso) { countryIso = it }
        }

        SectionCard(title = "CALL RECORDING") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Record calls",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Record each connected call and play it back from Recents.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = recordingEnabled,
                    onCheckedChange = { recordingEnabled = it },
                )
            }
        }

        SectionCard(title = "DIAGNOSTICS") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Diagnostic logs",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Record detailed activity on this device to help track down " +
                            "issues. Stored for ${DiagnosticLogStore.RETENTION_DAYS} days, then " +
                            "removed. View or share them from the Diagnostic logs menu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = diagnosticLoggingEnabled,
                    onCheckedChange = { diagnosticLoggingEnabled = it },
                )
            }
        }

        Text(
            text = "Credentials are encrypted with the Android Keystore and never leave this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Button(
            onClick = {
                onSave(
                    AppConfig(
                        sipUsername = username,
                        sipPassword = password,
                        registrarUrl = registrar,
                        sipDomain = domain,
                        backendUrl = backend,
                        backendToken = backendToken,
                        callerId = callerId,
                        recordingEnabled = recordingEnabled,
                        diagnosticLoggingEnabled = diagnosticLoggingEnabled,
                        defaultCountryIso = countryIso,
                    ),
                )
            },
            enabled = isValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Save and connect")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InboundSleepCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(InboundCallGuards.status(context)) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            status = InboundCallGuards.status(context)
        }
    }
    SectionCard(title = "INBOUND WHILE ASLEEP") {
        InboundGuardRow(
            title = "Notifications",
            ok = status.notificationsEnabled,
            detailOn = "Allowed — incoming calls can alert",
            detailOff = "Off — enable so inbound calls can ring",
            onClick = { InboundCallGuards.openNotificationSettings(context) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        InboundGuardRow(
            title = "Full-screen incoming calls",
            ok = status.fullScreenIntentAllowed,
            detailOn = "Allowed — lock screen can show the call",
            detailOff = "Off — tap to allow the incoming-call screen",
            onClick = { InboundCallGuards.openFullScreenIntentSettings(context) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        InboundGuardRow(
            title = "Battery usage",
            ok = status.batteryUnrestricted,
            detailOn = "Unrestricted — sleep will not delay the push",
            detailOff = "Restricted — tap to allow unrestricted battery",
            onClick = { InboundCallGuards.openBatterySettings(context) },
        )
    }
}

@Composable
private fun InboundGuardRow(
    title: String,
    ok: Boolean,
    detailOn: String,
    detailOff: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (ok) detailOn else detailOff,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(
            label = if (ok) "Ready" else "Fix",
            dotColor = if (ok) AnswerGreen else WarningAmber,
        )
    }
}

@Composable
private fun SipStatusRow(registration: RegistrationState, onReconnect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "SIP endpoint",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when (registration) {
                    RegistrationState.REGISTERED -> "Registered and ready for calls"
                    RegistrationState.CONNECTING -> "Connecting to registrar…"
                    RegistrationState.REGISTERING -> "Registering endpoint…"
                    RegistrationState.DISCONNECTED -> "Disconnected"
                    RegistrationState.FAILED -> "Registration failed"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ConnectionChip(state = registration, onReconnect = onReconnect)
        Spacer(Modifier.width(4.dp))
        if (registration == RegistrationState.CONNECTING ||
            registration == RegistrationState.REGISTERING
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onReconnect) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Reconnect SIP",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BackendStatusRow(health: BackendHealth, onRefresh: () -> Unit) {
    val (label, dotColor) = when (health.state) {
        BackendHealthState.ONLINE -> "Online" to AnswerGreen
        BackendHealthState.OFFLINE -> "Offline" to DeclineRed
        BackendHealthState.CHECKING -> "Checking…" to WarningAmber
        BackendHealthState.UNKNOWN -> "Unknown" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val detail = when (health.state) {
        BackendHealthState.ONLINE -> {
            val firebase = if (health.firebaseReady) "Firebase ready" else "Firebase off"
            "$firebase · ${health.pendingCalls} pending"
        }
        BackendHealthState.OFFLINE -> health.detail?.takeIf { it.isNotBlank() }
            ?: "Backend unreachable"
        BackendHealthState.CHECKING -> "Contacting backend…"
        BackendHealthState.UNKNOWN -> "Tap refresh to check the backend"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Backend",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(label = label, dotColor = dotColor)
        Spacer(Modifier.width(4.dp))
        if (health.state == BackendHealthState.CHECKING) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Check backend",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CountryPickerRow(selectedIso: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = CountryCodes.countryForIso(selectedIso)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Default country",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Used to normalize dialed numbers when no SIM is present.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = selected?.label ?: "Auto",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Auto (detect from SIM)") },
                onClick = {
                    onSelect("")
                    expanded = false
                },
            )
            CountryCodes.all.sortedBy { it.name }.forEach { country ->
                DropdownMenuItem(
                    text = { Text(country.label) },
                    onClick = {
                        onSelect(country.iso)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsSecretField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
