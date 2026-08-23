package com.enetro.vobizvoip.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Side menu matching the reference dialer: a "Phone" header plus Contacts,
 * Settings, and Clear call history. (Help and feedback is intentionally omitted.)
 */
@Composable
fun AppDrawer(
    onContacts: () -> Unit,
    onSettings: () -> Unit,
    onClearHistory: () -> Unit,
) {
    ModalDrawerSheet {
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Phone",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 28.dp, bottom = 20.dp),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Filled.Contacts, contentDescription = null) },
            label = { Text("Contacts") },
            selected = false,
            onClick = onContacts,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Settings") },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
            label = { Text("Clear call history") },
            selected = false,
            onClick = onClearHistory,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
    }
}
