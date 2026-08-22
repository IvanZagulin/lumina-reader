package com.lumina.reader.ui.reader

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReadingTheme
import com.lumina.reader.core.preferences.AppDisplayController
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onSettingsChanged: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var useSystemBrightness by remember {
        mutableStateOf(AppDisplayController.useSystemBrightness(context))
    }
    var brightness by remember {
        mutableFloatStateOf(AppDisplayController.savedBrightness(context))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Настройки чтения",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Display brightness. Preview is applied directly to the window while
            // dragging, but SharedPreferences are written only when the drag ends.
            Text(
                text = "Яркость экрана",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Автояркость",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Использовать системную яркость Android",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = useSystemBrightness,
                    onCheckedChange = { enabled ->
                        useSystemBrightness = enabled
                        AppDisplayController.saveBrightness(
                            context = context,
                            useSystemBrightness = enabled,
                            brightness = brightness
                        )
                        activity?.let {
                            AppDisplayController.applyBrightness(
                                activity = it,
                                useSystemBrightness = enabled,
                                brightness = brightness
                            )
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = brightness,
                    onValueChange = { value ->
                        brightness = value
                        if (!useSystemBrightness) {
                            activity?.let {
                                AppDisplayController.applyBrightness(
                                    activity = it,
                                    useSystemBrightness = false,
                                    brightness = value
                                )
                            }
                        }
                    },
                    onValueChangeFinished = {
                        AppDisplayController.saveBrightness(
                            context = context,
                            useSystemBrightness = useSystemBrightness,
                            brightness = brightness
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !useSystemBrightness,
                    valueRange = 0.05f..1f
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${(brightness * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (useSystemBrightness) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
            Text(
                text = if (useSystemBrightness) {
                    "Яркостью управляет датчик освещения и системные настройки."
                } else {
                    "Эта яркость будет использоваться во всём Lumina Reader."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(20.dp))

            // 1. Reading Themes
            Text(
                text = "Цветовая тема",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(ReadingTheme.values()) { theme ->
                    val isSelected = settings.theme == theme
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            onSettingsChanged { it.copy(theme = theme) }
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(theme.bgComposeColor)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = theme.textComposeColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = theme.title,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Font Family Selector
            Text(
                text = "Гарнитура шрифта",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "Serif" to "Книжный (Serif)",
                    "SansSerif" to "Гротеск (Sans)",
                    "Monospace" to "Моноширинный",
                    "Cursive" to "Курсив"
                ).forEach { (family, label) ->
                    FilterChip(
                        selected = settings.fontFamily == family,
                        onClick = { onSettingsChanged { it.copy(fontFamily = family) } },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Font Size
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Размер шрифта (${settings.fontSizeSp} sp)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = {
                            if (settings.fontSizeSp > 12) {
                                onSettingsChanged { it.copy(fontSizeSp = it.fontSizeSp - 1) }
                            }
                        }
                    ) {
                        Text("A-", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = {
                            if (settings.fontSizeSp < 36) {
                                onSettingsChanged { it.copy(fontSizeSp = it.fontSizeSp + 1) }
                            }
                        }
                    ) {
                        Text("A+", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Line Spacing
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Интервал строк",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.25f to "Узкий", 1.45f to "Норм", 1.8f to "Широкий").forEach { (mult, label) ->
                        FilterChip(
                            selected = settings.lineSpacingMultiplier == mult,
                            onClick = { onSettingsChanged { it.copy(lineSpacingMultiplier = mult) } },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Page Margins
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Поля страницы",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(12 to "Узкие", 20 to "Средние", 32 to "Широкие").forEach { (pad, label) ->
                        FilterChip(
                            selected = settings.horizontalPaddingDp == pad,
                            onClick = { onSettingsChanged { it.copy(horizontalPaddingDp = pad) } },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 6. Bionic Reading Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bionic Reading",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Выделяет ключевые буквы для скорочтения",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.isBionicReadingEnabled,
                    onCheckedChange = { isChecked ->
                        onSettingsChanged { it.copy(isBionicReadingEnabled = isChecked) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 7. Reading Mode Selector (Paged vs Vertical Scroll)
            Text(
                text = "Режим перелистывания",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Paged Card
                Surface(
                    onClick = { onSettingsChanged { it.copy(isContinuousScroll = false) } },
                    shape = RoundedCornerShape(14.dp),
                    color = if (!settings.isContinuousScroll) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (!settings.isContinuousScroll) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📖", fontSize = 22.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Как книга",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (!settings.isContinuousScroll) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Листание вбок",
                            fontSize = 11.sp,
                            color = if (!settings.isContinuousScroll) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Vertical Scroll Card
                Surface(
                    onClick = { onSettingsChanged { it.copy(isContinuousScroll = true) } },
                    shape = RoundedCornerShape(14.dp),
                    color = if (settings.isContinuousScroll) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (settings.isContinuousScroll) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📜", fontSize = 22.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Лента",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (settings.isContinuousScroll) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Скролл вниз",
                            fontSize = 11.sp,
                            color = if (settings.isContinuousScroll) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 8. Hardware page navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Листать кнопками громкости",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Громкость вниз — вперёд, вверх — назад",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.volumeKeyNavigation,
                    onCheckedChange = { enabled ->
                        onSettingsChanged { it.copy(volumeKeyNavigation = enabled) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 9. Keep Screen On
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Экран не гаснет",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Поддерживать подсветку экрана во время чтения",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.keepScreenOn,
                    onCheckedChange = { isChecked ->
                        onSettingsChanged { it.copy(keepScreenOn = isChecked) }
                    }
                )
            }
        }
    }
}
