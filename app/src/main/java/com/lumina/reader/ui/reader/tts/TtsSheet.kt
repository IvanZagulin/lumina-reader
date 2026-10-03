package com.lumina.reader.ui.reader.tts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.tts.TtsPlaybackState
import com.lumina.reader.core.tts.TtsStatus
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.reader.chrome.ReaderModalSheet
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Sleep-timer choices of the player sheet, in minutes. */
internal val TTS_SLEEP_MINUTES = listOf(15, 30, 60)

/** Whole minutes until [endsAt] (rounded up); 0 when it has passed. */
internal fun minutesUntil(endsAt: Long, now: Long): Int =
    if (endsAt <= now) 0 else ceil((endsAt - now) / 60_000.0).toInt()

/** Snaps a slider value to the [step] grid. */
internal fun snapToStep(value: Float, step: Float, min: Float): Float =
    min + ((value - min) / step).roundToInt() * step

/**
 * The read-aloud sheet (§4.2 TtsSheet, §7.7): transport, speed 0.5–3.0,
 * pitch 0.5–2.0 and the sleep timer «15 мин · 30 мин · 60 мин · До конца
 * главы · Выкл». The speech engine offers no voice list, so the system
 * voice for the book language is used.
 */
@Composable
internal fun TtsSheet(
    state: TtsPlaybackState,
    colors: ReaderChromeColors,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSleepTimer: (Int?) -> Unit,
    onStopAtChapterEnd: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ReaderModalSheet(colors = colors, onDismiss = onDismiss, scrimAlpha = 0.15f) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = state.chapterTitle.ifBlank { "Чтение вслух" },
                style = MaterialTheme.typography.titleMedium,
                color = colors.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (state.status == TtsStatus.ERROR) {
                Text(
                    text = state.errorMessage ?: "Ошибка синтеза речи",
                    fontSize = 13.sp,
                    color = colors.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Предыдущий абзац", tint = colors.content)
                }
                Spacer(modifier = Modifier.size(16.dp))
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onTogglePlay, modifier = Modifier.size(64.dp)) {
                        Icon(
                            imageVector = if (state.isSpeaking) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isSpeaking) "Пауза" else "Слушать",
                            tint = colors.onAccent,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.size(16.dp))
                IconButton(onClick = onNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Следующий абзац", tint = colors.content)
                }
            }

            LabeledSlider(
                title = "Скорость",
                value = state.speechRate,
                valueLabel = ::formatSpeed,
                range = 0.5f..3f,
                step = 0.25f,
                colors = colors,
                onChange = onSpeedChange
            )
            LabeledSlider(
                title = "Тон голоса",
                value = state.pitch,
                valueLabel = { formatSpeed(it).removeSuffix("×") },
                range = 0.5f..2f,
                step = 0.1f,
                colors = colors,
                onChange = onPitchChange
            )

            Text(
                text = "Таймер сна",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.muted,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
            val endsAt = state.sleepTimerEndsAt
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(endsAt) {
                while (endsAt != null) {
                    now = System.currentTimeMillis()
                    delay(15_000)
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val chipColors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.selectedBg,
                    selectedLabelColor = colors.accent,
                    labelColor = colors.content
                )
                val border = BorderStroke(1.dp, colors.border)
                TTS_SLEEP_MINUTES.forEach { minutes ->
                    FilterChip(
                        selected = false,
                        onClick = { onSleepTimer(minutes) },
                        label = { Text("$minutes мин") },
                        colors = chipColors,
                        border = border
                    )
                }
                FilterChip(
                    selected = state.stopAtChapterEnd,
                    onClick = { onStopAtChapterEnd(!state.stopAtChapterEnd) },
                    label = { Text("До конца главы") },
                    colors = chipColors,
                    border = border
                )
                FilterChip(
                    selected = endsAt == null && !state.stopAtChapterEnd,
                    onClick = {
                        onSleepTimer(null)
                        onStopAtChapterEnd(false)
                    },
                    label = { Text("Выкл") },
                    colors = chipColors,
                    border = border
                )
            }
            val timerText = when {
                endsAt != null -> "Остановится через ${minutesUntil(endsAt, now)} мин"
                state.stopAtChapterEnd -> "Остановится в конце главы"
                else -> null
            }
            if (timerText != null) {
                Text(
                    text = timerText,
                    fontSize = 13.sp,
                    color = colors.accent,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onStop) {
                Text("Остановить чтение вслух", color = colors.error)
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    value: Float,
    valueLabel: (Float) -> String,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    colors: ReaderChromeColors,
    onChange: (Float) -> Unit
) {
    // The slider keeps its own value while dragging; the engine gets the final value.
    var local by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    // The label follows the thumb while dragging.
    val label = valueLabel(local)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.muted,
            modifier = Modifier.weight(1f)
        )
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.content)
    }
    val steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
    Slider(
        value = local,
        onValueChange = { local = snapToStep(it, step, range.start).coerceIn(range) },
        onValueChangeFinished = { onChange(local) },
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = colors.accent,
            activeTrackColor = colors.accent,
            inactiveTrackColor = colors.content.copy(alpha = 0.16f),
            activeTickColor = colors.onAccent.copy(alpha = 0.5f),
            inactiveTickColor = colors.content.copy(alpha = 0.24f)
        ),
        modifier = Modifier.semantics { stateDescription = "$title $label" }
    )
}
