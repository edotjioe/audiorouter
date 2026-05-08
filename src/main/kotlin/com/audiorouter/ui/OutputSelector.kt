package com.audiorouter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OutputSelector(
    availableSinks: List<Pair<Int, String>>,
    selectedSinkName: String,
    onSinkSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = availableSinks.firstOrNull { it.second == selectedSinkName }?.second
        ?.substringAfterLast('.') ?: selectedSinkName.ifBlank { "Select output…" }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Output:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(displayName, maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (availableSinks.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No output devices found") },
                        onClick = { expanded = false },
                        enabled = false
                    )
                } else {
                    availableSinks.forEach { (_, name) ->
                        DropdownMenuItem(
                            text = { Text(name.substringAfterLast('.').ifBlank { name }) },
                            onClick = {
                                onSinkSelected(name)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
