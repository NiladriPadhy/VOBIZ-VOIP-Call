package com.enetro.vobizvoip.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.enetro.vobizvoip.data.Contact
import com.enetro.vobizvoip.data.ContactsRepository
import com.enetro.vobizvoip.data.DiagnosticLog
import com.enetro.vobizvoip.data.DiagnosticLogStore
import com.enetro.vobizvoip.data.Recording
import com.enetro.vobizvoip.data.filterByQuery
import com.enetro.vobizvoip.domain.BackendHealth
import com.enetro.vobizvoip.domain.CallCoordinator
import com.enetro.vobizvoip.domain.CallPhase
import com.enetro.vobizvoip.domain.CallUiState
import com.enetro.vobizvoip.domain.PendingDial
import com.enetro.vobizvoip.signaling.RegistrationState
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

@Composable
fun RootScreen(
    coordinator: CallCoordinator,
    contactsRepository: ContactsRepository,
    diagnosticLogStore: DiagnosticLogStore,
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val callLog by coordinator.callLog.collectAsStateWithLifecycle()
    val recordings by coordinator.recordings.collectAsStateWithLifecycle()
    val contacts by contactsRepository.contacts.collectAsStateWithLifecycle()
    val pendingDial by coordinator.pendingDial.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) pendingAudioAction?.invoke()
        pendingAudioAction = null
        applyGrantedPlatformPermissions(context, coordinator, contactsRepository, scope)
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
        // Apply the diagnostics toggle immediately so subsequent events persist.
        DiagnosticLog.enabled = config.diagnosticLoggingEnabled
        coordinator.saveConfig(config)
        requestMissingPermissions(context, permissionLauncher)
        registerForPush(coordinator)
    }
    val onPlaceCall: (String) -> Unit = { destination ->
        withAudioPermission { coordinator.placeCall(destination) }
    }

    LaunchedEffect(Unit) {
        requestMissingPermissions(context, permissionLauncher)
        applyGrantedPlatformPermissions(context, coordinator, contactsRepository, scope)
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

        else -> MainShell(
            state = state,
            callLog = callLog,
            recordings = recordings,
            contacts = contacts,
            contactsHasPermission = contactsRepository.hasPermission(),
            diagnosticLogStore = diagnosticLogStore,
            pendingDial = pendingDial,
            onConsumePendingDial = coordinator::consumePendingDial,
            onRequestContactsPermission = {
                permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            },
            onReconnect = coordinator::reconnect,
            onCheckBackend = coordinator::checkBackendHealth,
            onPlaceCall = onPlaceCall,
            onClearCallLog = coordinator::clearCallLog,
            onRefreshRecordings = coordinator::refreshRecordings,
            onSaveConfig = onSaveConfig,
        )
    }
}

private enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    KEYPAD("Keypad", Icons.Filled.Dialpad),
}

private enum class DrawerScreen { CONTACTS, SETTINGS, DIAGNOSTIC_LOGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    state: CallUiState,
    callLog: List<CallLogEntry>,
    recordings: List<Recording>,
    contacts: List<Contact>,
    contactsHasPermission: Boolean,
    diagnosticLogStore: DiagnosticLogStore,
    pendingDial: PendingDial?,
    onConsumePendingDial: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onReconnect: () -> Unit,
    onCheckBackend: () -> Unit,
    onPlaceCall: (String) -> Unit,
    onClearCallLog: () -> Unit,
    onRefreshRecordings: () -> Unit,
    onSaveConfig: (AppConfig) -> Unit,
) {
    StatusBarColor(MaterialTheme.colorScheme.background, darkIcons = !isSystemInDarkTheme())

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var currentTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var dialNumber by rememberSaveable { mutableStateOf("") }
    var drawerScreen by rememberSaveable { mutableStateOf<DrawerScreen?>(null) }
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
        if (currentTab == MainTab.HOME) onRefreshRecordings() else recordingPlayer.stop()
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(pendingDial) {
        val dial = pendingDial ?: return@LaunchedEffect
        drawerScreen = null
        if (dial.autoCall && dial.number.isNotBlank()) {
            onPlaceCall(dial.number)
        } else {
            dialNumber = dial.number
            currentTab = MainTab.KEYPAD
        }
        onConsumePendingDial()
    }

    BackHandler(enabled = drawerScreen != null) { drawerScreen = null }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerScreen == null,
        drawerContent = {
            AppDrawer(
                onContacts = {
                    scope.launch { drawerState.close() }
                    drawerScreen = DrawerScreen.CONTACTS
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    drawerScreen = DrawerScreen.SETTINGS
                },
                onDiagnostics = {
                    scope.launch { drawerState.close() }
                    drawerScreen = DrawerScreen.DIAGNOSTIC_LOGS
                },
                onClearHistory = {
                    scope.launch { drawerState.close() }
                    showClearDialog = true
                },
            )
        },
    ) {
        when (drawerScreen) {
            DrawerScreen.CONTACTS -> OverlayScaffold("Contacts", onBack = { drawerScreen = null }) { modifier ->
                ContactsScreen(
                    contacts = contacts,
                    hasPermission = contactsHasPermission,
                    onRequestPermission = onRequestContactsPermission,
                    onCall = onPlaceCall,
                    modifier = modifier,
                )
            }

            DrawerScreen.SETTINGS -> OverlayScaffold("Settings", onBack = { drawerScreen = null }) { modifier ->
                SettingsScreen(
                    initial = state.config,
                    registration = state.registration,
                    backendHealth = state.backendHealth,
                    onReconnect = onReconnect,
                    onCheckBackend = onCheckBackend,
                    onSave = onSaveConfig,
                    modifier = modifier,
                )
            }

            DrawerScreen.DIAGNOSTIC_LOGS ->
                OverlayScaffold("Diagnostic logs", onBack = { drawerScreen = null }) { modifier ->
                    DiagnosticLogsScreen(
                        store = diagnosticLogStore,
                        loggingEnabled = state.config.diagnosticLoggingEnabled,
                        onOpenSettings = { drawerScreen = DrawerScreen.SETTINGS },
                        modifier = modifier,
                    )
                }

            null -> Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        MainTab.entries.forEach { tab ->
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (state.registration != RegistrationState.REGISTERED) {
                        ConnectionBanner(state.registration, onReconnect)
                    }
                    Box(Modifier.weight(1f)) {
                        when (currentTab) {
                            MainTab.HOME -> HomeScreen(
                                entries = callLog,
                                recordings = recordings,
                                contacts = contacts,
                                player = recordingPlayer,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onCall = onPlaceCall,
                                onOpenInKeypad = { number ->
                                    dialNumber = number
                                    currentTab = MainTab.KEYPAD
                                },
                            )

                            MainTab.KEYPAD -> DialerScreen(
                                number = dialNumber,
                                onNumberChange = { dialNumber = it },
                                canCall = state.registration == RegistrationState.REGISTERED,
                                onCall = onPlaceCall,
                                matches = if (dialNumber.isBlank()) {
                                    emptyList()
                                } else {
                                    contacts.filterByQuery(dialNumber)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear call history?") },
            text = { Text("This will delete all calls from your history") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearCallLog()
                        showClearDialog = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverlayScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        content(
            Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
private fun ConnectionBanner(state: RegistrationState, onReconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionChip(state = state, onReconnect = onReconnect, prefix = "SIP")
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
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.READ_PHONE_STATE)
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

/** Starts cellular monitoring and loads contacts once their permissions exist. */
private fun applyGrantedPlatformPermissions(
    context: Context,
    coordinator: CallCoordinator,
    contactsRepository: ContactsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        coordinator.startCellularMonitoring()
    }
    if (contactsRepository.hasPermission()) {
        scope.launch { contactsRepository.refresh() }
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
