package com.enetro.vobizvoip

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enetro.vobizvoip.data.AppConfig
import com.enetro.vobizvoip.domain.CallCoordinator
import com.enetro.vobizvoip.domain.CallPhase
import com.enetro.vobizvoip.domain.CallUiState
import com.enetro.vobizvoip.signaling.RegistrationState
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {
    private val coordinator: CallCoordinator
        get() = (application as VobizApplication).container.coordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            MaterialTheme {
                VobizApp(coordinator)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SHOW_PENDING -> showPending(intent)
            ACTION_ANSWER_PENDING -> {
                showPending(intent)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    coordinator.acceptPendingInbound()
                }
            }
            ACTION_DECLINE_PENDING -> {
                showPending(intent)
                coordinator.declinePendingInbound()
                cancelIncomingNotification(intent)
            }
            ACTION_HANGUP -> coordinator.hangup()
        }
    }

    private fun showPending(intent: Intent) {
        val pendingId = intent.getStringExtra(EXTRA_PENDING_CALL_ID) ?: return
        coordinator.showPendingInbound(
            pendingCallId = pendingId,
            caller = intent.getStringExtra(EXTRA_CALLER),
        )
    }

    private fun cancelIncomingNotification(intent: Intent) {
        intent.getStringExtra(EXTRA_PENDING_CALL_ID)?.let {
            getSystemService(NotificationManager::class.java).cancel(it.hashCode())
        }
    }

    companion object {
        const val ACTION_SHOW_PENDING = "com.enetro.vobizvoip.action.SHOW_PENDING"
        const val ACTION_ANSWER_PENDING = "com.enetro.vobizvoip.action.ANSWER_PENDING"
        const val ACTION_DECLINE_PENDING = "com.enetro.vobizvoip.action.DECLINE_PENDING"
        const val ACTION_HANGUP = "com.enetro.vobizvoip.action.HANGUP"
        const val EXTRA_PENDING_CALL_ID = "pendingCallId"
        const val EXTRA_CALLER = "caller"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VobizApp(coordinator: CallCoordinator) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettings by rememberSaveable { mutableStateOf(!state.config.isComplete) }
    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (
            results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingAudioAction?.invoke()
        }
        pendingAudioAction = null
    }
    val withAudioPermission: (() -> Unit) -> Unit = { action ->
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingAudioAction = action
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vobiz VoIP POC") },
                actions = {
                    if (
                        state.config.isComplete &&
                        (state.phase == CallPhase.IDLE || state.phase == CallPhase.FAILED)
                    ) {
                        TextButton(onClick = { showSettings = !showSettings }) {
                            Text(if (showSettings) "Dialer" else "Settings")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            RegistrationBanner(state.registration, coordinator::reconnect)
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            when {
                showSettings || !state.config.isComplete -> ConfigScreen(
                    initial = state.config,
                    onSave = {
                        coordinator.saveConfig(it)
                        showSettings = false
                        permissionLauncher.launch(requiredPermissions())
                        runCatching {
                            FirebaseMessaging.getInstance().register()
                        }
                    },
                )
                state.phase == CallPhase.INCOMING -> IncomingCallScreen(
                    state = state,
                    onAnswer = {
                        withAudioPermission {
                            if (state.pendingCallId == null) {
                                coordinator.acceptIncoming()
                            } else {
                                coordinator.acceptPendingInbound()
                            }
                        }
                    },
                    onReject = {
                        if (state.pendingCallId == null) {
                            coordinator.rejectIncoming()
                        } else {
                            coordinator.declinePendingInbound()
                        }
                    },
                )
                state.phase in ACTIVE_PHASES -> ActiveCallScreen(state, coordinator)
                else -> DialerScreen(
                    enabled = state.registration == RegistrationState.REGISTERED,
                    onCall = {
                        val destination = it
                        withAudioPermission { coordinator.placeCall(destination) }
                    },
                )
            }
        }
    }
}

@Composable
private fun RegistrationBanner(
    state: RegistrationState,
    onReconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (state) {
                RegistrationState.DISCONNECTED -> "Disconnected"
                RegistrationState.CONNECTING -> "Connecting"
                RegistrationState.REGISTERING -> "Registering"
                RegistrationState.REGISTERED -> "Ready"
                RegistrationState.FAILED -> "Registration failed"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        if (state == RegistrationState.DISCONNECTED || state == RegistrationState.FAILED) {
            TextButton(onClick = onReconnect) { Text("Retry") }
        }
    }
    HorizontalDivider()
}

@Composable
private fun ConfigScreen(
    initial: AppConfig,
    onSave: (AppConfig) -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Endpoint configuration", style = MaterialTheme.typography.titleLarge)
        Text(
            "Account Auth ID and Auth Token belong only in the backend. Enter the dedicated SIP endpoint credentials here.",
            style = MaterialTheme.typography.bodySmall,
        )
        ConfigField("SIP username", username) { username = it }
        SecretField("SIP password", password) { password = it }
        ConfigField("Registrar WSS URL", registrar) { registrar = it }
        ConfigField("SIP domain", domain) { domain = it }
        ConfigField("Public backend HTTPS URL", backend) { backend = it }
        SecretField("POC device token", backendToken) { backendToken = it }
        ConfigField("Vobiz caller ID (E.164)", callerId) { callerId = it }
        ConfigField("TURN URL (recommended)", turnUrl) { turnUrl = it }
        ConfigField("TURN username", turnUsername) { turnUsername = it }
        SecretField("TURN password", turnPassword) { turnPassword = it }
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
                    ),
                )
            },
            enabled = username.isNotBlank() &&
                password.isNotBlank() &&
                registrar.startsWith("wss://") &&
                backend.startsWith("https://") &&
                backendToken.isNotBlank() &&
                callerId.startsWith("+"),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save and connect")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConfigField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecretField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DialerScreen(
    enabled: Boolean,
    onCall: (String) -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Outbound call", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text("Destination number") },
            supportingText = { Text("Use E.164 format, for example +919876543210") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onCall(destination) },
            enabled = enabled && destination.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Call")
        }
    }
}

@Composable
private fun IncomingCallScreen(
    state: CallUiState,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Incoming call", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(state.remoteNumber, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            OutlinedButton(onClick = onReject) { Text("Decline") }
            Button(onClick = onAnswer) { Text("Answer") }
        }
    }
}

@Composable
private fun ActiveCallScreen(
    state: CallUiState,
    coordinator: CallCoordinator,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            when (state.phase) {
                CallPhase.OUTGOING -> "Calling"
                CallPhase.RINGING -> "Ringing"
                CallPhase.CONNECTING -> "Connecting"
                CallPhase.ACTIVE -> "Connected"
                CallPhase.ENDING -> "Ending"
                else -> "Call"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))
        Text(state.remoteNumber, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { coordinator.setMuted(!state.muted) },
                enabled = state.phase == CallPhase.ACTIVE,
            ) {
                Text(if (state.muted) "Unmute" else "Mute")
            }
            OutlinedButton(
                onClick = { coordinator.setSpeakerEnabled(!state.speakerEnabled) },
                enabled = state.phase == CallPhase.ACTIVE,
            ) {
                Text(if (state.speakerEnabled) "Earpiece" else "Speaker")
            }
        }
        if (state.phase == CallPhase.ACTIVE) {
            Spacer(Modifier.height(20.dp))
            DtmfPad(coordinator::sendDtmf)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = coordinator::hangup) { Text("Hang up") }
    }
}

@Composable
private fun DtmfPad(onDigit: (Char) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    OutlinedButton(onClick = { onDigit(digit) }) {
                        Text(digit.toString())
                    }
                }
            }
        }
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private val ACTIVE_PHASES = setOf(
    CallPhase.OUTGOING,
    CallPhase.RINGING,
    CallPhase.CONNECTING,
    CallPhase.ACTIVE,
    CallPhase.ENDING,
)
