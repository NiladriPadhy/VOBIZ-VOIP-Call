package com.enetro.vobizvoip.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enetro.vobizvoip.data.AppConfig
import com.enetro.vobizvoip.data.CallLogEntry
import com.enetro.vobizvoip.data.Recording
import com.enetro.vobizvoip.domain.BackendHealth
import com.enetro.vobizvoip.domain.CallCoordinator
import com.enetro.vobizvoip.domain.CallPhase
import com.enetro.vobizvoip.domain.CallUiState
import com.enetro.vobizvoip.signaling.RegistrationState
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging

@Composable
fun RootScreen(coordinator: CallCoordinator) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val callLog by coordinator.callLog.collectAsStateWithLifecycle()
    val recordings by coordinator.recordings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) pendingAudioAction?.invoke()
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

    val onSaveConfig: (AppConfig) -> Unit = { config ->
        coordinator.saveConfig(config)
        requestMissingPermissions(context, permissionLauncher)
        registerForPush(coordinator)
    }

    LaunchedEffect(Unit) {
        requestMissingPermissions(context, permissionLauncher)
    }

    // Re-assert this device's FCM installation ID on every launch once the
    // endpoint is configured. The backend keeps installation IDs in memory, so
    // without this a backend restart would silently stop waking the app for
    // inbound calls until the user re-saved settings.
    LaunchedEffect(state.config.isComplete) {
        if (state.config.isComplete) {
            registerForPush(coordinator)
        }
    }

    when {
        !state.config.isComplete -> OnboardingScreen(
            config = state.config,
            backendHealth = state.backendHealth,
            onReconnect = coordinator::reconnect,
            onCheckBackend = coordinator::checkBackendHealth,
            onSave = onSaveConfig,
        )

        state.phase == CallPhase.INCOMING -> IncomingCallScreen(
            number = state.remoteNumber,
            onAnswer = {
                withAudioPermission {
                    if (state.pendingCallId == null) {
                        coordinator.acceptIncoming()
                    } else {
                        coordinator.acceptPendingInbound()
                    }
                }
            },
            onDecline = {
                if (state.pendingCallId == null) {
                    coordinator.rejectIncoming()
                } else {
                    coordinator.declinePendingInbound()
                }
            },
        )

        state.phase in ACTIVE_PHASES -> ActiveCallScreen(
            state = state,
            onToggleMute = coordinator::setMuted,
            onToggleSpeaker = coordinator::setSpeakerEnabled,
            onSendDtmf = coordinator::sendDtmf,
            onHangup = coordinator::hangup,
        )

        else -> HomeScaffold(
            state = state,
            callLog = callLog,
            recordings = recordings,
            onReconnect = coordinator::reconnect,
            onCheckBackend = coordinator::checkBackendHealth,
            onPlaceCall = { destination -> withAudioPermission { coordinator.placeCall(destination) } },
            onClearCallLog = coordinator::clearCallLog,
            onRefreshRecordings = coordinator::refreshRecordings,
            onSaveConfig = onSaveConfig,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScaffold(
    state: CallUiState,
    callLog: List<CallLogEntry>,
    recordings: List<Recording>,
    onReconnect: () -> Unit,
    onCheckBackend: () -> Unit,
    onPlaceCall: (String) -> Unit,
    onClearCallLog: () -> Unit,
    onRefreshRecordings: () -> Unit,
    onSaveConfig: (AppConfig) -> Unit,
) {
    StatusBarColor(MaterialTheme.colorScheme.background, darkIcons = !isSystemInDarkTheme())

    val context = LocalContext.current
    var currentTab by rememberSaveable { mutableStateOf(HomeTab.KEYPAD) }
    var dialNumber by rememberSaveable { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val recordingPlayer = remember(
        state.config.backendUrl,
        state.config.backendToken,
        state.config.sipUsername,
    ) {
        RecordingPlayer(
            context = context,
            baseUrl = state.config.backendUrl,
            authToken = state.config.backendToken,
            endpoint = state.config.sipUsername,
        )
    }
    DisposableEffect(recordingPlayer) {
        onDispose { recordingPlayer.stop() }
    }
    LaunchedEffect(currentTab) {
        if (currentTab == HomeTab.RECENTS) onRefreshRecordings() else recordingPlayer.stop()
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { BrandTitle() },
                actions = {
                    ConnectionChip(state.registration, onReconnect, prefix = "SIP")
                    if (currentTab == HomeTab.RECENTS && callLog.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Clear recents",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                HomeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (currentTab) {
                HomeTab.KEYPAD -> DialerScreen(
                    number = dialNumber,
                    onNumberChange = { dialNumber = it },
                    canCall = state.registration == RegistrationState.REGISTERED,
                    onCall = onPlaceCall,
                )

                HomeTab.RECENTS -> CallLogScreen(
                    entries = callLog,
                    recordings = recordings,
                    player = recordingPlayer,
                    onDial = onPlaceCall,
                    onOpenInKeypad = { number ->
                        dialNumber = number
                        currentTab = HomeTab.KEYPAD
                    },
                )

                HomeTab.SETTINGS -> SettingsScreen(
                    initial = state.config,
                    registration = state.registration,
                    backendHealth = state.backendHealth,
                    onReconnect = onReconnect,
                    onCheckBackend = onCheckBackend,
                    onSave = onSaveConfig,
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear recents?") },
            text = { Text("This removes all call history from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearCallLog()
                        showClearDialog = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun OnboardingScreen(
    config: AppConfig,
    backendHealth: BackendHealth,
    onReconnect: () -> Unit,
    onCheckBackend: () -> Unit,
    onSave: (AppConfig) -> Unit,
) {
    StatusBarColor(MaterialTheme.colorScheme.background, darkIcons = !isSystemInDarkTheme())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 12.dp),
        ) {
            BrandTitle()
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Set up your endpoint",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Add your Vobiz SIP endpoint and backend details to start placing and receiving calls.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsScreen(
            initial = config,
            registration = RegistrationState.DISCONNECTED,
            backendHealth = backendHealth,
            onReconnect = onReconnect,
            onCheckBackend = onCheckBackend,
            onSave = onSave,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BrandTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Call,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Enetro",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private enum class HomeTab(val label: String, val icon: ImageVector) {
    KEYPAD("Keypad", Icons.Filled.Dialpad),
    RECENTS("Recents", Icons.Filled.History),
    SETTINGS("Settings", Icons.Filled.Settings),
}

private val ACTIVE_PHASES = setOf(
    CallPhase.OUTGOING,
    CallPhase.RINGING,
    CallPhase.CONNECTING,
    CallPhase.ACTIVE,
    CallPhase.ENDING,
)

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun requestMissingPermissions(
    context: Context,
    launcher: ActivityResultLauncher<Array<String>>,
) {
    val missing = requiredPermissions().filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }
    if (missing.isNotEmpty()) {
        launcher.launch(missing.toTypedArray())
    }
}

// Ensure FCM is registered and report the current Firebase Installation ID
// (the target the backend pushes inbound-call wake-ups to) to the backend.
// Guarded because Firebase throws if google-services.json is absent.
private fun registerForPush(coordinator: CallCoordinator) {
    runCatching {
        FirebaseMessaging.getInstance().register()
        FirebaseInstallations.getInstance().id.addOnSuccessListener { installationId ->
            coordinator.registerInstallation(installationId)
        }
    }
}
