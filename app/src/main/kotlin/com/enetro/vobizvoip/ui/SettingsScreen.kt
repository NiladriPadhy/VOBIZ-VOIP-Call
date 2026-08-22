package com.enetro.vobizvoip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.enetro.vobizvoip.data.AppConfig
import com.enetro.vobizvoip.signaling.RegistrationState

@Composable
fun SettingsScreen(
    initial: AppConfig,
    registration: RegistrationState,
    onReconnect: () -> Unit,
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
    var turnUrl by rememberSaveable(initial) { mutableStateOf(initial.turnUrl) }
    var turnUsername by rememberSaveable(initial) { mutableStateOf(initial.turnUsername) }
    var turnPassword by rememberSaveable(initial) { mutableStateOf(initial.turnPassword) }
    var recordingEnabled by rememberSaveable(initial) { mutableStateOf(initial.recordingEnabled) }

    val isValid = username.isNotBlank() &&
        password.isNotBlank() &&
        registrar.startsWith("wss://") &&
        backend.startsWith("https://") &&
        backendToken.isNotBlank() &&
        callerId.startsWith("+")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        ConnectionCard(registration = registration, onReconnect = onReconnect)

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

        SectionCard(title = "TURN RELAY (RECOMMENDED)") {
            SettingsField("TURN URL", turnUrl) { turnUrl = it }
            SettingsField("TURN username", turnUsername) { turnUsername = it }
            SettingsSecretField("TURN password", turnPassword) { turnPassword = it }
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
                        turnUrl = turnUrl,
                        turnUsername = turnUsername,
                        turnPassword = turnPassword,
                        recordingEnabled = recordingEnabled,
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
private fun ConnectionCard(registration: RegistrationState, onReconnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Connection",
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
