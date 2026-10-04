package com.lumina.reader.ui.reader.chrome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.text.formatDecimal
import com.lumina.reader.ui.reader.BookPosition
import com.lumina.reader.ui.reader.formatTimeLeft

/** «214 / 530», or «≈ 214 / 530» while the book is still being paginated. */
fun formatPageLabel(position: BookPosition): String {
    val approximate = if (position.isExact) "" else "≈ "
    return "$approximate${position.pageNumber} / ${position.totalPages}"
}

/** «42,3 %». */
fun formatPercentLabel(percent: Float): String =
    formatDecimal(percent.coerceIn(0f, 100f).toDouble(), 1, decimalSeparator = ',') + " %"

/** «≈ 12 мин до конца главы»; null when unknown. */
fun timeLeftLabel(minutesLeft: Int?): String? =
    formatTimeLeft(minutesLeft)?.let { "≈ $it до конца главы" }

private val FooterStyle = TextStyle(fontSize = 11.sp, fontFeatureSettings = "tnum")

/**
 * Footer of a page (§4.2): time left in the chapter on the left, the book
 * position on the right (tap toggles pages/percent, long press jumps) and an
 * optional thin progress line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderPageFooter(
    position: BookPosition,
    showPages: Boolean,
    timeLeft: String?,
    showProgressLine: Boolean,
    mutedColor: Color,
    textColor: Color,
    accentColor: Color,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timeLeft.orEmpty(),
                style = FooterStyle,
                color = mutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClickLabel = if (showPages) "Показать процент" else "Показать страницы книги",
                        onLongClickLabel = "Перейти к месту в книге",
                        onLongClick = onLongPress,
                        onClick = onToggle
                    )
                    .heightIn(min = 28.dp)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (showPages) formatPageLabel(position) else formatPercentLabel(position.percent),
                    style = FooterStyle,
                    fontWeight = FontWeight.Medium,
                    color = mutedColor,
                    maxLines = 1
                )
            }
        }
        if (showProgressLine) {
            val fraction = (position.percent / 100f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(1.5.dp)
                    .drawBehind {
                        drawRect(textColor.copy(alpha = 0.10f))
                        drawRect(
                            color = accentColor.copy(alpha = 0.60f),
                            topLeft = Offset.Zero,
                            size = Size(size.width * fraction, size.height)
                        )
                    }
            )
        }
    }
}
