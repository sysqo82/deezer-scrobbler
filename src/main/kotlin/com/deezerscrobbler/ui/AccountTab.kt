package com.deezerscrobbler.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deezerscrobbler.viewmodel.MainViewModel
import com.deezerscrobbler.viewmodel.UiState
import java.awt.Desktop
import java.net.URI

@Composable
fun AccountTab(uiState: UiState, viewModel: MainViewModel) {
    var apiKeyInput by remember(uiState.config.apiKey) { mutableStateOf(uiState.config.apiKey) }
    var apiSecretInput by remember(uiState.config.apiSecret) { mutableStateOf(uiState.config.apiSecret) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Last.fm Account Connection",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (uiState.config.isAuthenticated) {
            // Connected View
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "✔ Connected to Last.fm",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                    Text(
                        text = "Account: ${uiState.config.username}",
                        fontSize = 16.sp
                    )
                    Button(
                        onClick = { viewModel.disconnectLastFm() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Disconnect Account")
                    }
                }
            }
        } else {
            // Setup & Auth Flow
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Step 1: Get Last.fm API Key",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "1. Visit Last.fm API creation page to get your free API key and Secret.\n2. Enter Application Name as 'Deezer Scrobbler' and leave callback empty.",
                        fontSize = 13.sp
                    )
                    OutlinedButton(onClick = {
                        try {
                            if (Desktop.isDesktopSupported()) {
                                Desktop.getDesktop().browse(URI("https://www.last.fm/api/account/create"))
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }) {
                        Text("🔗 Open Last.fm API Creation Page")
                    }
                }
            }

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = {
                    apiKeyInput = it.trim()
                    viewModel.updateConfig(uiState.config.copy(apiKey = it.trim()))
                },
                label = { Text("Last.fm API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = apiSecretInput,
                onValueChange = {
                    apiSecretInput = it.trim()
                    viewModel.updateConfig(uiState.config.copy(apiSecret = it.trim()))
                },
                label = { Text("Last.fm Shared Secret") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.startLastFmAuth(apiKeyInput, apiSecretInput) },
                    enabled = apiKeyInput.isNotBlank() && apiSecretInput.isNotBlank() && !uiState.isAuthenticating
                ) {
                    Text("1. Authorize in Browser")
                }

                Button(
                    onClick = { viewModel.completeLastFmAuth() },
                    enabled = apiKeyInput.isNotBlank() && apiSecretInput.isNotBlank() && !uiState.isAuthenticating,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("2. Complete Login")
                }
            }

            if (!uiState.authStatusMessage.isNullOrBlank()) {
                Text(
                    text = uiState.authStatusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (uiState.authStatusMessage.contains("Successfully")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
