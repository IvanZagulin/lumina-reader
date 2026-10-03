package com.lumina.reader.ui.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.lumina.reader.ui.theme.LuminaShape
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreenWithAchievements(
    viewModel: StatsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val advancedViewModel: AdvancedStatsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val advanced by advancedViewModel.uiState.collectAsState()
    var showAchievements by remember { mutableStateOf(false) }
    var showAnalytics by remember { mutableStateOf(false) }

    // The old floating buttons would sit under the dock: they are pills under the title now.
    StatsScreen(
        viewModel = viewModel,
        onBack = onBack,
        quickActions = {
            StatsQuickAction(
                icon = Icons.Default.Analytics,
                label = "Аналитика",
                onClick = { showAnalytics = true }
            )
            StatsQuickAction(
                icon = Icons.Default.EmojiEvents,
                label = "Достижения",
                onClick = { showAchievements = true }
            )
        }
    )

    if (showAnalytics) {
        ModalBottomSheet(
            onDismissRequest = { showAnalytics = false },
            shape = LuminaShape.Sheet,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp
        ) {
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
        ModalBottomSheet(
            onDismissRequest = { showAchievements = false },
            shape = LuminaShape.Sheet,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp
        ) {
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

@Composable
private fun StatsQuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = LuminaShape.Pill,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = statsCardBorder()
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
