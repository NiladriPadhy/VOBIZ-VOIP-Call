package com.enetro.vobizvoip.data

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

data class PendingCall(
    val id: String,
    val caller: String,
    val expiresAtEpochMs: Long,
)

data class JoinInstruction(
    val joinNumber: String,
)

data class InboundCallStatus(
    val known: Boolean,
    val active: Boolean,
)

data class HealthReport(
    val firebaseReady: Boolean,
    val pendingCalls: Int,
)

class BackendApi(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun prepareOutbound(config: AppConfig, destination: String) {
        execute(
            config,
            "/calls/outbound",
            JSONObject()
                .put("endpoint", config.sipUsername)
                .put("destination", destination)
                .put("callerId", config.callerId)
                .put("record", config.recordingEnabled),
        )
    }

    suspend fun setRecordingPreference(config: AppConfig) {
        execute(
            config,
            "/devices/recording",
            JSONObject()
                .put("endpoint", config.sipUsername)
                .put("enabled", config.recordingEnabled),
        )
    }

    suspend fun registerInstallation(config: AppConfig, installationId: String) {
        execute(
            config,
            "/devices/register",
            JSONObject()
                .put("endpoint", config.sipUsername)
                .put("installationId", installationId)
                .put("callerId", config.callerId),
        )
    }

    suspend fun pendingCall(config: AppConfig, pendingCallId: String): PendingCall {
        val json = execute(config, "/calls/$pendingCallId", null, "GET")
        return PendingCall(
            id = json.getString("id"),
            caller = json.optString("caller", "Unknown caller"),
            expiresAtEpochMs = json.getLong("expiresAt"),
        )
    }

    suspend fun acceptPending(config: AppConfig, pendingCallId: String): JoinInstruction {
        val json = execute(
            config,
            "/calls/$pendingCallId/accept",
            JSONObject().put("endpoint", config.sipUsername),
        )
        return JoinInstruction(json.getString("joinNumber"))
    }

    suspend fun declinePending(config: AppConfig, pendingCallId: String) {
        execute(config, "/calls/$pendingCallId/decline", JSONObject())
    }

    suspend fun checkHealth(config: AppConfig): HealthReport {
        val json = execute(config, "/health", null, "GET")
        return HealthReport(
            firebaseReady = json.optBoolean("firebase"),
            pendingCalls = json.optInt("pendingCalls"),
        )
    }

    suspend fun inboundCallStatus(config: AppConfig): InboundCallStatus {
        val json = execute(config, "/calls/inbound-status", null, "GET")
        return InboundCallStatus(
            known = json.optBoolean("known"),
            active = json.optBoolean("active"),
        )
    }

    suspend fun fetchRecordings(config: AppConfig): List<Recording> {
        val json = execute(config, "/recordings", null, "GET")
        val array = json.optJSONArray("recordings") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                add(
                    Recording(
                        id = obj.getString("id"),
                        number = obj.optString("number"),
                        direction = if (obj.optString("direction") == "incoming") {
                            CallDirection.INCOMING
                        } else {
                            CallDirection.OUTGOING
                        },
                        startedAtEpochMs = obj.optLong("startedAtEpochMs"),
                        durationSeconds = obj.optLong("durationSeconds"),
                    ),
                )
            }
        }
    }

    private suspend fun execute(
        config: AppConfig,
        path: String,
        body: JSONObject?,
        method: String = "POST",
    ): JSONObject = suspendCancellableCoroutine { continuation ->
        val url = "${config.backendUrl.removeSuffix("/")}$path"
        val requestBody = body?.toString()?.toRequestBody(JSON)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.backendToken}")
            .header("X-Vobiz-Endpoint", config.sipUsername)
            .method(method, requestBody)
            .build()
        // Never logs credentials (auth header/token) — only method, path, status, timing.
        val startedAt = System.currentTimeMillis()
        DiagnosticLog.i(TAG, "request $method $path")
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    DiagnosticLog.w(
                        TAG,
                        "request failed $method $path after " +
                            "${System.currentTimeMillis() - startedAt}ms: ${e.message}",
                    )
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val responseBody = it.body?.string().orEmpty()
                        val elapsed = System.currentTimeMillis() - startedAt
                        if (!it.isSuccessful) {
                            DiagnosticLog.w(TAG, "response ${it.code} $method $path (${elapsed}ms)")
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException("Backend returned HTTP ${it.code}"),
                                )
                            }
                            return
                        }
                        DiagnosticLog.i(TAG, "response ${it.code} $method $path (${elapsed}ms)")
                        val result = if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            },
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val TAG = "VobizBackend"
    }
}
