package com.enetro.vobizvoip.data

/** Whether the local endpoint originated or received the call. */
enum class CallDirection { INCOMING, OUTGOING }

/** How a call ended, used to render the recents list. */
enum class CallResult {
    COMPLETED,
    MISSED,
    DECLINED,
    CANCELED,
    FAILED,
}

data class CallLogEntry(
    val id: String,
    val number: String,
    val direction: CallDirection,
    val result: CallResult,
    val startedAt: Long,
    val durationSeconds: Long,
)
