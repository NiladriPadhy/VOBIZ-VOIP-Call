package com.enetro.vobizvoip.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Reads dialable device contacts through [ContactsContract]. Contacts are loaded
 * once permission is granted and cached in memory (POC scale) so both the dialer
 * UI and the call-log name resolution can query them synchronously.
 */
class ContactsRepository(private val context: Context) {
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun refresh() {
        _contacts.value = withContext(Dispatchers.IO) { query() }
    }

    /** Filters the cached contacts by name (case-insensitive) or number digits. */
    fun search(query: String): List<Contact> = _contacts.value.filterByQuery(query)

    /** Best-effort contact name for a number, matched on the last 10 digits. */
    fun nameFor(number: String): String? = _contacts.value.nameForNumber(number)

    private fun query(): List<Contact> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
        )
        val seenNumbers = HashSet<String>()
        val results = ArrayList<Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIndex)?.trim().orEmpty()
                if (number.isEmpty()) continue
                val dedupeKey = number.filter(Char::isDigit).takeLast(10).ifEmpty { number }
                if (!seenNumbers.add(dedupeKey)) continue
                results += Contact(
                    id = cursor.getString(idIndex).orEmpty(),
                    name = cursor.getString(nameIndex)?.trim().orEmpty().ifEmpty { number },
                    number = number,
                    photoUri = cursor.getString(photoIndex),
                )
            }
        }
        return results
    }
}

/** Best-effort contact name for a number, matched on the last 10 digits. */
fun List<Contact>.nameForNumber(number: String): String? {
    val digits = number.filter(Char::isDigit).takeLast(10)
    if (digits.isEmpty()) return null
    return firstOrNull { it.number.filter(Char::isDigit).takeLast(10) == digits }?.name
}

/** Filters contacts by name (case-insensitive) or by number digits. */
fun List<Contact>.filterByQuery(query: String): List<Contact> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    val digits = trimmed.filter(Char::isDigit)
    return filter { contact ->
        contact.name.contains(trimmed, ignoreCase = true) ||
            (digits.isNotEmpty() && contact.number.filter(Char::isDigit).contains(digits))
    }
}
