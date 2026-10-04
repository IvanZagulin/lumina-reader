package com.lumina.reader.ui.reader.chrome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.reader.ReaderFonts
import com.lumina.reader.ui.theme.LuminaDimens
import com.lumina.reader.ui.theme.LuminaShape

/** Everything the book-level scrubber of the bottom bar needs. */
class ScrubberModel(
    val value: Float,
    val chapterStarts: FloatArray,
    val chapterLabel: String,
    val percentLabel: String,
    val stateDescription: String,
    val labelFor: (Float) -> ScrubberLabel,
    val onJump: (Float) -> Unit
)

/**
 * The bottom panel (§4.2): the book scrubber and five actions. A rounded
 * capsule 12dp above the navigation bar; cells are at least 64dp tall.
 */
@Composable
fun ReaderBottomBar(
    colors: ReaderChromeColors,
    scrubber: ScrubberModel?,
    isTtsPlaying: Boolean,
    showTextActions: Boolean,
    onOpenNavigation: () -> Unit,
    onOpenSearch: () -> Unit,
    onListen: () -> Unit,
    onToggleTheme: () -> Unit,
    onLongPressTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = LuminaDimens.ChromeMargin, end = LuminaDimens.ChromeMargin, bottom = 12.dp)
    ) {
        ReaderChromeCapsule(
            colors = colors,
            shape = LuminaShape.Panel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (scrubber != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = scrubber.chapterLabel,
                            style = TabularLabel,
                            color = colors.muted,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(52.dp)
                        )
                        ChapterScrubber(
                            value = scrubber.value,
                            chapterStarts = scrubber.chapterStarts,
                            colors = colors,
                            labelFor = scrubber.labelFor,
                            stateDescription = scrubber.stateDescription,
                            onJump = scrubber.onJump,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = scrubber.percentLabel,
                            style = TabularLabel,
                            color = colors.muted,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(44.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionCell(
                        label = "Оглавление",
                        colors = colors,
                        onClick = onOpenNavigation,
                        icon = {
                            Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, contentDescription = null)
                        }
                    )
                    if (showTextActions) {
                        ActionCell(
                            label = "Поиск",
                            colors = colors,
                            onClick = onOpenSearch,
                            icon = { Icon(Icons.Rounded.Search, contentDescription = null) }
                        )
                        ActionCell(
                            label = if (isTtsPlaying) "Пауза" else "Слушать",
                            colors = colors,
                            onClick = onListen,
                            icon = {
                                if (isTtsPlaying) {
                                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = colors.accent)
                                } else {
                                    Icon(Icons.Rounded.Headphones, contentDescription = null)
                                }
                            }
                        )
                    }
                    ActionCell(
                        label = "Тема",
                        colors = colors,
                        onClick = onToggleTheme,
                        onLongClick = onLongPressTheme,
                        onLongClickLabel = "Выбрать тему",
                        icon = {
                            Icon(
                                imageVector = if (colors.isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = null
                            )
                        }
                    )
                    ActionCell(
                        label = "Аа",
                        colors = colors,
                        onClick = onOpenSettings,
                        contentLabel = "Шрифт и оформление",
                        icon = {
                            Text(
                                text = "Аа",
                                fontFamily = ReaderFonts.Literata.family,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.content
                            )
                        }
                    )
                }
            }
        }
    }
}

private val TabularLabel = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum"
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionCell(
    label: String,
    colors: ReaderChromeColors,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    contentLabel: String = label
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                role = Role.Button,
                onClickLabel = contentLabel,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = colors.content,
            maxLines = 1
        )
    }
}
