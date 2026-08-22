package com.enetro.vobizvoip.domain

import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import androidx.core.content.ContextCompat
import com.enetro.vobizvoip.data.AppConfig
import com.enetro.vobizvoip.data.BackendApi
import com.enetro.vobizvoip.data.SecureConfigStore
import com.enetro.vobizvoip.media.WebRtcAudioSession
import com.enetro.vobizvoip.service.CallForegroundService
import com.enetro.vobizvoip.signaling.RegistrationState
import com.enetro.vobizvoip.signaling.SipClient
import com.enetro.vobizvoip.signaling.SipEvent
import com.enetro.vobizvoip.signaling.SipMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.webrtc.PeerConnection

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

data class CallUiState(
    val config: AppConfig = AppConfig(),
    val registration: RegistrationState = RegistrationState.DISCONNECTED,
    val phase: CallPhase = CallPhase.IDLE,
    val remoteNumber: String = "",
    val muted: Boolean = false,
    val speakerEnabled: Boolean = false,
    val error: String? = null,
    val pendingCallId: String? = null,
)

class CallCoordinator(
    private val context: Context,
    private val configStore: SecureConfigStore,
    private val sipClient: SipClient,
    private val webRtc: WebRtcAudioSession,
    private val backendApi: BackendApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(CallUiState(config = configStore.load()))
    private var incomingInvite: SipMessage? = null
    private var incomingRingtone: Ringtone? = null
    private var inboundStatusJob: Job? = null

    val state: StateFlow<CallUiState> = _state

    init {
        scope.launch {
            sipClient.registrationState.collect { registration ->
                _state.update { it.copy(registration = registration) }
            }
        }
        scope.launch {
            sipClient.events.collect(::handleSipEvent)
        }
        if (_state.value.config.isComplete) {
            sipClient.connect(_state.value.config)
        }
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
    }

    fun reconnect() {
        val config = _state.value.config
        if (config.isComplete) {
            _state.update { it.copy(phase = CallPhase.IDLE, error = null) }
            sipClient.connect(config)
        }
    }

    fun placeCall(destination: String) {
        val normalized = destination.trim().replace(" ", "")
        if (!E164.matches(normalized)) {
            fail("Enter a destination in E.164 format, for example +919876543210")
            return
        }
        startOutgoing(normalized, prepareBackend = true)
    }

    fun acceptIncoming() {
        val invite = incomingInvite ?: return
        stopIncomingRingtone()
        _state.update { it.copy(phase = CallPhase.CONNECTING, error = null) }
        scope.launch {
            runCatching {
                val answer = webRtc.answerOffer(invite.body, iceServers(_state.value.config))
                sipClient.acceptIncoming(answer)
            }.onFailure { fail(it.message ?: "Unable to answer call") }
        }
    }

    fun rejectIncoming() {
        stopIncomingRingtone()
        sipClient.rejectIncoming()
        endLocally()
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
        _state.update {
            it.copy(
                phase = CallPhase.INCOMING,
                remoteNumber = caller ?: "Unknown caller",
                pendingCallId = pendingCallId,
                error = null,
            )
        }
        startIncomingRingtone()
    }

    fun acceptPendingInbound() {
        val pendingId = _state.value.pendingCallId ?: return
        stopIncomingRingtone()
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
            endLocally()
        }
    }

    fun registerInstallation(installationId: String) {
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
            )
        }
        scope.launch {
            runCatching {
                if (prepareBackend) {
                    backendApi.prepareOutbound(_state.value.config, destination)
                }
                val offer = webRtc.createOffer(iceServers(_state.value.config))
                sipClient.invite(destination, offer)
            }.onFailure { fail(it.message ?: "Unable to start call") }
        }
    }

    private suspend fun handleSipEvent(event: SipEvent) {
        when (event) {
            is SipEvent.IncomingInvite -> {
                if (_state.value.phase != CallPhase.IDLE) {
                    sipClient.rejectIncoming()
                    return
                }
                incomingInvite = event.request
                _state.update {
                    it.copy(
                        phase = CallPhase.INCOMING,
                        remoteNumber = event.caller,
                        error = null,
                    )
                }
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
                _state.update { it.copy(phase = CallPhase.ACTIVE) }
                startCallService()
            }
            SipEvent.CallEnded -> endLocally()
            is SipEvent.Failure -> {
                val status = event.statusCode?.let { " (SIP $it)" }.orEmpty()
                fail(event.message + status)
            }
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

    private fun endLocally() {
        inboundStatusJob?.cancel()
        inboundStatusJob = null
        stopIncomingRingtone()
        webRtc.close()
        stopCallService()
        incomingInvite = null
        _state.update {
            it.copy(
                phase = CallPhase.IDLE,
                remoteNumber = "",
                muted = false,
                speakerEnabled = false,
                pendingCallId = null,
            )
        }
    }

    private fun fail(message: String) {
        inboundStatusJob?.cancel()
        inboundStatusJob = null
        stopIncomingRingtone()
        webRtc.close()
        stopCallService()
        _state.update { it.copy(phase = CallPhase.FAILED, error = message) }
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

    private fun iceServers(config: AppConfig): List<PeerConnection.IceServer> = buildList {
        DEFAULT_STUN_SERVERS.forEach { url ->
            add(PeerConnection.IceServer.builder(url).createIceServer())
        }
        if (config.turnUrl.isNotBlank()) {
            add(
                PeerConnection.IceServer.builder(config.turnUrl)
                    .setUsername(config.turnUsername)
                    .setPassword(config.turnPassword)
                    .createIceServer(),
            )
        }
    }

    private companion object {
        val E164 = Regex("^\\+[1-9]\\d{7,14}$")
        const val INBOUND_STATUS_POLL_MS = 1_000L
        val DEFAULT_STUN_SERVERS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
            "stun:global.stun.twilio.com:3478",
        )
    }
}
