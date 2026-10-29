package com.deezerscrobbler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deezerscrobbler.model.PlaybackState
import com.deezerscrobbler.model.ScrobbleLogEntry
import com.deezerscrobbler.model.ScrobbleLogType
import com.deezerscrobbler.viewmodel.MainViewModel
import com.deezerscrobbler.viewmodel.UiState

@Composable
fun StatusTab(uiState: UiState, viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Track Status Card
        CurrentTrackCard(uiState, viewModel)

        // Activity Log Section
        Text(
            text = "Activity Log",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (uiState.logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No activity logged yet.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.logs) { log ->
                        LogItemRow(log)
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentTrackCard(uiState: UiState, viewModel: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (uiState.playbackState) {
                PlaybackState.PLAYING -> MaterialTheme.colorScheme.primaryContainer
                PlaybackState.PAUSED -> MaterialTheme.colorScheme.secondaryContainer
                PlaybackState.IDLE -> MaterialTheme.colorScheme.surfaceVariant
                PlaybackState.DEEZER_NOT_RUNNING -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (uiState.playbackState) {
                        PlaybackState.PLAYING -> "▶ NOW PLAYING ON DEEZER"
                        PlaybackState.PAUSED -> "⏸ PAUSED"
                        PlaybackState.IDLE -> "⏹ DEEZER IDLE"
                        PlaybackState.DEEZER_NOT_RUNNING -> "⚠ DEEZER APP NOT DETECTED"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (uiState.pendingQueueCount > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                        Text("${uiState.pendingQueueCount} Offline Queued")
                    }
                }
            }

            if (uiState.currentTrack != null) {
                Column {
                    Text(
                        text = uiState.currentTrack.title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = uiState.currentTrack.artist,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Progress indicator
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val threshold = 90 // 90 seconds default threshold
                    val progress = (uiState.playedSeconds.toFloat() / threshold).coerceIn(0f, 1f)

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = if (uiState.isScrobbled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${uiState.playedSeconds}s played", fontSize = 12.sp)
                        Text(
                            text = when {
                                uiState.isScrobbled -> "✔ Scrobbled"
                                uiState.isNowPlayingSent -> "✔ Now Playing sent"
                                else -> "Scrobbling soon..."
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (uiState.isScrobbled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                Text(
                    text = if (uiState.playbackState == PlaybackState.DEEZER_NOT_RUNNING)
                        "Open Deezer for Windows and start playing a track."
                    else "Waiting for music playback...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Quick actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.pendingQueueCount > 0) {
                    TextButton(onClick = { viewModel.retryOfflineQueue() }) {
                        Text("Sync Offline Queue (${uiState.pendingQueueCount})")
                    }
                }

                if (uiState.currentTrack != null && !uiState.isScrobbled && uiState.playbackState == PlaybackState.PLAYING) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { viewModel.forceScrobbleCurrent() }) {
                        Text("Scrobble Now")
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItemRow(log: ScrobbleLogEntry) {
    val (bgColor, textColor) = when (log.type) {
        ScrobbleLogType.NOW_PLAYING -> Pair(Color(0xFFE3F2FD), Color(0xFF1565C0))
        ScrobbleLogType.SCROBBLED -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32))
        ScrobbleLogType.QUEUED_OFFLINE -> Pair(Color(0xFFF3E5F5), Color(0xFF7B1FA2))
        ScrobbleLogType.ERROR -> Pair(Color(0xFFFFEBEE), Color(0xFFC62828))
        ScrobbleLogType.INFO -> Pair(Color(0xFFF5F5F5), Color(0xFF424242))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = log.message,
            fontSize = 13.sp,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = log.timestamp,
            fontSize = 11.sp,
            color = Color.Gray,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
