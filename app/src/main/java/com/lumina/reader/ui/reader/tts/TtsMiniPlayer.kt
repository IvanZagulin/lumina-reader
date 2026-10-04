package com.lumina.reader.ui.reader.tts

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.text.formatDecimal
import com.lumina.reader.core.tts.TtsPlaybackState
import com.lumina.reader.core.tts.TtsStatus
import com.lumina.reader.ui.reader.chrome.ReaderChromeCapsule
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.theme.LuminaDimens
import com.lumina.reader.ui.theme.LuminaShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Speeds the chip of the mini player steps through. */
internal val TTS_QUICK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** The quick speed after [current] (wrapping around); unknown speeds go to the nearest step first. */
internal fun nextTtsSpeed(current: Float): Float {
    val nearest = TTS_QUICK_SPEEDS.indices.minByOrNull { abs(TTS_QUICK_SPEEDS[it] - current) } ?: 0
    return if (abs(TTS_QUICK_SPEEDS[nearest] - current) > 0.01f) {
        TTS_QUICK_SPEEDS[nearest]
    } else {
        TTS_QUICK_SPEEDS[(nearest + 1) % TTS_QUICK_SPEEDS.size]
    }
}

/** «1,25×». */
internal fun formatSpeed(speed: Float): String {
    val text = formatDecimal(speed.toDouble(), 2).trimEnd('0').trimEnd('.')
    return text.replace('.', ',') + "×"
}

/** True when the spoken text plays or is about to. */
internal val TtsPlaybackState.isSpeaking: Boolean
    get() = status == TtsStatus.PLAYING || status == TtsStatus.PREPARING

/**
 * The read-aloud mini player (§4.2): a 56dp capsule with play/pause (an
 * equaliser while speaking), chapter and paragraph, a speed chip, next
 * paragraph and stop. Tapping the text opens the full player sheet.
 */
@Composable
internal fun TtsMiniPlayer(
    state: TtsPlaybackState,
    paragraphCount: Int,
    colors: ReaderChromeColors,
    reducedMotion: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val speaking = state.isSpeaking
    ReaderChromeCapsule(
        colors = colors,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LuminaDimens.ChromeMargin)
            .heightIn(min = LuminaDimens.CapsuleHeight)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onTogglePlay)
                    .semantics { contentDescription = if (speaking) "Пауза" else "Слушать" },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                    contentAlignment = Alignment.Center
                ) {
                    if (speaking) {
                        Equalizer(color = colors.onAccent, animate = !reducedMotion)
                    } else {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = colors.onAccent)
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(LuminaShape.Card)
                    .clickable(onClickLabel = "Открыть плеер", onClick = onOpenPlayer)
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = state.chapterTitle.ifBlank { "Глава ${state.chapterIndex + 1}" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = when {
                    state.status == TtsStatus.ERROR -> state.errorMessage ?: "Ошибка синтеза речи"
                    paragraphCount > 0 -> "Абзац ${state.paragraphIndex + 1} из $paragraphCount"
                    else -> ""
                }
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = if (state.status == TtsStatus.ERROR) colors.error else colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(LuminaShape.Pill)
                    .clickable(role = Role.Button, onClickLabel = "Скорость", onClick = onCycleSpeed)
                    .semantics { contentDescription = "Скорость ${formatSpeed(state.speechRate)}" },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clip(LuminaShape.Pill)
                        .background(colors.content.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatSpeed(state.speechRate),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.content,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                }
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Следующий абзац", tint = colors.content)
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Rounded.Close, contentDescription = "Остановить чтение вслух", tint = colors.muted)
            }
        }
    }
}

/** Three bars bouncing with offsets (900ms loop); still bars under reduced motion. */
@Composable
private fun Equalizer(color: Color, animate: Boolean) {
    val phase: State<Float>? = if (animate) {
        rememberInfiniteTransition(label = "equalizer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "equalizerPhase"
        )
    } else {
        null
    }
    Canvas(modifier = Modifier.size(18.dp)) {
        val p = phase?.value ?: 0.25f
        val barWidth = size.width / 5f
        for (i in 0 until 3) {
            val wave = (sin((p + i * 0.27f) * 2f * PI).toFloat() + 1f) / 2f
            val height = size.height * (0.35f + 0.65f * wave)
            val x = barWidth * (i * 2f)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}
