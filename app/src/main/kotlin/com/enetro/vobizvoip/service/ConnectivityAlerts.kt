package com.enetro.vobizvoip.service

import com.enetro.vobizvoip.domain.BackendHealthState
import com.enetro.vobizvoip.signaling.RegistrationState

enum class ConnectivityAlert {
    SIP_CONNECTED,
    SIP_DISCONNECTED,
    BACKEND_SUCCESS,
    BACKEND_FAILED,
}

/**
 * Decides when a dismissable status notification should fire. Intermediate
 * states (connecting / checking) are ignored so periodic probes stay quiet.
 */
object ConnectivityAlerts {
    fun forSip(
        previousStable: RegistrationState?,
        current: RegistrationState,
    ): ConnectivityAlert? {
        if (
            current == RegistrationState.CONNECTING ||
            current == RegistrationState.REGISTERING
        ) {
            return null
        }
        if (current == previousStable) return null
        if (current == RegistrationState.REGISTERED) {
            return ConnectivityAlert.SIP_CONNECTED
        }
        val lost = current == RegistrationState.DISCONNECTED ||
            current == RegistrationState.FAILED
        if (!lost) return null
        if (previousStable == RegistrationState.REGISTERED) {
            return ConnectivityAlert.SIP_DISCONNECTED
        }
        // First completed attempt failed (CONNECTING → FAILED; stable was still null).
        if (previousStable == null && current == RegistrationState.FAILED) {
            return ConnectivityAlert.SIP_DISCONNECTED
        }
        return null
    }

    fun forBackend(
        previousStable: BackendHealthState?,
        current: BackendHealthState,
    ): ConnectivityAlert? {
        if (
            current == BackendHealthState.CHECKING ||
            current == BackendHealthState.UNKNOWN
        ) {
            return null
        }
        if (current == previousStable) return null
        return when (current) {
            BackendHealthState.ONLINE -> ConnectivityAlert.BACKEND_SUCCESS
            BackendHealthState.OFFLINE -> ConnectivityAlert.BACKEND_FAILED
            else -> null
        }
    }

    fun isStableSip(state: RegistrationState): Boolean =
        state == RegistrationState.REGISTERED ||
            state == RegistrationState.DISCONNECTED ||
            state == RegistrationState.FAILED

    fun isStableBackend(state: BackendHealthState): Boolean =
        state == BackendHealthState.ONLINE || state == BackendHealthState.OFFLINE

    fun isSipConnected(state: RegistrationState): Boolean =
        state == RegistrationState.REGISTERED

    fun isBackendConnected(state: BackendHealthState): Boolean =
        state == BackendHealthState.ONLINE

    fun needsRetry(registration: RegistrationState, health: BackendHealthState): Boolean =
        !isSipConnected(registration) || !isBackendConnected(health)
}
