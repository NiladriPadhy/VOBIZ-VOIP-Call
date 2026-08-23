package com.enetro.vobizvoip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.enetro.vobizvoip.data.CallLogEntry
import com.enetro.vobizvoip.data.Contact
import com.enetro.vobizvoip.data.Recording
import com.enetro.vobizvoip.data.filterByQuery
import com.enetro.vobizvoip.data.nameForNumber

/**
 * Home tab: a search bar over the recents list. Typing a query switches the body
 * to matching contacts (like the native dialer); an empty query shows the call log
 * grouped into Today / Yesterday / Older.
 */
@Composable
fun HomeScreen(
    entries: List<CallLogEntry>,
    recordings: List<Recording>,
    contacts: List<Contact>,
    player: RecordingPlayer,
    onOpenDrawer: () -> Unit,
    onCall: (String) -> Unit,
    onOpenInKeypad: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val nameResolver: (String) -> String? = remember(contacts) {
        { number -> contacts.nameForNumber(number) }
    }
    Column(modifier.fillMaxSize()) {
        HomeSearchBar(query = query, onQueryChange = { query = it }, onMenuClick = onOpenDrawer)
        if (query.isBlank()) {
            CallLogList(
                entries = entries,
                recordings = recordings,
                player = player,
                nameResolver = nameResolver,
                onDial = onCall,
                onOpenInKeypad = onOpenInKeypad,
            )
        } else {
            val matches = remember(query, contacts) { contacts.filterByQuery(query) }
            ContactSearchResults(query = query, matches = matches, onCall = onCall)
        }
    }
}

@Composable
private fun HomeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onMenuClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = "Open menu",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Search contacts",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 14.dp),
        )
    }
}

@Composable
private fun ContactSearchResults(
    query: String,
    matches: List<Contact>,
    onCall: (String) -> Unit,
) {
    val digits = query.filter(Char::isDigit)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (digits.length >= 3) {
            item(key = "call-typed") {
                ContactRow(
                    contact = Contact(id = "typed", name = "Call $query", number = query, photoUri = null),
                    onCall = onCall,
                )
            }
        }
        items(matches, key = { "${it.id}-${it.number}" }) { contact ->
            ContactRow(contact = contact, onCall = onCall)
        }
        if (matches.isEmpty() && digits.length < 3) {
            item(key = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "No matches",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
