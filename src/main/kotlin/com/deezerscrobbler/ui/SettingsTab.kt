package com.deezerscrobbler.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deezerscrobbler.viewmodel.MainViewModel
import com.deezerscrobbler.viewmodel.UiState

@Composable
fun SettingsTab(uiState: UiState, viewModel: MainViewModel) {
    var swapArtistAndTitle by remember(uiState.config.swapArtistAndTitle) { mutableStateOf(uiState.config.swapArtistAndTitle) }
    var enableOfflineQueue by remember(uiState.config.enableOfflineQueue) { mutableStateOf(uiState.config.enableOfflineQueue) }
    var minSeconds by remember(uiState.config.minPlaybackSeconds) { mutableStateOf(uiState.config.minPlaybackSeconds.toString()) }
    var minPercent by remember(uiState.config.minPlaybackPercent) { mutableStateOf(uiState.config.minPlaybackPercent.toString()) }
    var showSavedMessage by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Scrobbler Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Swap Artist and Title", fontWeight = FontWeight.SemiBold)
                Text("Enable if window title shows 'Track - Artist' instead of 'Artist - Track'", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = swapArtistAndTitle,
                onCheckedChange = {
                    swapArtistAndTitle = it
                    showSavedMessage = false
                }
            )
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable Offline Queue", fontWeight = FontWeight.SemiBold)
                Text("Save scrobbles locally when offline or when network errors occur", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = enableOfflineQueue,
                onCheckedChange = {
                    enableOfflineQueue = it
                    showSavedMessage = false
                }
            )
        }

        HorizontalDivider()

        OutlinedTextField(
            value = minSeconds,
            onValueChange = {
                minSeconds = it
                showSavedMessage = false
            },
            label = { Text("Minimum Scrobble Seconds (e.g. 240s)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = minPercent,
            onValueChange = {
                minPercent = it
                showSavedMessage = false
            },
            label = { Text("Minimum Track Playback % (e.g. 50%)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val sec = minSeconds.toIntOrNull() ?: uiState.config.minPlaybackSeconds
                    val pct = minPercent.toIntOrNull() ?: uiState.config.minPlaybackPercent
                    val updated = uiState.config.copy(
                        swapArtistAndTitle = swapArtistAndTitle,
                        enableOfflineQueue = enableOfflineQueue,
                        minPlaybackSeconds = sec,
                        minPlaybackPercent = pct
                    )
                    viewModel.updateConfig(updated)
                    showSavedMessage = true
                }
            ) {
                Text("💾 Save Settings")
            }

            if (showSavedMessage) {
                Text(
                    text = "✔ Settings saved!",
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Text(
            text = "Config saved to ${System.getProperty("user.home")}\\.deezer-scrobbler\\config.json",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
