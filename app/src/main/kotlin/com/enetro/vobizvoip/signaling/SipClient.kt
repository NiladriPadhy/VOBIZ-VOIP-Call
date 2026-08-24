package com.enetro.vobizvoip.signaling

import android.util.Log
import com.enetro.vobizvoip.data.AppConfig
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

enum class RegistrationState {
    DISCONNECTED,
    CONNECTING,
    REGISTERING,
    REGISTERED,
    FAILED,
}

sealed interface SipEvent {
    data class IncomingInvite(val caller: String, val request: SipMessage) : SipEvent
    data object RemoteRinging : SipEvent
    data class CallAccepted(val remoteSdp: String?) : SipEvent
    data object CallEnded : SipEvent
    data class Failure(val message: String, val statusCode: Int? = null) : SipEvent
}

class SipClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val random = SecureRandom()
    private val _registrationState = MutableStateFlow(RegistrationState.DISCONNECTED)
    private val _events = MutableSharedFlow<SipEvent>(extraBufferCapacity = 16)
    private var config: AppConfig? = null
    private var socket: WebSocket? = null
    private var registerJob: Job? = null
    private var optionsJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var userRequestedDisconnect = true
    private var authRejected = false
    private var nextCSeq = 1
    private var registerCallId = newCallId()
    private var registerFromTag = token(10)
    private val contactHost = "${token(16)}.invalid"
    private var registeredContactUri: String? = null
    private var registerAuthAttempts = 0
    private var inviteAuthAttempts = 0
    private var activeDialog: Dialog? = null
    private var pendingIncomingInvite: SipMessage? = null
    private var lastInvite: SipMessage? = null
    private var lastAnsweredSdp: String? = null
    private var cancellationPending = false
    private val nonceCounts = mutableMapOf<String, Int>()

    val registrationState: StateFlow<RegistrationState> = _registrationState
    val events: SharedFlow<SipEvent> = _events

    fun connect(config: AppConfig) {
        reconnectJob?.cancel()
        reconnectJob = null
        userRequestedDisconnect = false
        authRejected = false
        beginSession(config)
    }

    fun disconnect() {
        userRequestedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        authRejected = false
        closeSocket()
        _registrationState.value = RegistrationState.DISCONNECTED
    }

    private fun beginSession(config: AppConfig) {
        closeSocket()
        this.config = config
        _registrationState.value = RegistrationState.CONNECTING
        val request = Request.Builder()
            .url(config.registrarUrl)
            .header("Sec-WebSocket-Protocol", "sip")
            .build()
        socket = httpClient.newWebSocket(request, listener)
    }

    private fun closeSocket() {
        registerJob?.cancel()
        registerJob = null
        optionsJob?.cancel()
        optionsJob = null
        val current = socket
        socket = null
        current?.close(1000, "Client disconnect")
        registeredContactUri = null
        activeDialog = null
        pendingIncomingInvite = null
        lastInvite = null
        lastAnsweredSdp = null
        cancellationPending = false
    }

    private fun scheduleReconnect() {
        if (userRequestedDisconnect || authRejected) return
        if (reconnectJob?.isActive == true) return
        val current = config ?: return
        reconnectJob = scope.launch {
            val delayMs = reconnectDelayMs(reconnectAttempt)
            Log.i("VobizSip", "Reconnecting in ${delayMs}ms (attempt ${reconnectAttempt + 1})")
            delay(delayMs)
            if (userRequestedDisconnect || authRejected) return@launch
            reconnectAttempt += 1
            beginSession(current)
        }
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val shift = attempt.coerceAtMost(5)
        return (RECONNECT_INITIAL_MS shl shift).coerceAtMost(RECONNECT_MAX_MS)
    }

    fun invite(destination: String, localSdp: String) {
        val current = requireNotNull(config) { "SIP client is not configured" }
        check(_registrationState.value == RegistrationState.REGISTERED) {
            "SIP endpoint is not registered"
        }
        val uri = toSipUri(destination, current.sipDomain)
        val fromTag = token(10)
        val callId = newCallId()
        val branch = branch()
        val cSeq = nextCSeq++
        inviteAuthAttempts = 0
        val mediaLine = localSdp.lineSequence().firstOrNull { it.startsWith("m=audio ") }
        val codecs = localSdp.lineSequence()
            .filter { it.startsWith("a=rtpmap:") }
            .joinToString { it.substringAfter(' ') }
        val candidateTypes = localSdp.lineSequence()
            .filter { it.startsWith("a=candidate:") }
            .mapNotNull { it.substringAfter(" typ ", "").substringBefore(' ').takeIf(String::isNotBlank) }
            .toSet()
        Log.i(
            "VobizSip",
            "INVITE media: $mediaLine; codecs=[$codecs]; candidateTypes=$candidateTypes; " +
                "rtcpMux=${localSdp.contains("a=rtcp-mux")}",
        )
        val request = SipMessage.request(
            method = "INVITE",
            uri = uri,
            headers = commonRequestHeaders(branch) + listOf(
                "From" to "<sip:${current.sipUsername}@${current.sipDomain}>;tag=$fromTag",
                "To" to "<$uri>",
                "Call-ID" to callId,
                "CSeq" to "$cSeq INVITE",
                "Contact" to "<${dialogContactUri(current)}>",
                "Allow" to "INVITE, ACK, CANCEL, BYE, OPTIONS, INFO",
                "Content-Type" to "application/sdp",
            ),
            body = localSdp,
        )
        activeDialog = Dialog(
            callId = callId,
            localTag = fromTag,
            remoteTag = null,
            localUri = "sip:${current.sipUsername}@${current.sipDomain}",
            remoteUri = uri,
            remoteTarget = uri,
            inviteCSeq = cSeq,
            isOutgoing = true,
        )
        lastInvite = request
        cancellationPending = false
        send(request)
    }

    fun cancelOutgoing() {
        val invite = lastInvite ?: return
        val dialog = activeDialog ?: return
        if (dialog.established || cancellationPending) return
        cancellationPending = true
        val cancel = SipMessage.request(
            "CANCEL",
            dialog.remoteUri,
            listOf(
                "Via" to invite.header("Via").orEmpty(),
                "Max-Forwards" to "70",
                "User-Agent" to USER_AGENT,
                "From" to invite.header("From").orEmpty(),
                "To" to invite.header("To").orEmpty(),
                "Call-ID" to dialog.callId,
                "CSeq" to "${dialog.inviteCSeq} CANCEL",
            ),
        )
        send(cancel)
    }

    fun acceptIncoming(localSdp: String) {
        val invite = pendingIncomingInvite ?: error("No incoming call")
        val current = requireNotNull(config)
        val toTag = activeDialog?.localTag ?: token(10)
        lastAnsweredSdp = localSdp
        send(
            SipMessage.response(
                request = invite,
                code = 200,
                reason = "OK",
                toTag = toTag,
                body = localSdp,
                contentType = "application/sdp",
                contact = dialogContactUri(current),
            ),
        )
    }

    fun rejectIncoming() {
        val invite = pendingIncomingInvite ?: return
        send(SipMessage.response(invite, 486, "Busy Here", activeDialog?.localTag ?: token(10)))
        clearDialog()
    }

    fun abandonCall() {
        clearDialog()
    }

    fun hangup() {
        val dialog = activeDialog ?: return
        val current = requireNotNull(config)
        val from = if (dialog.isOutgoing) {
            "<${dialog.localUri}>;tag=${dialog.localTag}"
        } else {
            "<${dialog.localUri}>;tag=${dialog.localTag}"
        }
        val to = buildString {
            append("<").append(dialog.remoteUri).append(">")
            dialog.remoteTag?.let { append(";tag=").append(it) }
        }
        val bye = SipMessage.request(
            "BYE",
            dialog.remoteTarget,
            commonRequestHeaders(branch()) + routeHeaders(dialog) + listOf(
                "From" to from,
                "To" to to,
                "Call-ID" to dialog.callId,
                "CSeq" to "${dialog.localCSeq + 1} BYE",
                "Contact" to "<${dialogContactUri(current)}>",
            ),
        )
        send(bye)
        // The UI ends immediately and Vobiz can return 481 after the PSTN leg has
        // already disappeared. Do not retain a stale dialog that would reject the
        // next inbound INVITE as busy.
        clearDialog()
    }

    fun sendDtmfInfo(digit: Char) {
        val dialog = activeDialog ?: return
        val body = "Signal=$digit\r\nDuration=160\r\n"
        send(
            SipMessage.request(
                "INFO",
                dialog.remoteTarget,
                commonRequestHeaders(branch()) + routeHeaders(dialog) + listOf(
                    "From" to "<${dialog.localUri}>;tag=${dialog.localTag}",
                    "To" to "<${dialog.remoteUri}>" +
                        (dialog.remoteTag?.let { ";tag=$it" } ?: ""),
                    "Call-ID" to dialog.callId,
                    "CSeq" to "${dialog.localCSeq + 1} INFO",
                    "Content-Type" to "application/dtmf-relay",
                ),
                body,
            ),
        )
        activeDialog = dialog.copy(localCSeq = dialog.localCSeq + 1)
    }

    private fun register(
        authorization: String? = null,
        authorizationHeader: String = "Authorization",
    ) {
        val current = requireNotNull(config)
        _registrationState.value = RegistrationState.REGISTERING
        val uri = "sip:${current.sipDomain}"
        val aor = "sip:${current.sipUsername}@${current.sipDomain}"
        val headers = commonRequestHeaders(branch()) + buildList {
            add("From" to "<$aor>;tag=$registerFromTag")
            add("To" to "<$aor>")
            add("Call-ID" to registerCallId)
            add("CSeq" to "${nextCSeq++} REGISTER")
            add("Contact" to "<${contactUri(current)}>;expires=$REGISTRATION_SECONDS")
            add("Expires" to REGISTRATION_SECONDS.toString())
            add("Allow" to "INVITE, ACK, CANCEL, BYE, OPTIONS, INFO")
            add("Supported" to "path, gruu, outbound")
            authorization?.let { add(authorizationHeader to it) }
        }
        send(SipMessage.request("REGISTER", uri, headers))
    }

    private fun scheduleRegistrationRefresh() {
        registerJob?.cancel()
        registerJob = scope.launch {
            while (isActive) {
                delay(REGISTRATION_REFRESH_MS)
                runCatching { register() }
                    .onFailure { error ->
                        Log.w("VobizSip", "Registration refresh failed: ${error.message}")
                        scheduleReconnect()
                        return@launch
                    }
            }
        }
    }

    private fun scheduleOptionsKeepAlive() {
        optionsJob?.cancel()
        optionsJob = scope.launch {
            while (isActive) {
                delay(OPTIONS_KEEPALIVE_MS)
                val current = config ?: continue
                if (_registrationState.value != RegistrationState.REGISTERED) continue
                val uri = "sip:${current.sipUsername}@${current.sipDomain}"
                runCatching {
                    send(
                        SipMessage.request(
                            "OPTIONS",
                            uri,
                            commonRequestHeaders(branch()) + listOf(
                                "From" to
                                    "<sip:${current.sipUsername}@${current.sipDomain}>;tag=$registerFromTag",
                                "To" to "<$uri>",
                                "Call-ID" to newCallId(),
                                "CSeq" to "${nextCSeq++} OPTIONS",
                            ),
                        ),
                    )
                }.onFailure { error ->
                    Log.w("VobizSip", "OPTIONS keepalive failed: ${error.message}")
                    scheduleReconnect()
                    return@launch
                }
            }
        }
    }

    private fun handle(message: SipMessage) {
        if (message.isResponse) {
            handleResponse(message)
        } else {
            handleRequest(message)
        }
    }

    private fun handleResponse(response: SipMessage) {
        val method = response.cSeqMethod ?: return
        val code = response.statusCode ?: return
        when {
            method == "REGISTER" && (code == 401 || code == 407) -> {
                if (registerAuthAttempts >= 1) {
                    authRejected = true
                    _registrationState.value = RegistrationState.FAILED
                    _events.tryEmit(SipEvent.Failure("SIP credentials were rejected", code))
                    return
                }
                registerAuthAttempts++
                authenticateAndRetry(response, "REGISTER")
            }
            method == "REGISTER" && code in 200..299 -> {
                registeredContactUri = response.header("Contact")
                    ?.let(::extractUri)
                    ?.takeIf(String::isNotBlank)
                Log.i(
                    "VobizSip",
                    "REGISTER accepted: Contact=${response.header("Contact") ?: "none"}; " +
                        "Expires=${response.header("Expires") ?: "none"}; " +
                        "Path=${response.header("Path") ?: "none"}",
                )
                registerAuthAttempts = 0
                reconnectAttempt = 0
                authRejected = false
                _registrationState.value = RegistrationState.REGISTERED
                scheduleRegistrationRefresh()
                scheduleOptionsKeepAlive()
            }
            method == "REGISTER" && code >= 300 -> {
                if (code == 403) {
                    authRejected = true
                }
                _registrationState.value = RegistrationState.FAILED
                _events.tryEmit(SipEvent.Failure("SIP registration failed", code))
                if (!authRejected) {
                    scheduleReconnect()
                }
            }
            method == "INVITE" && (code == 401 || code == 407) -> {
                sendFailureAck(response)
                if (inviteAuthAttempts >= 1) {
                    _events.tryEmit(SipEvent.Failure("Call authentication was rejected", code))
                    clearDialog()
                    return
                }
                inviteAuthAttempts++
                authenticateAndRetry(response, "INVITE")
            }
            method == "INVITE" && code in 180..183 -> {
                _events.tryEmit(SipEvent.RemoteRinging)
            }
            method == "INVITE" && code in 200..299 -> {
                val wasEstablished = activeDialog?.established == true
                establishOutgoingDialog(response)
                sendAck(response)
                if (wasEstablished) return
                activeDialog = activeDialog?.copy(established = true)
                if (cancellationPending) {
                    hangup()
                } else {
                    _events.tryEmit(SipEvent.CallAccepted(response.body.ifBlank { null }))
                }
            }
            method == "INVITE" && code >= 300 -> {
                sendFailureAck(response)
                Log.i(
                    "VobizSip",
                    "INVITE rejected: ${response.startLine}; " +
                        "Warning=${response.header("Warning") ?: "none"}; " +
                        "Reason=${response.header("Reason") ?: "none"}",
                )
                if (cancellationPending) {
                    _events.tryEmit(SipEvent.CallEnded)
                } else {
                    _events.tryEmit(SipEvent.Failure(reasonFor(code), code))
                }
                clearDialog()
            }
            method == "BYE" && code in 200..299 -> {
                _events.tryEmit(SipEvent.CallEnded)
                clearDialog()
            }
        }
    }

    private fun handleRequest(request: SipMessage) {
        when (request.method) {
            "INVITE" -> handleIncomingInvite(request)
            "ACK" -> {
                val dialog = activeDialog
                val matches = dialog != null &&
                    dialog.callId == request.header("Call-ID") &&
                    !dialog.established
                Log.i(
                    "VobizSip",
                    "ACK received: matches=$matches; dialogCallId=${dialog?.callId}; " +
                        "ackCallId=${request.header("Call-ID")}; established=${dialog?.established}",
                )
                if (matches) {
                    activeDialog = dialog!!.copy(established = true)
                    _events.tryEmit(SipEvent.CallAccepted(null))
                }
            }
            "BYE" -> {
                val dialog = activeDialog
                if (dialog != null) {
                    val receivedCallId = request.header("Call-ID")
                    if (dialog.callId != receivedCallId) {
                        Log.w(
                            "VobizSip",
                            "Ending the only active call after BYE with unexpected Call-ID: " +
                                "expected=${dialog.callId}, received=$receivedCallId",
                        )
                    }
                    send(SipMessage.response(request, 200, "OK"))
                    _events.tryEmit(SipEvent.CallEnded)
                    clearDialog()
                } else {
                    send(SipMessage.response(request, 481, "Call/Transaction Does Not Exist"))
                }
            }
            "CANCEL" -> {
                val invite = pendingIncomingInvite
                if (invite != null && cancelMatchesInvite(request, invite)) {
                    send(SipMessage.response(request, 200, "OK"))
                    send(
                        SipMessage.response(
                            invite,
                            487,
                            "Request Terminated",
                            activeDialog?.localTag,
                        ),
                    )
                    _events.tryEmit(SipEvent.CallEnded)
                    clearDialog()
                } else {
                    send(SipMessage.response(request, 481, "Call/Transaction Does Not Exist"))
                }
            }
            "OPTIONS" -> send(SipMessage.response(request, 200, "OK"))
        }
    }

    private fun handleIncomingInvite(invite: SipMessage) {
        val current = requireNotNull(config)
        val callId = invite.header("Call-ID").orEmpty()
        if (
            invite.body.isBlank() ||
            !invite.header("Content-Type").orEmpty().startsWith("application/sdp", ignoreCase = true)
        ) {
            send(SipMessage.response(invite, 488, "Not Acceptable Here", token(10)))
            return
        }
        if (activeDialog != null) {
            if (activeDialog?.callId == callId) {
                val answeredSdp = lastAnsweredSdp
                if (answeredSdp != null) {
                    send(
                        SipMessage.response(
                            invite,
                            200,
                            "OK",
                            activeDialog?.localTag,
                            answeredSdp,
                            "application/sdp",
                            dialogContactUri(current),
                        ),
                    )
                } else {
                    send(SipMessage.response(invite, 180, "Ringing", activeDialog?.localTag))
                }
            } else {
                send(SipMessage.response(invite, 486, "Busy Here", token(10)))
            }
            return
        }
        val localTag = token(10)
        val callerUri = extractUri(invite.header("From").orEmpty())
        val caller = invite.incomingCallerDisplay()
        pendingIncomingInvite = invite
        activeDialog = Dialog(
            callId = callId,
            localTag = localTag,
            remoteTag = extractTag(invite.header("From")),
            localUri = "sip:${current.sipUsername}@${current.sipDomain}",
            remoteUri = callerUri,
            remoteTarget = extractUri(invite.header("Contact") ?: callerUri),
            inviteCSeq = invite.header("CSeq")?.substringBefore(' ')?.toIntOrNull() ?: 1,
            localCSeq = nextCSeq++,
            isOutgoing = false,
            routeSet = invite.headers("Record-Route"),
        )
        send(SipMessage.response(invite, 100, "Trying"))
        send(
            SipMessage.response(
                invite,
                180,
                "Ringing",
                localTag,
                contact = dialogContactUri(current),
            ),
        )
        _events.tryEmit(SipEvent.IncomingInvite(caller, invite))
    }

    private fun authenticateAndRetry(response: SipMessage, method: String) {
        val current = requireNotNull(config)
        val original = when (method) {
            "REGISTER" -> null
            "INVITE" -> lastInvite
            else -> null
        }
        val headerName = if (response.statusCode == 407) "Proxy-Authenticate" else "WWW-Authenticate"
        val authorizationName = if (response.statusCode == 407) "Proxy-Authorization" else "Authorization"
        val challenge = runCatching {
            SipDigestAuth.parseChallenge(requireNotNull(response.header(headerName)))
        }.getOrElse {
            _events.tryEmit(SipEvent.Failure("Invalid SIP authentication challenge"))
            return
        }
        Log.i(
            "VobizSip",
            "Digest challenge: status=${response.statusCode}, realm=${challenge.realm}, " +
                "algorithm=${challenge.algorithm}, qop=${challenge.qop ?: "none"}",
        )
        val nonceCount = nonceCounts.merge(challenge.nonce, 1, Int::plus) ?: 1
        val uri = original?.requestUri ?: "sip:${current.sipDomain}"
        val authorization = SipDigestAuth.authorization(
            challenge = challenge,
            username = current.sipUsername,
            password = current.sipPassword,
            method = method,
            uri = uri,
            nonceCount = nonceCount,
        )
        if (method == "REGISTER") {
            register(authorization, authorizationName)
        } else if (original != null) {
            val cSeq = nextCSeq++
            val retry = original.copy(
                headers = original.headers
                    .filterNot {
                        it.first.equals("Via", true) ||
                            it.first.equals("CSeq", true) ||
                            it.first.equals("Max-Forwards", true) ||
                            it.first.equals("User-Agent", true) ||
                            it.first.equals(authorizationName, true)
                    }
                    .let { commonRequestHeaders(branch()) + it } +
                    ("CSeq" to "$cSeq INVITE") +
                    (authorizationName to authorization),
            )
            activeDialog = activeDialog?.copy(inviteCSeq = cSeq, localCSeq = cSeq)
            lastInvite = retry
            send(retry)
        }
    }

    private fun establishOutgoingDialog(response: SipMessage) {
        val dialog = activeDialog ?: return
        activeDialog = dialog.copy(
            remoteTag = extractTag(response.header("To")),
            remoteTarget = extractUri(response.header("Contact") ?: dialog.remoteUri),
            routeSet = response.headers("Record-Route").reversed(),
        )
    }

    private fun sendAck(response: SipMessage) {
        val dialog = activeDialog ?: return
        send(
            SipMessage.request(
                "ACK",
                dialog.remoteTarget,
                commonRequestHeaders(branch()) + routeHeaders(dialog) + listOf(
                    "From" to lastInvite?.header("From").orEmpty(),
                    "To" to response.header("To").orEmpty(),
                    "Call-ID" to dialog.callId,
                    "CSeq" to "${dialog.inviteCSeq} ACK",
                ),
            ),
        )
    }

    private fun sendFailureAck(response: SipMessage) {
        val dialog = activeDialog ?: return
        send(
            SipMessage.request(
                "ACK",
                dialog.remoteUri,
                listOf(
                    "Via" to lastInvite?.header("Via").orEmpty(),
                    "Max-Forwards" to "70",
                    "User-Agent" to USER_AGENT,
                    "From" to lastInvite?.header("From").orEmpty(),
                    "To" to response.header("To").orEmpty(),
                    "Call-ID" to dialog.callId,
                    "CSeq" to "${dialog.inviteCSeq} ACK",
                ),
            ),
        )
    }

    private fun commonRequestHeaders(branch: String): List<Pair<String, String>> = listOf(
        "Via" to "SIP/2.0/WSS $contactHost;branch=$branch;rport",
        "Max-Forwards" to "70",
        "User-Agent" to USER_AGENT,
    )

    private fun routeHeaders(dialog: Dialog): List<Pair<String, String>> =
        dialog.routeSet.map { "Route" to it }

    private fun send(message: SipMessage) {
        Log.i("VobizSip", "TX ${message.startLine}; Call-ID=${message.header("Call-ID") ?: "none"}")
        check(socket?.send(message.encode()) == true) { "SIP WebSocket is not connected" }
    }

    private fun clearDialog() {
        activeDialog = null
        pendingIncomingInvite = null
        lastInvite = null
        lastAnsweredSdp = null
        inviteAuthAttempts = 0
        cancellationPending = false
    }

    private fun cancelMatchesInvite(cancel: SipMessage, invite: SipMessage): Boolean {
        val cancelCSeq = cancel.header("CSeq")?.substringBefore(' ')
        val inviteCSeq = invite.header("CSeq")?.substringBefore(' ')
        val cancelBranch = extractBranch(cancel.header("Via"))
        return cancel.header("Call-ID") == invite.header("Call-ID") &&
            cancel.header("From") == invite.header("From") &&
            cancel.header("To") == invite.header("To") &&
            cancelCSeq == inviteCSeq &&
            cancelBranch != null &&
            cancelBranch == extractBranch(invite.header("Via"))
    }

    private fun extractBranch(via: String?): String? =
        via?.substringAfter(";branch=", "")?.substringBefore(';')?.takeIf(String::isNotBlank)

    private fun contactUri(config: AppConfig): String =
        "sip:${config.sipUsername}@$contactHost;transport=ws;ob"

    private fun dialogContactUri(config: AppConfig): String =
        registeredContactUri ?: contactUri(config)

    private fun toSipUri(destination: String, domain: String): String =
        if (destination.startsWith("sip:")) destination else "sip:$destination@$domain"

    private fun extractUri(value: String): String =
        if ('<' in value && '>' in value) value.substringAfter('<').substringBefore('>')
        else value.substringBefore(';').trim()

    private fun extractTag(value: String?): String? =
        value?.substringAfter(";tag=", "")?.substringBefore(';')?.takeIf(String::isNotBlank)

    private fun reasonFor(code: Int): String = when (code) {
        401, 403, 407 -> "SIP authentication was rejected"
        404 -> "Destination was not found"
        408 -> "Call timed out"
        480 -> "Destination is unavailable"
        486 -> "Destination is busy"
        else -> "Call failed with SIP status $code"
    }

    private fun newCallId(): String = "${UUID.randomUUID()}@client.invalid"
    private fun branch(): String = "z9hG4bK${token(18)}"
    private fun token(length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            register()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.isBlank()) return
            runCatching { SipMessage.parse(text) }
                .onSuccess { message ->
                    Log.i(
                        "VobizSip",
                        "RX ${message.startLine}; Call-ID=${message.header("Call-ID") ?: "none"}",
                    )
                    handle(message)
                }
                .onFailure { _events.tryEmit(SipEvent.Failure("Malformed SIP message")) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket != socket) return
            socket = null
            _registrationState.value = RegistrationState.DISCONNECTED
            if (activeDialog != null || pendingIncomingInvite != null) {
                _events.tryEmit(SipEvent.CallEnded)
                clearDialog()
            }
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket != socket) return
            socket = null
            _registrationState.value = RegistrationState.FAILED
            _events.tryEmit(SipEvent.Failure(t.message ?: "SIP WebSocket failed"))
            scheduleReconnect()
        }
    }

    private data class Dialog(
        val callId: String,
        val localTag: String,
        val remoteTag: String?,
        val localUri: String,
        val remoteUri: String,
        val remoteTarget: String,
        val inviteCSeq: Int,
        val localCSeq: Int = inviteCSeq,
        val isOutgoing: Boolean,
        val routeSet: List<String> = emptyList(),
        val established: Boolean = false,
    )

    private companion object {
        const val REGISTRATION_SECONDS = 600
        const val REGISTRATION_REFRESH_MS = 450 * 1_000L
        const val OPTIONS_KEEPALIVE_MS = 60_000L
        const val RECONNECT_INITIAL_MS = 2_000L
        const val RECONNECT_MAX_MS = 60_000L
        const val USER_AGENT = "vobiz_android_poc"
    }
}
