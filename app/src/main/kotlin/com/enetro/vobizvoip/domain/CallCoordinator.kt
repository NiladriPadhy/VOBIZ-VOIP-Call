package com.enetro.vobizvoip.domain

import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import com.enetro.vobizvoip.data.AppConfig
import com.enetro.vobizvoip.data.BackendApi
import com.enetro.vobizvoip.data.CallDirection
import com.enetro.vobizvoip.data.CallLogEntry
import com.enetro.vobizvoip.data.CallLogStore
import com.enetro.vobizvoip.data.CallResult
import com.enetro.vobizvoip.data.CountryCodes
// Routes this file's existing Log.i/Log.w calls through the diagnostic facade so
// they are captured to the on-device log DB (when enabled) as well as logcat.
import com.enetro.vobizvoip.data.DiagnosticLog as Log
import com.enetro.vobizvoip.data.Recording
import com.enetro.vobizvoip.data.SecureConfigStore
import com.enetro.vobizvoip.media.WebRtcAudioSession
import com.enetro.vobizvoip.service.CallForegroundService
import com.enetro.vobizvoip.service.ConnectivityMonitorService
import com.enetro.vobizvoip.service.IncomingCallPresenter
import com.enetro.vobizvoip.service.IncomingCallWake
import com.enetro.vobizvoip.telecom.IncomingCallAccount
import com.enetro.vobizvoip.telephony.CallStateMonitor
import com.enetro.vobizvoip.telephony.CellularCallState
import com.enetro.vobizvoip.telephony.TelephonyInfo
import com.enetro.vobizvoip.signaling.RegistrationState
import com.enetro.vobizvoip.signaling.SipClient
import com.enetro.vobizvoip.signaling.SipEvent
import com.enetro.vobizvoip.signaling.SipMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.webrtc.PeerConnection
import java.util.UUID

enum class CallPhase {
    IDLE,
    OUTGOING,
    RINGING,
    INCOMING,
    CONNECTING,
    ACTIVE,
    ENDING,
    FAILED,
}

enum class BackendHealthState { UNKNOWN, CHECKING, ONLINE, OFFLINE }

data class BackendHealth(
    val state: BackendHealthState = BackendHealthState.UNKNOWN,
    val firebaseReady: Boolean = false,
    val pendingCalls: Int = 0,
    val detail: String? = null,
    val checkedAtMillis: Long? = null,
)

data class CallUiState(
    val config: AppConfig = AppConfig(),
    val registration: RegistrationState = RegistrationState.DISCONNECTED,
    val phase: CallPhase = CallPhase.IDLE,
    val remoteNumber: String = "",
    val muted: Boolean = false,
    val speakerEnabled: Boolean = false,
    val error: String? = null,
    val pendingCallId: String? = null,
    val connectedAtMillis: Long? = null,
    val backendHealth: BackendHealth = BackendHealth(),
)

private data class ActiveCallRecord(
    val number: String,
    val direction: CallDirection,
    val startedAt: Long,
    var connectedAt: Long? = null,
)

/** A dial requested from outside the UI (e.g. a `tel:` intent from another app). */
data class PendingDial(val number: String, val autoCall: Boolean)

class CallCoordinator(
    private val context: Context,
    private val configStore: SecureConfigStore,
    private val sipClient: SipClient,
    private val webRtc: WebRtcAudioSession,
    private val backendApi: BackendApi,
    private val callLogStore: CallLogStore,
    private val callStateMonitor: CallStateMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(CallUiState(config = configStore.load()))
    private var incomingInvite: SipMessage? = null
    private var incomingRingtone: Ringtone? = null
    private var inboundStatusJob: Job? = null
    private var healthMonitorJob: Job? = null
    private var healthCheckInFlight = false
    private var lastInstallationId: String? = null
    private var activeCallRecord: ActiveCallRecord? = null
    private var mutedForCellularCall = false
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { onNetworkAvailable() }
        }
    }
    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    private val _pendingDial = MutableStateFlow<PendingDial?>(null)

    val state: StateFlow<CallUiState> = _state
    val callLog: StateFlow<List<CallLogEntry>> = callLogStore.entries
    val recordings: StateFlow<List<Recording>> = _recordings

    /** A dial routed in from an intent, consumed by the UI once handled. */
    val pendingDial: StateFlow<PendingDial?> = _pendingDial

    /** Live cellular (native phone) call state, for UI/diagnostics. */
    val cellularCallState: StateFlow<CellularCallState> = callStateMonitor.state

    fun clearCallLog() = callLogStore.clear()

    fun refreshRecordings() {
        val config = _state.value.config
        if (!config.isComplete) return
        scope.launch {
            runCatching { backendApi.fetchRecordings(config) }
                .onSuccess { _recordings.value = it }
                .onFailure { Log.w("VobizCall", "Fetching recordings failed: ${it.message}") }
        }
    }

    private fun pushRecordingPreference() {
        val config = _state.value.config
        if (!config.isComplete) return
        scope.launch {
            runCatching { backendApi.setRecordingPreference(config) }
                .onFailure { Log.w("VobizCall", "Recording preference push failed: ${it.message}") }
        }
    }

    init {
        scope.launch {
            sipClient.registrationState.collect { registration ->
                _state.update { it.copy(registration = registration) }
            }
        }
        scope.launch {
            sipClient.events.collect(::handleSipEvent)
        }
        scope.launch {
            callStateMonitor.state.collect(::onCellularStateChanged)
        }
        scope.launch {
            var lastPhase: CallPhase? = null
            _state.collect { current ->
                if (current.phase != lastPhase) {
                    Log.i(
                        "VobizCall",
                        "phase $lastPhase -> ${current.phase}; remote=${current.remoteNumber}; " +
                            "pending=${current.pendingCallId}; connectedAt=${current.connectedAtMillis}",
                    )
                    lastPhase = current.phase
                }
            }
        }
        if (_state.value.config.isComplete) {
            sipClient.connect(_state.value.config)
            refreshRecordings()
            pushRecordingPreference()
            startConnectivityMonitoring()
        } else if (_state.value.config.backendUrl.trim().startsWith("http")) {
            startConnectivityMonitoring()
        }
        registerNetworkCallback()
    }

    fun saveConfig(config: AppConfig) {
        val normalized = config.copy(
            backendUrl = config.backendUrl.trim().removeSuffix("/"),
            registrarUrl = config.registrarUrl.trim(),
            sipDomain = config.sipDomain.trim(),
            sipUsername = config.sipUsername.trim(),
            callerId = config.callerId.trim(),
        )
        configStore.save(normalized)
        _state.update { it.copy(config = normalized, phase = CallPhase.IDLE, error = null) }
        sipClient.connect(normalized)
        pushRecordingPreference()
        startConnectivityMonitoring()
    }

    fun reconnect() {
        val config = _state.value.config
        if (config.isComplete) {
            _state.update { it.copy(phase = CallPhase.IDLE, error = null) }
            sipClient.connect(config)
        }
    }

    fun checkBackendHealth() {
        scope.launch { probeBackendHealth(silent = false) }
    }

    /** Reconnect SIP and re-check backend health, used by the status notification Retry action. */
    fun retryConnectivity() {
        reconnect()
        checkBackendHealth()
    }

    /** Starts or resumes health polling, SIP/backend reconnect, and the status notification. */
    fun ensureConnectivityMonitoring() {
        val config = _state.value.config
        if (config.isComplete || config.backendUrl.trim().startsWith("http")) {
            startConnectivityMonitoring()
        }
    }

    private fun startConnectivityMonitoring() {
        startMonitorService()
        startHealthMonitor()
    }

    private fun startMonitorService() {
        val intent = Intent(context, ConnectivityMonitorService::class.java).apply {
            action = ConnectivityMonitorService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Exception) {
            Log.w("VobizCall", "Unable to start connectivity monitor: ${error.message}")
        }
    }

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            var offlineStreak = 0
            while (isActive) {
                val previous = _state.value.backendHealth.state
                val health = probeBackendHealth(silent = true)
                if (health.state == BackendHealthState.ONLINE && previous == BackendHealthState.OFFLINE) {
                    recoverBackendSession()
                }
                if (health.state == BackendHealthState.OFFLINE) {
                    offlineStreak += 1
                } else {
                    offlineStreak = 0
                }
                delay(healthRetryDelayMs(health.state, offlineStreak))
            }
        }
    }

    private suspend fun probeBackendHealth(silent: Boolean): BackendHealth {
        val config = _state.value.config
        if (!config.backendUrl.trim().startsWith("http")) {
            val health = BackendHealth(
                state = BackendHealthState.OFFLINE,
                detail = "Set a backend URL in settings",
                checkedAtMillis = System.currentTimeMillis(),
            )
            _state.update { it.copy(backendHealth = health) }
            return health
        }
        if (healthCheckInFlight) {
            return _state.value.backendHealth
        }
        healthCheckInFlight = true
        if (!silent) {
            _state.update {
                it.copy(backendHealth = it.backendHealth.copy(state = BackendHealthState.CHECKING))
            }
        }
        val health = try {
            val report = withTimeout(BACKEND_HEALTH_TIMEOUT_MS) { backendApi.checkHealth(config) }
            BackendHealth(
                state = BackendHealthState.ONLINE,
                firebaseReady = report.firebaseReady,
                pendingCalls = report.pendingCalls,
                checkedAtMillis = System.currentTimeMillis(),
            )
        } catch (timeout: TimeoutCancellationException) {
            BackendHealth(
                state = BackendHealthState.OFFLINE,
                detail = "No response from backend (timed out)",
                checkedAtMillis = System.currentTimeMillis(),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w("VobizCall", "Backend health check failed: ${error.message}")
            BackendHealth(
                state = BackendHealthState.OFFLINE,
                detail = healthErrorMessage(error),
                checkedAtMillis = System.currentTimeMillis(),
            )
        } finally {
            healthCheckInFlight = false
        }
        _state.update { it.copy(backendHealth = health) }
        return health
    }

    private fun recoverBackendSession() {
        val installationId = lastInstallationId
        if (installationId != null) {
            registerInstallation(installationId)
        }
        pushRecordingPreference()
        refreshRecordings()
    }

    private fun onNetworkAvailable() {
        val config = _state.value.config
        if (config.isComplete) {
            val registration = sipClient.registrationState.value
            if (
                registration == RegistrationState.DISCONNECTED ||
                registration == RegistrationState.FAILED
            ) {
                sipClient.connect(config)
            }
        }
        if (config.backendUrl.trim().startsWith("http")) {
            scope.launch { probeBackendHealth(silent = true) }
        }
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }.onFailure {
            Log.w("VobizCall", "Unable to watch network changes: ${it.message}")
        }
    }

    private fun healthRetryDelayMs(state: BackendHealthState, offlineStreak: Int): Long {
        if (state == BackendHealthState.ONLINE) return HEALTH_POLL_OK_MS
        val shift = (offlineStreak - 1).coerceIn(0, 3)
        return (HEALTH_RETRY_INITIAL_MS shl shift).coerceAtMost(HEALTH_RETRY_MAX_MS)
    }

    private fun healthErrorMessage(error: Throwable): String {
        val message = error.message
        return if (!message.isNullOrBlank() && message.contains("HTTP")) {
            message
        } else {
            "Backend unreachable — check the tunnel and server"
        }
    }

    fun placeCall(destination: String) {
        val normalized = PhoneNumberNormalizer.normalize(destination, effectiveCallingCode())
        if (!E164.matches(normalized)) {
            fail("Enter a valid phone number to call")
            return
        }
        activeCallRecord = ActiveCallRecord(
            number = normalized,
            direction = CallDirection.OUTGOING,
            startedAt = System.currentTimeMillis(),
        )
        startOutgoing(normalized, prepareBackend = true)
    }

    /**
     * The E.164 calling code used to normalize dialed numbers. The SIM region
     * wins when a SIM is present; otherwise the user's configured default (from
     * Settings) is used, finally falling back to [CountryCodes.DEFAULT_ISO].
     */
    fun effectiveCallingCode(): String {
        val candidates = listOfNotNull(
            TelephonyInfo.simCountryIso(context),
            _state.value.config.defaultCountryIso.ifBlank { null },
            CountryCodes.DEFAULT_ISO,
        )
        return candidates.firstNotNullOfOrNull { CountryCodes.callingCodeForIso(it) } ?: "91"
    }

    /** Records a dial routed in from an intent so the UI can prefill or auto-call. */
    fun requestDial(number: String, autoCall: Boolean) {
        _pendingDial.value = PendingDial(number, autoCall)
    }

    fun consumePendingDial() {
        _pendingDial.value = null
    }

    /** Begins observing the native cellular call state (needs READ_PHONE_STATE). */
    fun startCellularMonitoring() = callStateMonitor.start()

    private fun onCellularStateChanged(cellular: CellularCallState) {
        when (cellular) {
            CellularCallState.OFFHOOK -> {
                if (_state.value.phase == CallPhase.ACTIVE && !_state.value.muted) {
                    mutedForCellularCall = true
                    setMuted(true)
                    Log.i("VobizCall", "Native cellular call off-hook; auto-muted VoIP mic")
                }
            }
            CellularCallState.IDLE -> {
                if (mutedForCellularCall) {
                    mutedForCellularCall = false
                    setMuted(false)
                    Log.i("VobizCall", "Native cellular call ended; restored VoIP mic")
                }
            }
            CellularCallState.RINGING -> Unit
        }
    }

    fun acceptIncoming() {
        val invite = incomingInvite ?: return
        stopIncomingRingtone()
        _state.update { it.copy(phase = CallPhase.CONNECTING, error = null) }
        scope.launch {
            runCatching {
                val answer = webRtc.answerOffer(invite.body, iceServers())
                sipClient.acceptIncoming(answer)
                // The answering side is connected once the 200 OK is sent. Some
                // WSS/PSTN paths never deliver the in-dialog ACK back to the app,
                // so we must not gate the active state on receiving CallAccepted.
                markCallActive()
            }.onFailure { fail(it.message ?: "Unable to answer call") }
        }
    }

    fun rejectIncoming() {
        stopIncomingRingtone()
        sipClient.rejectIncoming()
        endLocally(CallResult.DECLINED)
    }

    fun hangup() {
        val previousPhase = _state.value.phase
        _state.update { it.copy(phase = CallPhase.ENDING) }
        if (previousPhase == CallPhase.OUTGOING || previousPhase == CallPhase.RINGING) {
            sipClient.cancelOutgoing()
        } else {
            sipClient.hangup()
        }
        endLocally()
    }

    fun setMuted(muted: Boolean) {
        webRtc.setMuted(muted)
        _state.update { it.copy(muted = muted) }
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        webRtc.setSpeakerEnabled(enabled)
        _state.update { it.copy(speakerEnabled = enabled) }
    }

    fun sendDtmf(digit: Char) {
        if (!webRtc.sendDtmf(digit.toString())) {
            sipClient.sendDtmfInfo(digit)
        }
    }

    fun showPendingInbound(pendingCallId: String, caller: String?) {
        val remote = caller ?: "Unknown caller"
        activeCallRecord = ActiveCallRecord(
            number = remote,
            direction = CallDirection.INCOMING,
            startedAt = System.currentTimeMillis(),
        )
        _state.update {
            it.copy(
                phase = CallPhase.INCOMING,
                remoteNumber = remote,
                pendingCallId = pendingCallId,
                error = null,
            )
        }
        IncomingCallPresenter.keepAwakeForIncoming(context, remote, pendingCallId)
        ensureSipConnected()
        startIncomingRingtone()
        startInboundStatusPolling()
    }

    fun acceptPendingInbound() {
        val pendingId = _state.value.pendingCallId ?: return
        stopIncomingRingtone()
        ensureSipConnected()
        scope.launch {
            runCatching {
                val config = _state.value.config
                withTimeout(20_000) {
                    sipClient.registrationState.first {
                        it == RegistrationState.REGISTERED || it == RegistrationState.FAILED
                    }
                }
                check(sipClient.registrationState.value == RegistrationState.REGISTERED) {
                    "SIP registration failed"
                }
                _state.update { it.copy(phase = CallPhase.CONNECTING) }
                val instruction = backendApi.acceptPending(config, pendingId)
                startOutgoing(instruction.joinNumber, prepareBackend = false)
            }.onFailure { fail(it.message ?: "Unable to join incoming call") }
        }
    }

    fun declinePendingInbound() {
        val pendingId = _state.value.pendingCallId ?: return
        stopIncomingRingtone()
        scope.launch {
            runCatching { backendApi.declinePending(_state.value.config, pendingId) }
            endLocally(CallResult.DECLINED)
        }
    }

    fun registerInstallation(installationId: String) {
        lastInstallationId = installationId
        val config = _state.value.config
        if (!config.isComplete) return
        scope.launch {
            runCatching { backendApi.registerInstallation(config, installationId) }
                .onFailure { fail("FCM registration failed: ${it.message}") }
        }
    }

    private fun startOutgoing(destination: String, prepareBackend: Boolean) {
        if (sipClient.registrationState.value != RegistrationState.REGISTERED) {
            fail("SIP endpoint is not registered")
            return
        }
        _state.update {
            it.copy(
                phase = CallPhase.OUTGOING,
                remoteNumber = destination,
                pendingCallId = null,
                error = null,
                connectedAtMillis = null,
            )
        }
        scope.launch {
            runCatching {
                if (prepareBackend) {
                    backendApi.prepareOutbound(_state.value.config, destination)
                }
                val offer = webRtc.createOffer(iceServers())
                sipClient.invite(destination, offer)
            }.onFailure { fail(it.message ?: "Unable to start call") }
        }
    }

    private suspend fun handleSipEvent(event: SipEvent) {
        Log.i("VobizCall", "sipEvent ${event::class.simpleName}; phase=${_state.value.phase}")
        when (event) {
            is SipEvent.IncomingInvite -> {
                if (_state.value.phase != CallPhase.IDLE) {
                    sipClient.rejectIncoming()
                    return
                }
                incomingInvite = event.request
                activeCallRecord = ActiveCallRecord(
                    number = event.caller,
                    direction = CallDirection.INCOMING,
                    startedAt = System.currentTimeMillis(),
                )
                _state.update {
                    it.copy(
                        phase = CallPhase.INCOMING,
                        remoteNumber = event.caller,
                        error = null,
                    )
                }
                IncomingCallPresenter.keepAwakeForIncoming(context, event.caller)
                startIncomingRingtone()
                startInboundStatusPolling()
            }
            SipEvent.RemoteRinging -> _state.update { it.copy(phase = CallPhase.RINGING) }
            is SipEvent.CallAccepted -> {
                runCatching {
                    event.remoteSdp?.let { webRtc.applyAnswer(it) }
                }.onFailure {
                    fail(it.message ?: "Remote media negotiation failed")
                    return
                }
                markCallActive()
            }
            SipEvent.CallEnded -> endLocally()
            is SipEvent.Failure -> {
                val inCall = _state.value.phase in CALL_PHASES
                if (inCall) {
                    val status = event.statusCode?.let { " (SIP $it)" }.orEmpty()
                    fail(event.message + status)
                } else {
                    Log.w("VobizCall", "SIP failure while idle: ${event.message}")
                }
            }
        }
    }

    private fun markCallActive() {
        if (_state.value.phase == CallPhase.ACTIVE) return
        val connectedAt = System.currentTimeMillis()
        activeCallRecord?.connectedAt = connectedAt
        _state.update { it.copy(phase = CallPhase.ACTIVE, connectedAtMillis = connectedAt) }
        IncomingCallAccount.setActive()
        IncomingCallWake.release()
        startCallService()
    }

    private fun ensureSipConnected() {
        val config = _state.value.config
        if (!config.isComplete) return
        when (sipClient.registrationState.value) {
            RegistrationState.REGISTERED,
            RegistrationState.CONNECTING,
            RegistrationState.REGISTERING,
            -> return
            else -> sipClient.connect(config)
        }
    }

    private fun startCallService() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, CallForegroundService::class.java).apply {
                action = CallForegroundService.ACTION_START
                putExtra(CallForegroundService.EXTRA_REMOTE, _state.value.remoteNumber)
            },
        )
    }

    private fun stopCallService() {
        context.startService(
            Intent(context, CallForegroundService::class.java).apply {
                action = CallForegroundService.ACTION_STOP
            },
        )
    }

    private fun endLocally(result: CallResult? = null) {
        inboundStatusJob?.cancel()
        inboundStatusJob = null
        stopIncomingRingtone()
        webRtc.close()
        IncomingCallPresenter.finished(context, _state.value.pendingCallId)
        stopCallService()
        incomingInvite = null
        recordCall(result)
        _state.update {
            it.copy(
                phase = CallPhase.IDLE,
                remoteNumber = "",
                muted = false,
                speakerEnabled = false,
                pendingCallId = null,
                connectedAtMillis = null,
            )
        }
    }

    private fun fail(message: String) {
        inboundStatusJob?.cancel()
        inboundStatusJob = null
        stopIncomingRingtone()
        webRtc.close()
        IncomingCallPresenter.finished(context, _state.value.pendingCallId)
        stopCallService()
        recordCall(CallResult.FAILED)
        _state.update { it.copy(phase = CallPhase.FAILED, error = message, connectedAtMillis = null) }
    }

    private fun recordCall(explicitResult: CallResult?) {
        val record = activeCallRecord ?: return
        activeCallRecord = null
        val connectedAt = record.connectedAt
        val durationSeconds = if (connectedAt != null) {
            ((System.currentTimeMillis() - connectedAt) / 1000).coerceAtLeast(0)
        } else {
            0L
        }
        val result = explicitResult ?: when {
            connectedAt != null -> CallResult.COMPLETED
            record.direction == CallDirection.INCOMING -> CallResult.MISSED
            else -> CallResult.CANCELED
        }
        callLogStore.add(
            CallLogEntry(
                id = UUID.randomUUID().toString(),
                number = record.number,
                direction = record.direction,
                result = result,
                startedAt = record.startedAt,
                durationSeconds = durationSeconds,
            ),
        )
        // A completed call is recorded server-side; the RecordStop webhook lands
        // a few seconds after hangup, so refresh shortly afterwards to surface it.
        if (result == CallResult.COMPLETED) {
            scope.launch {
                delay(RECORDING_REFRESH_DELAY_MS)
                refreshRecordings()
            }
        }
    }

    private fun startInboundStatusPolling() {
        inboundStatusJob?.cancel()
        inboundStatusJob = scope.launch {
            while (
                _state.value.phase == CallPhase.INCOMING ||
                _state.value.phase == CallPhase.CONNECTING ||
                _state.value.phase == CallPhase.ACTIVE
            ) {
                delay(INBOUND_STATUS_POLL_MS)
                val status = runCatching {
                    backendApi.inboundCallStatus(_state.value.config)
                }.getOrNull() ?: continue
                if (status.known && !status.active) {
                    sipClient.abandonCall()
                    endLocally()
                    return@launch
                }
            }
        }
    }

    private fun startIncomingRingtone() {
        if (incomingRingtone?.isPlaying == true) return
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            RingtoneManager.getRingtone(context, uri)?.also { ringtone ->
                ringtone.isLooping = true
                ringtone.play()
                incomingRingtone = ringtone
            }
        }
    }

    private fun stopIncomingRingtone() {
        incomingRingtone?.stop()
        incomingRingtone = null
    }

    private fun iceServers(): List<PeerConnection.IceServer> =
        DEFAULT_STUN_SERVERS.map { url ->
            PeerConnection.IceServer.builder(url).createIceServer()
        }

    private companion object {
        val E164 = Regex("^\\+[1-9]\\d{7,14}$")
        val CALL_PHASES = setOf(
            CallPhase.OUTGOING,
            CallPhase.RINGING,
            CallPhase.INCOMING,
            CallPhase.CONNECTING,
            CallPhase.ACTIVE,
            CallPhase.ENDING,
        )
        const val INBOUND_STATUS_POLL_MS = 1_000L
        const val RECORDING_REFRESH_DELAY_MS = 8_000L
        const val BACKEND_HEALTH_TIMEOUT_MS = 6_000L
        const val HEALTH_POLL_OK_MS = 30_000L
        const val HEALTH_RETRY_INITIAL_MS = 5_000L
        const val HEALTH_RETRY_MAX_MS = 30_000L
        val DEFAULT_STUN_SERVERS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
            "stun:global.stun.twilio.com:3478",
        )
    }
}
