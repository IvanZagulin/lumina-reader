package com.lumina.reader.ui.reader.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.reader.MIN_SCREEN_BRIGHTNESS
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.reader.rememberScreenBrightness
import com.lumina.reader.ui.theme.LegacyM3Defaults

/**
 * Screen brightness (§7.3): small sun · slider · large sun · «Авто». Uses
 * the app-wide [com.lumina.reader.ui.reader.ScreenBrightness]: the screen
 * follows the slider while dragging and the value is saved when the drag ends.
 */
@Composable
fun BrightnessRow(
    colors: ReaderChromeColors,
    modifier: Modifier = Modifier
) {
    val screenBrightness = rememberScreenBrightness()
    var useSystem by remember { mutableStateOf(screenBrightness.useSystem()) }
    var brightness by remember { mutableFloatStateOf(screenBrightness.savedLevel()) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.LightMode,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(16.dp)
        )
        Slider(
            value = brightness,
            onValueChange = { value ->
                brightness = value
                if (useSystem) {
                    // Moving the slider means the reader wants manual brightness.
                    useSystem = false
                }
                screenBrightness.apply(useSystem = false, level = value)
            },
            onValueChangeFinished = {
                screenBrightness.save(useSystem = useSystem, level = brightness)
            },
            valueRange = MIN_SCREEN_BRIGHTNESS..1f,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = if (useSystem) colors.muted else colors.accent,
                inactiveTrackColor = colors.content.copy(alpha = 0.16f)
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Яркость экрана" }
        )
        Icon(
            imageVector = Icons.Rounded.LightMode,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        FilterChip(
            selected = useSystem,
            onClick = {
                val enabled = !useSystem
                useSystem = enabled
                screenBrightness.save(useSystem = enabled, level = brightness)
                screenBrightness.apply(useSystem = enabled, level = brightness)
            },
            label = { Text("Авто") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = colors.selectedBg,
                selectedLabelColor = colors.accent,
                labelColor = colors.content
            ),
            border = LegacyM3Defaults.filterChipBorder(selected = useSystem)
        )
    }
}
