package com.lumina.reader.ui.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.update.SemanticVersion
import com.lumina.reader.ui.components.BookCoverModel
import com.lumina.reader.ui.components.DownloadChipState
import com.lumina.reader.ui.components.DownloadProgressChip
import com.lumina.reader.ui.components.LuminaCard
import com.lumina.reader.ui.components.SearchPill
import com.lumina.reader.ui.components.ShelfBookUi
import com.lumina.reader.ui.components.ShelfHeader
import com.lumina.reader.ui.components.ShelfHeaderStyle
import com.lumina.reader.ui.components.ShelfRow
import com.lumina.reader.ui.components.rememberShelfBookSize
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaReaderTheme
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.LuminaType

/**
 * The screen of the iPhone preview builds until the library moves here
 * (stage 8b). It shows the real design system shared with Android: the
 * Lumina theme (colours, Onest / Lora from the shared font resources), shelves
 * of generated covers on wooden planks, a card, the search pill and a
 * download chip. It also proves the chain: the version comes from the app's
 * Info.plist, and the report lines can only be produced by code from :shared.
 */
@Composable
fun IosPreviewApp(versionName: String, buildNumber: String, startRoute: String? = null) {
    LuminaReaderTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Lumina.colors.wall) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 24.dp)
            ) {
                PreviewHeader(versionName, buildNumber)
                PreviewShelves()
                PreviewControls(versionName, startRoute)
            }
        }
    }
}

@Composable
private fun PreviewHeader(versionName: String, buildNumber: String) {
    val icon = remember { luminaIconVector() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(LuminaShape.Tile)
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = "Lumina Reader",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Предварительная сборка $versionName ($buildNumber)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PreviewShelves() {
    val size = rememberShelfBookSize()
    Spacer(Modifier.height(12.dp))
    ShelfHeader(title = "Читаю сейчас", count = ReadingNow.size, onShowAll = {})
    ShelfRow(
        sectionKey = "preview:reading",
        books = ReadingNow,
        size = size,
        onOpen = { _, _ -> },
        onLongPress = {},
        captions = true,
        showNewDot = true
    )
    Spacer(Modifier.height(8.dp))
    ShelfHeader(
        title = "Ведьмак",
        count = Witcher.size,
        style = ShelfHeaderStyle.SERIES,
        readCount = Witcher.count { it.isFinished },
        totalCount = Witcher.size
    )
    ShelfRow(
        sectionKey = "preview:series",
        books = Witcher,
        size = size,
        onOpen = { _, _ -> },
        onLongPress = {},
        plateText = "Ведьмак · ${Witcher.count { it.isFinished }}/${Witcher.size}"
    )
}

@Composable
private fun PreviewControls(versionName: String, startRoute: String?) {
    val search: PreviewViewModel = viewModel { PreviewViewModel() }
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        SearchPill(query = search.query, onQueryChange = search::onQueryChange, placeholder = "Название или автор")
        Row(verticalAlignment = Alignment.CenterVertically) {
            DownloadProgressChip(
                state = DownloadChipState.Idle,
                formatLabel = "FB2",
                onStart = {},
                onCancel = {},
                onOpen = {},
                onRetry = {},
                best = true
            )
            Spacer(Modifier.width(8.dp))
            DownloadProgressChip(
                state = DownloadChipState.Running(0.42f),
                formatLabel = "EPUB",
                onStart = {},
                onCancel = {},
                onOpen = {},
                onRetry = {}
            )
        }
        LuminaCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "О СБОРКЕ",
                    style = LuminaType.eyebrow,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Библиотека, каталоги и чтение появятся в следующих обновлениях.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                for (line in sharedModuleReport(versionName)) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (startRoute != null) {
                    Text(
                        text = "Маршрут запуска: $startRoute",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

private fun sampleBook(
    id: Long,
    title: String,
    author: String,
    progress: Float = 0f,
    finished: Boolean = false,
    favorite: Boolean = false,
    series: Int? = null,
    thickness: Float = 3f
) = ShelfBookUi(
    id = id,
    cover = BookCoverModel(
        bookId = id,
        key = "preview:$id",
        title = title,
        author = author,
        image = null,
        thicknessDp = thickness
    ),
    title = title,
    author = author,
    progress = progress,
    isFavorite = favorite,
    isFinished = finished,
    isNew = !finished && progress <= 0f,
    seriesNumber = series
)

// Generated covers only: the preview has no book files yet.
private val ReadingNow = listOf(
    sampleBook(1, "Мастер и Маргарита", "Михаил Булгаков", progress = 0.62f, favorite = true, thickness = 4f),
    sampleBook(2, "Дюна", "Фрэнк Герберт", progress = 0.18f, thickness = 6f),
    sampleBook(3, "Война и мир", "Лев Толстой", finished = true, thickness = 6f),
    sampleBook(4, "Пикник на обочине", "Аркадий и Борис Стругацкие", thickness = 2f),
    sampleBook(5, "Шантарам", "Грегори Дэвид Робертс", progress = 0.05f, thickness = 5f),
)

private val Witcher = listOf(
    sampleBook(11, "Последнее желание", "Анджей Сапковский", finished = true, series = 1),
    sampleBook(12, "Меч предназначения", "Анджей Сапковский", progress = 0.4f, series = 2),
    sampleBook(13, "Кровь эльфов", "Анджей Сапковский", series = 3),
)
