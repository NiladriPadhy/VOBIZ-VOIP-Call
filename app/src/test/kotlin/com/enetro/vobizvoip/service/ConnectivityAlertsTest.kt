package com.enetro.vobizvoip.service

import com.enetro.vobizvoip.domain.BackendHealthState
import com.enetro.vobizvoip.signaling.RegistrationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectivityAlertsTest {
    @Test
    fun `first successful SIP registration notifies connected`() {
        assertEquals(
            ConnectivityAlert.SIP_CONNECTED,
            ConnectivityAlerts.forSip(null, RegistrationState.REGISTERED),
        )
    }

    @Test
    fun `initial disconnected state is quiet`() {
        assertNull(ConnectivityAlerts.forSip(null, RegistrationState.DISCONNECTED))
    }

    @Test
    fun `connecting and registering do not notify`() {
        assertNull(ConnectivityAlerts.forSip(null, RegistrationState.CONNECTING))
        assertNull(
            ConnectivityAlerts.forSip(
                RegistrationState.DISCONNECTED,
                RegistrationState.REGISTERING,
            ),
        )
    }

    @Test
    fun `drop from registered notifies disconnected`() {
        assertEquals(
            ConnectivityAlert.SIP_DISCONNECTED,
            ConnectivityAlerts.forSip(
                RegistrationState.REGISTERED,
                RegistrationState.DISCONNECTED,
            ),
        )
        assertEquals(
            ConnectivityAlert.SIP_DISCONNECTED,
            ConnectivityAlerts.forSip(RegistrationState.REGISTERED, RegistrationState.FAILED),
        )
    }

    @Test
    fun `first registration failure notifies disconnected`() {
        assertEquals(
            ConnectivityAlert.SIP_DISCONNECTED,
            ConnectivityAlerts.forSip(null, RegistrationState.FAILED),
        )
    }

    @Test
    fun `repeated registered state does not notify again`() {
        assertNull(
            ConnectivityAlerts.forSip(RegistrationState.REGISTERED, RegistrationState.REGISTERED),
        )
    }

    @Test
    fun `backend recovery and outage notify once each`() {
        assertEquals(
            ConnectivityAlert.BACKEND_SUCCESS,
            ConnectivityAlerts.forBackend(BackendHealthState.OFFLINE, BackendHealthState.ONLINE),
        )
        assertEquals(
            ConnectivityAlert.BACKEND_FAILED,
            ConnectivityAlerts.forBackend(BackendHealthState.ONLINE, BackendHealthState.OFFLINE),
        )
    }

    @Test
    fun `silent healthy probes and checking do not notify`() {
        assertNull(
            ConnectivityAlerts.forBackend(BackendHealthState.ONLINE, BackendHealthState.ONLINE),
        )
        assertNull(
            ConnectivityAlerts.forBackend(BackendHealthState.ONLINE, BackendHealthState.CHECKING),
        )
        assertNull(
            ConnectivityAlerts.forBackend(BackendHealthState.UNKNOWN, BackendHealthState.CHECKING),
        )
    }

    @Test
    fun `retry is offered when sip or backend is down`() {
        assertEquals(
            false,
            ConnectivityAlerts.needsRetry(RegistrationState.REGISTERED, BackendHealthState.ONLINE),
        )
        assertEquals(
            true,
            ConnectivityAlerts.needsRetry(RegistrationState.FAILED, BackendHealthState.ONLINE),
        )
        assertEquals(
            true,
            ConnectivityAlerts.needsRetry(RegistrationState.REGISTERED, BackendHealthState.OFFLINE),
        )
    }

    @Test
    fun `first backend result notifies`() {
        assertEquals(
            ConnectivityAlert.BACKEND_SUCCESS,
            ConnectivityAlerts.forBackend(null, BackendHealthState.ONLINE),
        )
        assertEquals(
            ConnectivityAlert.BACKEND_FAILED,
            ConnectivityAlerts.forBackend(null, BackendHealthState.OFFLINE),
        )
    }
}
