package com.enetro.vobizvoip.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enetro.vobizvoip.data.Contact
import com.enetro.vobizvoip.ui.theme.AnswerGreen

@Composable
fun DialerScreen(
    number: String,
    onNumberChange: (String) -> Unit,
    canCall: Boolean,
    onCall: (String) -> Unit,
    matches: List<Contact>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (matches.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(matches, key = { "${it.id}-${it.number}" }) { contact ->
                        ContactRow(contact = contact, onCall = onCall)
                    }
                }
            }
        }

        NumberBar(
            number = number,
            onBackspace = { onNumberChange(number.dropLast(1)) },
            onClear = { onNumberChange("") },
        )
        Spacer(Modifier.height(14.dp))
        DialPad(
            onDigit = { onNumberChange(number + it) },
            onPlus = { onNumberChange(number + "+") },
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { onCall(number) },
            enabled = canCall && number.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = AnswerGreen,
                contentColor = Color.White,
                disabledContainerColor = AnswerGreen.copy(alpha = 0.4f),
                disabledContentColor = Color.White.copy(alpha = 0.7f),
            ),
            modifier = Modifier.height(56.dp).widthIn(min = 140.dp),
        ) {
            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(8.dp))
            Text("Call", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(22.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberBar(number: String, onBackspace: () -> Unit, onClear: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.ifEmpty { "Enter number" },
            style = MaterialTheme.typography.displaySmall,
            color = if (number.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (number.isNotEmpty()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .combinedClickable(onClick = onBackspace, onLongClick = onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class DialKey(val digit: String, val letters: String)

private val dialRows = listOf(
    listOf(DialKey("1", ""), DialKey("2", "ABC"), DialKey("3", "DEF")),
    listOf(DialKey("4", "GHI"), DialKey("5", "JKL"), DialKey("6", "MNO")),
    listOf(DialKey("7", "PQRS"), DialKey("8", "TUV"), DialKey("9", "WXYZ")),
    listOf(DialKey("*", ""), DialKey("0", "+"), DialKey("#", "")),
)

@Composable
private fun DialPad(onDigit: (String) -> Unit, onPlus: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 24.dp),
    ) {
        dialRows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                row.forEach { key ->
                    DialButton(
                        key = key,
                        onClick = { onDigit(key.digit) },
                        onLongClick = if (key.digit == "0") onPlus else null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialButton(
    key: DialKey,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = key.digit,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (key.letters.isNotEmpty()) {
                Text(
                    text = key.letters,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
