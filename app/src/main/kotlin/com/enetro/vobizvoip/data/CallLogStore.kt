package com.enetro.vobizvoip.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight, offline call history persisted as JSON in SharedPreferences.
 * Newest entries first. Exposes a [StateFlow] so Compose can observe changes.
 */
class CallLogStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())

    val entries: StateFlow<List<CallLogEntry>> = _entries

    fun add(entry: CallLogEntry) {
        val updated = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = updated
        persist(updated)
    }

    fun clear() {
        _entries.value = emptyList()
        preferences.edit().remove(KEY_ENTRIES).apply()
    }

    private fun load(): List<CallLogEntry> {
        val raw = preferences.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    add(
                        CallLogEntry(
                            id = obj.getString("id"),
                            number = obj.getString("number"),
                            direction = CallDirection.valueOf(obj.getString("direction")),
                            result = CallResult.valueOf(obj.getString("result")),
                            startedAt = obj.getLong("startedAt"),
                            durationSeconds = obj.getLong("durationSeconds"),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun persist(entries: List<CallLogEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("number", entry.number)
                    .put("direction", entry.direction.name)
                    .put("result", entry.result.name)
                    .put("startedAt", entry.startedAt)
                    .put("durationSeconds", entry.durationSeconds),
            )
        }
        preferences.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES = "call_log"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 200
    }
}
