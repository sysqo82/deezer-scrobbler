package com.deezerscrobbler.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deezerscrobbler.viewmodel.MainViewModel

@Composable
fun MainWindow(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("🎵 Status") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                if (uiState.config.isAuthenticated) "👤 Account (${uiState.config.username})"
                                else "👤 Last.fm Account"
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("⚙ Settings") }
                    )
                }

                Box(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                    when (selectedTab) {
                        0 -> StatusTab(uiState, viewModel)
                        1 -> AccountTab(uiState, viewModel)
                        2 -> SettingsTab(uiState, viewModel)
                    }
                }
            }
        }
    }
}
