package com.lumina.reader.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreenWithAchievements(
    viewModel: StatsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showAchievements by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        StatsScreen(
            viewModel = viewModel,
            onBack = onBack
        )

        if (!state.isLoading) {
            ExtendedFloatingActionButton(
                onClick = { showAchievements = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(18.dp),
                icon = {
                    Icon(
                        Icons.Default.EmojiEvents,
                        contentDescription = null
                    )
                },
                text = {
                    Text(
                        "Достижения",
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    }

    if (showAchievements) {
        ModalBottomSheet(
            onDismissRequest = { showAchievements = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.92f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                AchievementsPanel(state)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
