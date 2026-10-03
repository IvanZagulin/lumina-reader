package com.lumina.reader.ui.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.update.SemanticVersion

/**
 * Placeholder screen of the first iPhone builds (stage 2 of the iOS port). It
 * proves the whole chain: Compose renders on iOS, the version comes from the
 * app's Info.plist, and code from :shared is linked into the app.
 */
@Composable
fun IosPreviewApp(versionName: String, buildNumber: String, startRoute: String? = null) {
    val colors = if (isSystemInDarkTheme()) PreviewDarkColors else PreviewLightColors
    val icon = remember { luminaIconVector() }
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(128.dp).clip(RoundedCornerShape(28.dp)),
                    )
                    Spacer(Modifier.height(28.dp))
                    Text(
                        text = "Lumina Reader для iPhone",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Предварительная сборка $versionName ($buildNumber)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    for (line in sharedModuleReport(versionName)) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (startRoute != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Маршрут запуска: $startRoute",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** Lines that can only be produced by code from :shared, so they prove it is linked. */
internal fun sharedModuleReport(versionName: String): List<String> {
    val baseline = "1.0.0"
    val comparison = SemanticVersion.compare(versionName, baseline)
    val versionLine = if (comparison == null) {
        "Модуль :shared подключён, но версия «$versionName» не распознана"
    } else {
        "Модуль :shared: SemanticVersion.compare(\"$versionName\", \"$baseline\") = $comparison"
    }
    val formats = BookFormat.entries.joinToString(", ") { it.name }
    return listOf(versionLine, "Форматы книг: $formats")
}

// The two Lumina palettes of the Android app («Дневная библиотека» and
// «Вечерняя библиотека», ui/theme/Color.kt), reduced to what this screen uses.
private val PreviewLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFA64A23),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF4ECE1),
    onBackground = Color(0xFF2A211B),
    surface = Color(0xFFFFFAF3),
    onSurface = Color(0xFF2A211B),
    surfaceVariant = Color(0xFFEFE5D7),
    onSurfaceVariant = Color(0xFF6F6256),
)

private val PreviewDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFF0A06B),
    onPrimary = Color(0xFF4A1F05),
    background = Color(0xFF16110E),
    onBackground = Color(0xFFF1E6D8),
    surface = Color(0xFF1E1813),
    onSurface = Color(0xFFF1E6D8),
    surfaceVariant = Color(0xFF30261F),
    onSurfaceVariant = Color(0xFFB3A493),
)
