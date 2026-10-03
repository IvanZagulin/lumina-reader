package com.lumina.reader.ui.reader

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.FIRST_LINE_INDENT_OPTIONS_EM
import com.lumina.reader.core.model.LINE_SPACING_OPTIONS
import com.lumina.reader.core.model.MARGIN_OPTIONS_DP
import com.lumina.reader.core.model.PARAGRAPH_SPACING_OPTIONS_EM
import com.lumina.reader.core.model.PageTurnAnimation
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReaderTapZones
import com.lumina.reader.core.model.ReaderTextAlign
import com.lumina.reader.core.model.ReaderThemeMode
import com.lumina.reader.core.model.ReadingTheme
import com.lumina.reader.core.model.nearestOption
import com.lumina.reader.core.model.resetToDefaults
import com.lumina.reader.core.model.withThemeChoice
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.reader.chrome.ReaderModalSheet
import com.lumina.reader.ui.reader.settings.BrightnessRow
import com.lumina.reader.ui.reader.settings.FontPreviewChip
import com.lumina.reader.ui.reader.settings.IconSegmentRow
import com.lumina.reader.ui.reader.settings.PageTurnStyleCard
import com.lumina.reader.ui.reader.settings.ThemeSwatch
import com.lumina.reader.ui.reader.settings.ThemeSwatchSpacing
import com.lumina.reader.ui.reader.settings.drawLineSpacingIcon
import com.lumina.reader.ui.reader.settings.drawMarginIcon
import com.lumina.reader.ui.theme.LuminaShape
import kotlin.math.roundToInt

private const val MIN_FONT_SIZE = 12
private const val MAX_FONT_SIZE = 32

/**
 * «Аа» (§7.3): brightness and themes on top, then the tabs «Текст»,
 * «Страница», «Чтение». The sheet is low (62 % of the screen) so the live
 * page above previews every change. Changes that repaginate in quick steps
 * (font size) go through [onSettingsChangedDebounced].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsSheet(
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    reducedMotion: Boolean,
    curlSupported: Boolean,
    onSettingsChanged: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onSettingsChangedDebounced: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ReaderModalSheet(colors = colors, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .padding(horizontal = 16.dp)
        ) {
            BrightnessRow(colors = colors)
            ThemeRow(settings, colors, reducedMotion, onSettingsChanged)
            if (settings.themeMode == ReaderThemeMode.SYSTEM) {
                DayNightRow(settings, colors, onSettingsChanged)
            }
            Spacer(modifier = Modifier.height(10.dp))
            val tabs = listOf("Текст", "Страница", "Чтение")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, title ->
                    SegmentedButton(
                        selected = tab == index,
                        onClick = { tab = index },
                        shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                        icon = {},
                        label = { Text(title, maxLines = 1) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Crossfade(
                targetState = tab,
                animationSpec = tween(150),
                label = "settingsTab",
                modifier = Modifier.weight(1f)
            ) { current ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                ) {
                    when (current) {
                        0 -> TextTab(settings, colors, onSettingsChanged, onSettingsChangedDebounced)
                        1 -> PageTab(settings, colors, onSettingsChanged)
                        else -> ReadingTab(settings, colors, reducedMotion, curlSupported, onSettingsChanged)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeRow(
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    reducedMotion: Boolean,
    onSettingsChanged: ((ReaderSettings) -> ReaderSettings) -> Unit
) {
    val auto = settings.themeMode == ReaderThemeMode.SYSTEM
    LazyRow(
        horizontalArrangement = ThemeSwatchSpacing,
        contentPadding = PaddingValues(vertical = 6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        item(key = "auto") {
            ThemeSwatch(
                label = "Авто",
                theme = settings.dayTheme,
                nightTheme = settings.nightTheme,
                selected = auto,
                colors = colors,
                reducedMotion = reducedMotion,
                onClick = { onSettingsChanged { it.withThemeChoice(null) } }
            )
        }
        items(ReadingTheme.entries, key = { it.name }) { theme ->
            ThemeSwatch(
                label = theme.title,
                theme = theme,
                selected = !auto && settings.theme == theme,
                colors = colors,
                reducedMotion = reducedMotion,
                onClick = { onSettingsChanged { it.withThemeChoice(theme) } }
            )
        }
    }
}

/** «Днём: [Кремовая ▾]   Ночью: [Чёрная ▾]» under the «Авто» tile. */
@Composable
private fun DayNightRow(
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    onSettingsChanged: ((ReaderSettings) -> ReaderSettings) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemeDropdownChip(
            prefix = "Днём",
            selected = settings.dayTheme,
            colors = colors,
            onSelect = { theme -> onSettingsChanged { it.copy(dayTheme = theme) } }
        )
        ThemeDropdownChip(
            prefix = "Ночью",
            selected = settings.nightTheme,
            colors = colors,
            onSelect = { theme -> onSettingsChanged { it.copy(nightTheme = theme) } }
        )
    }
}

@Composable
private fun ThemeDropdownChip(
    prefix: String,
    selected: ReadingTheme,
    colors: ReaderChromeColors,
    onSelect: (ReadingTheme) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("$prefix: ${selected.title}", maxLines = 1) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = colors.content,
                trailingIconContentColor = colors.muted
            ),
            border = BorderStroke(1.dp, colors.border)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surface
        ) {
            ReadingTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = { Text(theme.title, color = colors.content) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(theme.bgComposeColor, CircleShape)
                                .border(1.dp, theme.textComposeColor.copy(alpha = 0.3f), CircleShape)
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(theme)
                    }
                )
            }
        }
    }
}

@Composable
private fun TextTab(
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    onSettingsChanged: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onSettingsChangedDebounced: ((ReaderSettings) -> ReaderSettings) -> Unit
) {
    SectionTitle("Шрифт", colors)
    val selectedFont = ReaderFonts.byId(settings.fontFamily)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ReaderFonts.all, key = { it.id }) { font ->
            FontPreviewChip(
                font = font,
                selected = font.id == selectedFont.id,
                colors = colors,
                onClick = { onSettingsChanged { it.copy(fontFamily = font.id) } }
            )
        }
    }

    // The slider keeps its own value while it moves; the page reflows when it pauses.
    var fontSize by remember { mutableIntStateOf(settings.fontSizeSp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)) }
    fun setFontSize(value: Int) {
        val size = value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        if (size == fontSize) return
        fontSize = size
        onSettingsChangedDebounced { it.copy(fontSizeSp = size) }
    }
    SectionTitle("Размер · $fontSize", colors)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(label = "A−", fontSize = 14, description = "Уменьшить шрифт", colors = colors) { setFontSize(fontSize - 1) }
        Slider(
            value = fontSize.toFloat(),
            onValueChange = { setFontSize(it.roundToInt()) },
            valueRange = MIN_FONT_SIZE.toFloat()..MAX_FONT_SIZE.toFloat(),
            steps = MAX_FONT_SIZE - MIN_FONT_SIZE - 1,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.content.copy(alpha = 0.16f),
                activeTickColor = colors.onAccent.copy(alpha = 0.5f),
                inactiveTickColor = colors.content.copy(alpha = 0.24f)
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { stateDescription = "$fontSize пунктов" }
        )
        StepButton(label = "A+", fontSize = 20, description = "Увеличить шрифт", colors = colors) { setFontSize(fontSize + 1) }
    }

    SectionTitle("Межстрочный интервал", colors)
    IconSegmentRow(
        options = LINE_SPACING_OPTIONS,
        selected = nearestOption(settings.lineSpacingMultiplier, LINE_SPACING_OPTIONS),
        colors = colors,
        label = { formatDecimal(it) },
        onSelect = { value -> onSettingsChanged { it.copy(lineSpacingMultiplier = value) } },
        drawIcon = { option, color -> drawLineSpacingIcon(option, color) }
    )
}

@Composable
private fun PageTab(
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    onSettingsChanged: ((ReaderSettings) -> ReaderSettings) -> Unit
) {
    SectionTitle("Поля", colors)
    IconSegmentRow(
        options = MARGIN_OPTIONS_DP,
        selected = nearestOption(settings.horizontalPaddingDp, MARGIN_OPTIONS_DP),
        colors = colors,
        label = { "$it" },
        onSelect = { value -> onSettingsChanged { it.copy(horizontalPaddingDp = value) } },
        drawIcon = { option, color -> drawMarginIcon(option, color) }
    )

    SectionTitle("Выравнивание", colors)
    Segmented(
        options = listOf(ReaderTextAlign.START to "По левому краю", ReaderTextAlign.JUSTIFY to "По ширине"),
        selected = settings.textAlign,
        onSelect = { value -> onSettingsChanged { it.copy(textAlign = value) } }
    )
    SwitchRow(
        title = "Переносы слов",
        subtitle = "Ровнее строки при выравнивании по ширине",
        checked = settings.hyphenation,
        colors = colors,
        onCheckedChange = { value -> onSettingsChanged { it.copy(hyphenation = value) } }
    )

    SectionTitle("Отступ первой строки", colors)
    Segmented(
        options = FIRST_LINE_INDENT_OPTIONS_EM.map { it to if (it == 0f) "Нет" else formatDecimal(it) + " em" },
        selected = nearestOption(settings.firstLineIndentEm, FIRST_LINE_INDENT_OPTIONS_EM),
        onSelect = { value -> onSettingsChanged { it.copy(firstLineIndentEm = value) } }
    )

    SectionTitle("Между абзацами", colors)
    val spacingLabels = listOf("Нет", "Малый", "Обычный", "Большой")
    Segmented(
        options = PARAGRAPH_SPACING_OPTIONS_EM.mapIndexed { index, value -> value to spacingLabels[index] },
        selected = nearestOption(settings.paragraphSpacingEm, PARAGRAPH_SPACING_OPTIONS_EM),
        onSelect = { value -> onSettingsChanged { it.copy(paragraphSpacingEm = value) } }
    )
}

@Composable
private fun ReadingTab(
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    reducedMotion: Boolean,
    curlSupported: Boolean,
    onSettingsChanged: ((ReaderSettings) -> ReaderSettings) -> Unit
) {
    SectionTitle("Режим", colors)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ModeCard(
            title = "Страницы",
            scroll = false,
            selected = !settings.isContinuousScroll,
            colors = colors,
            onClick = { onSettingsChanged { it.copy(isContinuousScroll = false) } }
        )
        ModeCard(
            title = "Лента",
            scroll = true,
            selected = settings.isContinuousScroll,
            colors = colors,
            onClick = { onSettingsChanged { it.copy(isContinuousScroll = true) } }
        )
    }

    SectionTitle("Перелистывание", colors)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PageTurnAnimation.entries.forEach { style ->
            val enabled = !settings.isContinuousScroll && (style != PageTurnAnimation.CURL || curlSupported)
            PageTurnStyleCard(
                style = style,
                selected = settings.pageTurnAnimation == style,
                enabled = enabled,
                colors = colors,
                reducedMotion = reducedMotion,
                onClick = { onSettingsChanged { it.copy(pageTurnAnimation = style) } }
            )
        }
    }
    if (!curlSupported) {
        Text(
            text = "«Загиб» недоступен на этом устройстве",
            fontSize = 12.sp,
            color = colors.muted,
            modifier = Modifier.padding(top = 4.dp)
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    SwitchRow("Листать кнопками громкости", null, settings.volumeKeyNavigation, colors) { value ->
        onSettingsChanged { it.copy(volumeKeyNavigation = value) }
    }
    SwitchRow("Не гасить экран", null, settings.keepScreenOn, colors) { value ->
        onSettingsChanged { it.copy(keepScreenOn = value) }
    }
    SwitchRow("Бионическое чтение", "Выделяет начала слов для быстрого чтения", settings.isBionicReadingEnabled, colors) { value ->
        onSettingsChanged { it.copy(isBionicReadingEnabled = value) }
    }
    SwitchRow("Время до конца главы", null, settings.showTimeLeft, colors) { value ->
        onSettingsChanged { it.copy(showTimeLeft = value) }
    }
    SwitchRow("Полоса прогресса", null, settings.showProgressLine, colors) { value ->
        onSettingsChanged { it.copy(showProgressLine = value) }
    }
    SwitchRow("Номера страниц вместо %", null, settings.showBookPagesInFooter, colors) { value ->
        onSettingsChanged { it.copy(showBookPagesInFooter = value) }
    }

    SectionTitle("Зоны касания", colors)
    Segmented(
        options = listOf(ReaderTapZones.CLASSIC to "Классика", ReaderTapZones.ONE_HAND to "Одной рукой"),
        selected = settings.tapZones,
        onSelect = { value -> onSettingsChanged { it.copy(tapZones = value) } }
    )
    SwitchRow(
        title = "Поменять стороны",
        subtitle = "Касание слева листает вперёд, справа — назад",
        checked = settings.tapZonesInverted,
        colors = colors,
        onCheckedChange = { value -> onSettingsChanged { it.copy(tapZonesInverted = value) } }
    )

    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = { onSettingsChanged { it.resetToDefaults() } }) {
        Text("Сбросить настройки", color = colors.accent)
    }
}

@Composable
private fun SectionTitle(text: String, colors: ReaderChromeColors) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = colors.muted,
        modifier = Modifier
            .padding(top = 14.dp, bottom = 8.dp)
            .semantics { heading() }
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    colors: ReaderChromeColors,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = colors.content)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                label = { Text(label, maxLines = 1, fontSize = 13.sp) }
            )
        }
    }
}

@Composable
private fun StepButton(
    label: String,
    fontSize: Int,
    description: String,
    colors: ReaderChromeColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.content.copy(alpha = 0.06f),
        contentColor = colors.content,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** «Страницы» / «Лента»: 120×88dp cards with a tiny illustration. */
@Composable
private fun ModeCard(
    title: String,
    scroll: Boolean,
    selected: Boolean,
    colors: ReaderChromeColors,
    onClick: () -> Unit
) {
    Surface(
        shape = LuminaShape.Tile,
        color = if (selected) colors.accent.copy(alpha = 0.08f) else colors.content.copy(alpha = 0.04f),
        border = if (selected) BorderStroke(2.dp, colors.accent) else BorderStroke(1.dp, colors.border),
        contentColor = colors.content,
        modifier = Modifier
            .size(width = 120.dp, height = 88.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val ink = (if (selected) colors.accent else colors.muted)
            Canvas(modifier = Modifier.size(width = 56.dp, height = 40.dp)) {
                val stroke = Stroke(width = 1.5.dp.toPx())
                val radius = CornerRadius(3.dp.toPx())
                if (scroll) {
                    drawRoundRect(ink, Offset(size.width * 0.2f, -4f), Size(size.width * 0.6f, size.height + 8f), radius, style = stroke)
                    for (i in 0 until 6) {
                        val y = size.height * (0.08f + i * 0.17f)
                        drawLine(ink, Offset(size.width * 0.3f, y), Offset(size.width * 0.7f, y), 1.dp.toPx())
                    }
                } else {
                    drawRoundRect(ink, Offset(size.width * 0.04f, 0f), Size(size.width * 0.44f, size.height), radius, style = stroke)
                    drawRoundRect(ink, Offset(size.width * 0.52f, 0f), Size(size.width * 0.44f, size.height), radius, style = stroke)
                    for (i in 0 until 4) {
                        val y = size.height * (0.22f + i * 0.18f)
                        drawLine(ink, Offset(size.width * 0.10f, y), Offset(size.width * 0.42f, y), 1.dp.toPx())
                        drawLine(ink, Offset(size.width * 0.58f, y), Offset(size.width * 0.90f, y), 1.dp.toPx())
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.accent else colors.content
            )
        }
    }
}

/** «1,45», «2», «1,5». */
internal fun formatDecimal(value: Float): String {
    val rounded = (value * 100).roundToInt()
    return if (rounded % 100 == 0) {
        (rounded / 100).toString()
    } else {
        val text = (rounded / 100.0).toString().trimEnd('0').trimEnd('.')
        text.replace('.', ',')
    }
}
