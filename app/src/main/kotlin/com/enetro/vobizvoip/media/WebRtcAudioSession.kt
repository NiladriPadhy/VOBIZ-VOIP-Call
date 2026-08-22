package com.enetro.vobizvoip.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CopyOnWriteArrayList

class WebRtcAudioSession(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioDeviceModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var iceGatheringComplete = CompletableDeferred<Unit>()
    private val localIceCandidates = CopyOnWriteArrayList<IceCandidate>()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    suspend fun createOffer(iceServers: List<PeerConnection.IceServer>): String {
        repeat(ICE_GATHERING_ATTEMPTS) { attempt ->
            createPeer(iceServers)
            val offer = createDescription(isOffer = true)
            setLocalDescription(offer)
            awaitIceGathering()
            val localSdp = localDescriptionWithIceCandidates()
            if (localSdp.hasRoutableIceCandidate()) {
                return normalizeVobizSdp(localSdp)
            }
            if (attempt < ICE_GATHERING_ATTEMPTS - 1) {
                delay(ICE_RETRY_DELAY_MS)
            }
        }
        throw IllegalStateException("Unable to gather ICE candidates; check the network connection")
    }

    suspend fun answerOffer(
        remoteOffer: String,
        iceServers: List<PeerConnection.IceServer>,
    ): String {
        repeat(ICE_GATHERING_ATTEMPTS) { attempt ->
            createPeer(iceServers)
            setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, remoteOffer))
            val answer = createDescription(isOffer = false)
            setLocalDescription(answer)
            awaitIceGathering()
            val localSdp = localDescriptionWithIceCandidates()
            if (localSdp.hasRoutableIceCandidate()) {
                return normalizeVobizSdp(localSdp)
            }
            if (attempt < ICE_GATHERING_ATTEMPTS - 1) {
                delay(ICE_RETRY_DELAY_MS)
            }
        }
        throw IllegalStateException("Unable to gather ICE candidates; check the network connection")
    }

    suspend fun applyAnswer(remoteAnswer: String) {
        setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, remoteAnswer))
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val preferredType = if (enabled) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        audioManager.availableCommunicationDevices
            .firstOrNull { it.type == preferredType }
            ?.let(audioManager::setCommunicationDevice)
    }

    fun sendDtmf(digits: String): Boolean {
        val sender = peerConnection?.senders?.firstOrNull {
            it.track()?.kind() == "audio"
        } ?: return false
        return sender.dtmf()?.insertDtmf(digits, 160, 70) ?: false
    }

    fun close() {
        localAudioTrack?.setEnabled(false)
        peerConnection?.close()
        peerConnection?.dispose()
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnection = null
        localAudioTrack = null
        audioSource = null
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.clearCommunicationDevice()
    }

    fun dispose() {
        close()
        factory.dispose()
        audioDeviceModule.release()
    }

    private fun createPeer(iceServers: List<PeerConnection.IceServer>) {
        close()
        iceGatheringComplete = CompletableDeferred()
        localIceCandidates.clear()
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // SIP carries the complete SDP in one INVITE/200 response; trickle ICE is unavailable.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        peerConnection = requireNotNull(factory.createPeerConnection(configuration, observer)) {
            "Unable to create WebRTC peer connection"
        }
        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        }
        audioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("vobiz-audio", audioSource).also {
            peerConnection?.addTrack(it, listOf("vobiz-stream"))
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private suspend fun createDescription(isOffer: Boolean): SessionDescription =
        withContext(Dispatchers.Default) {
            val deferred = CompletableDeferred<SessionDescription>()
            val callback = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    deferred.complete(description)
                }

                override fun onCreateFailure(error: String) {
                    deferred.completeExceptionally(IllegalStateException(error))
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String) = Unit
            }
            val constraints = MediaConstraints().apply {
                mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
                mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
            }
            if (isOffer) {
                peerConnection?.createOffer(callback, constraints)
            } else {
                peerConnection?.createAnswer(callback, constraints)
            }
            withTimeout(SDP_TIMEOUT_MS) { deferred.await() }
        }

    private suspend fun setLocalDescription(description: SessionDescription) {
        setDescription(description) { observer -> peerConnection?.setLocalDescription(observer, description) }
    }

    private suspend fun setRemoteDescription(description: SessionDescription) {
        setDescription(description) { observer -> peerConnection?.setRemoteDescription(observer, description) }
    }

    private suspend fun setDescription(
        description: SessionDescription,
        operation: (SdpObserver) -> Unit,
    ) {
        val deferred = CompletableDeferred<Unit>()
        operation(
            object : SdpObserver {
                override fun onSetSuccess() {
                    deferred.complete(Unit)
                }

                override fun onSetFailure(error: String) {
                    deferred.completeExceptionally(
                        IllegalStateException("${description.type}: $error"),
                    )
                }

                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            },
        )
        withTimeout(SDP_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun awaitIceGathering() {
        if (peerConnection?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return
        // Android often never signals ICE gathering COMPLETE. The working JsSIP
        // POC forces SDP out ~1.2s after the first candidate so INVITE / 200 OK
        // are not delayed until the 20s timeout.
        withTimeout(ICE_TIMEOUT_MS) {
            while (
                localIceCandidates.isEmpty() &&
                !iceGatheringComplete.isCompleted &&
                peerConnection?.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE
            ) {
                delay(50)
            }
            delay(ICE_EARLY_READY_MS)
        }
    }

    private fun localDescriptionWithIceCandidates(): String {
        val description = requireNotNull(peerConnection?.localDescription).description
        val separator = if (description.contains("\r\n")) "\r\n" else "\n"
        val existingCandidates = description.lineSequence()
            .filter { it.startsWith("a=candidate:") }
            .toSet()
        val candidates = localIceCandidates
            .map { candidate ->
                candidate.sdp.let { if (it.startsWith("a=")) it else "a=$it" }
            }
            .filterNot(existingCandidates::contains)
        if (candidates.isEmpty()) return description

        return buildString {
            append(description.trimEnd())
            append(separator)
            append(candidates.joinToString(separator))
            append(separator)
            if (!description.contains("a=end-of-candidates")) {
                append("a=end-of-candidates")
                append(separator)
            }
        }
    }

    private fun String.hasRoutableIceCandidate(): Boolean =
        lineSequence().any { it.startsWith("a=candidate:") }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                iceGatheringComplete.complete(Unit)
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            localIceCandidates += candidate
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private companion object {
        const val SDP_TIMEOUT_MS = 15_000L
        const val ICE_TIMEOUT_MS = 20_000L
        const val ICE_EARLY_READY_MS = 1_200L
        const val ICE_GATHERING_ATTEMPTS = 3
        const val ICE_RETRY_DELAY_MS = 750L
    }
}

internal fun normalizeVobizSdp(sdp: String): String {
    val separator = if (sdp.contains("\r\n")) "\r\n" else "\n"
    val lines = sdp.split(Regex("\r?\n"))
    val payloadByCodec = lines.mapNotNull { line ->
        RTPMAP.matchEntire(line)?.destructured?.let { (payload, codec) ->
            codec.uppercase() to payload
        }
    }.toMap()
    val allowedPayloads = listOfNotNull(
        payloadByCodec["OPUS"],
        payloadByCodec["PCMU"],
    )
    if (allowedPayloads.isEmpty()) return sdp

    return lines.mapNotNull { line ->
        when {
            line.startsWith("m=audio ") -> {
                val fields = line.split(Regex("\\s+"))
                if (fields.size < 4) line else fields.take(3).plus(allowedPayloads).joinToString(" ")
            }
            line.startsWith("a=fmtp:${payloadByCodec["OPUS"]} ") &&
                !line.contains("maxaveragebitrate=", ignoreCase = true) -> {
                "$line;maxaveragebitrate=48000"
            }
            CODEC_ATTRIBUTE.matches(line) -> {
                val payload = CODEC_ATTRIBUTE.matchEntire(line)?.groupValues?.get(1)
                line.takeIf { payload in allowedPayloads }
            }
            else -> line
        }
    }.joinToString(separator)
}

private val RTPMAP = Regex("""a=rtpmap:(\d+)\s+([^/]+)/.*""", RegexOption.IGNORE_CASE)
private val CODEC_ATTRIBUTE =
    Regex("""a=(?:rtpmap|fmtp|rtcp-fb):(\d+)(?:\s|$).*""", RegexOption.IGNORE_CASE)
