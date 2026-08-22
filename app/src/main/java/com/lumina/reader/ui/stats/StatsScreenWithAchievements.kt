package com.lumina.reader.ui.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreenWithAchievements(
    viewModel: StatsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val advancedViewModel: AdvancedStatsViewModel = viewModel()
    val advanced by advancedViewModel.uiState.collectAsState()
    var showAchievements by remember { mutableStateOf(false) }
    var showAnalytics by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        StatsScreen(
            viewModel = viewModel,
            onBack = onBack
        )

        if (!state.isLoading) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(18.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showAnalytics = true },
                    icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                    text = { Text("Аналитика", fontWeight = FontWeight.Bold) }
                )
                ExtendedFloatingActionButton(
                    onClick = { showAchievements = true },
                    icon = { Icon(Icons.Default.EmojiEvents, contentDescription = null) },
                    text = { Text("Достижения", fontWeight = FontWeight.Bold) }
                )
            }
        }
    }

    if (showAnalytics) {
        ModalBottomSheet(onDismissRequest = { showAnalytics = false }) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                AdvancedStatsPanel(base = state, advanced = advanced)
                Spacer(Modifier.height(36.dp))
            }
        }
    }

    if (showAchievements) {
        AchievementCompat.update(state, advanced)
        ModalBottomSheet(onDismissRequest = { showAchievements = false }) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                AchievementsPanelV2(base = state, advanced = advanced)
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}
