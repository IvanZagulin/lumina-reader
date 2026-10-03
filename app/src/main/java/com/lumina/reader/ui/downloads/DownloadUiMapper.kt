package com.lumina.reader.ui.downloads

import androidx.compose.runtime.Immutable
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.download.describeProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URLDecoder

/**
 * What the download UI shows about a task besides its state. The importer's
 * state map is keyed by URL only, so screens that start a download register
 * the title, author and cover here (in memory, UI only).
 */
@Immutable
data class DownloadMeta(
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    /** Headers for [coverUrl] (catalogue credentials for its own hosts only). */
    val coverHeaders: Map<String, String> = emptyMap(),
    val formatLabel: String? = null
)

/** In-memory labels of downloads, keyed like [com.lumina.reader.core.library.BookImporter.downloads]. */
object DownloadMetaRegistry {
    private val state = MutableStateFlow<Map<String, DownloadMeta>>(emptyMap())

    val meta: StateFlow<Map<String, DownloadMeta>> = state.asStateFlow()

    fun put(key: String, meta: DownloadMeta) {
        state.update { current -> if (current[key] == meta) current else current + (key to meta) }
    }
}

/** UI phase of a download (spec §7.9 state machine). */
enum class DownloadPhase { QUEUED, RUNNING, IMPORTING, DONE, IN_LIBRARY, FAILED }

/** One row of the downloads sheet / island. */
@Immutable
data class DownloadRowUi(
    val key: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val coverHeaders: Map<String, String>,
    val formatLabel: String?,
    val phase: DownloadPhase,
    /** 0..1 while transferring with a known size; null otherwise (indeterminate or not running). */
    val fraction: Float?,
    val statusText: String,
    /** The library book once the download finished. */
    val bookId: Long?,
    val errorMessage: String?
) {
    val isActive: Boolean
        get() = phase == DownloadPhase.QUEUED || phase == DownloadPhase.RUNNING || phase == DownloadPhase.IMPORTING

    val isFinished: Boolean
        get() = !isActive

    val percent: Int?
        get() = fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
}

/** Collapsed island while something is downloading. */
@Immutable
data class IslandProgress(
    val activeCount: Int,
    val doneInBatch: Int,
    val batchSize: Int,
    /** The first active download (title and cover shown for a single task). */
    val lead: DownloadRowUi,
    /** Overall 0..1, or null when it cannot be known (single task without Content-Length). */
    val fraction: Float?
) {
    val percent: Int?
        get() = fraction?.let { (it * 100).toInt().coerceIn(0, 100) }

    val label: String
        get() = if (activeCount <= 1 && batchSize <= 1) {
            "Загрузка «${lead.title}»"
        } else {
            val verb = if (activeCount % 10 in 2..4 && activeCount % 100 !in 12..14) "Загружаются" else "Загружается"
            "$verb $activeCount ${pluralBooks(activeCount)} · $doneInBatch/$batchSize"
        }
}

/** A finished download the island announces for a few seconds. */
@Immutable
sealed interface IslandAnnouncement {
    val row: DownloadRowUi

    data class Success(override val row: DownloadRowUi) : IslandAnnouncement
    data class Failure(override val row: DownloadRowUi) : IslandAnnouncement
}

/** Pure mapping from the importer's state map to UI rows (no Android, unit tested). */
object DownloadUiMapper {

    fun row(key: String, state: DownloadState, meta: DownloadMeta?): DownloadRowUi {
        val title = when {
            state is DownloadState.Completed && state.title.isNotBlank() -> state.title
            !meta?.title.isNullOrBlank() -> meta!!.title
            else -> titleFromUrl(key) ?: "Книга"
        }
        val phase = when (state) {
            DownloadState.Queued -> DownloadPhase.QUEUED
            is DownloadState.Running -> if (state.isImporting) DownloadPhase.IMPORTING else DownloadPhase.RUNNING
            is DownloadState.Completed -> if (state.alreadyInLibrary) DownloadPhase.IN_LIBRARY else DownloadPhase.DONE
            is DownloadState.Failed -> DownloadPhase.FAILED
        }
        return DownloadRowUi(
            key = key,
            title = title,
            author = meta?.author.orEmpty(),
            coverUrl = meta?.coverUrl,
            coverHeaders = meta?.coverHeaders.orEmpty(),
            formatLabel = meta?.formatLabel,
            phase = phase,
            fraction = (state as? DownloadState.Running)?.takeIf { !it.isImporting }?.fraction,
            statusText = statusText(state),
            bookId = (state as? DownloadState.Completed)?.bookId,
            errorMessage = (state as? DownloadState.Failed)?.message
        )
    }

    /**
     * Rows for the sheet: active downloads first (in start order), then
     * finished ones, most recently started first.
     */
    fun rows(downloads: Map<String, DownloadState>, meta: Map<String, DownloadMeta>): List<DownloadRowUi> {
        val all = downloads.map { (key, state) -> row(key, state, meta[key]) }
        val active = all.filter { it.isActive }
        val finished = all.filter { it.isFinished }.asReversed()
        return active + finished
    }

    fun statusText(state: DownloadState): String = when (state) {
        DownloadState.Queued -> "В очереди"
        is DownloadState.Running -> when {
            state.isImporting -> "Обработка…"
            state.bytesRead <= 0 -> "Подключение…"
            else -> describeProgress(state.bytesRead, state.totalBytes)
        }
        is DownloadState.Completed -> if (state.alreadyInLibrary) "Уже на полке" else "На полке"
        is DownloadState.Failed -> state.message
    }

    /** Keys that went from queued/running to completed/failed between two snapshots. */
    fun newlyFinished(previous: Map<String, DownloadState>, current: Map<String, DownloadState>): List<String> =
        current.filter { (key, state) -> state.isFinished && previous[key]?.isActive == true }.keys.toList()

    /**
     * Keys of the current "batch": every download seen active since the island
     * was last idle. Cancelled downloads (gone from the map) leave the batch;
     * the batch resets once nothing is active.
     */
    fun nextBatch(batch: Set<String>, current: Map<String, DownloadState>): Set<String> {
        val active = current.filterValues { it.isActive }.keys
        if (active.isEmpty()) return emptySet()
        return (batch.filterTo(LinkedHashSet()) { it in current }) + active
    }

    /** The collapsed island, or null when nothing is active. */
    fun progress(rows: List<DownloadRowUi>, batch: Set<String>): IslandProgress? {
        val active = rows.filter { it.isActive }
        val lead = active.firstOrNull() ?: return null
        val byKey = rows.associateBy { it.key }
        val keys = (batch + active.map { it.key }).filter { it in byKey }
        val size = keys.size.coerceAtLeast(active.size)
        val done = keys.count { byKey[it]?.isFinished == true }
        val fraction = if (size <= 1) {
            lead.fraction
        } else {
            keys.sumOf { key ->
                val row = byKey.getValue(key)
                when {
                    row.isFinished -> 1.0
                    row.phase == DownloadPhase.IMPORTING -> 1.0
                    else -> (row.fraction ?: 0f).toDouble()
                }
            }.div(size).toFloat().coerceIn(0f, 1f)
        }
        return IslandProgress(
            activeCount = active.size,
            doneInBatch = done,
            batchSize = size,
            lead = lead,
            fraction = fraction
        )
    }

    /** What the island announces for a download that just finished, or null. */
    fun announcementFor(key: String, state: DownloadState?, meta: DownloadMeta?): IslandAnnouncement? {
        if (state == null || !state.isFinished) return null
        val row = row(key, state, meta)
        return if (state is DownloadState.Failed) IslandAnnouncement.Failure(row) else IslandAnnouncement.Success(row)
    }

    /**
     * A readable title from a download URL ("…/Dune.fb2.zip" -> "Dune"), or
     * null for opaque URLs ("/b/123/fb2").
     */
    fun titleFromUrl(url: String): String? {
        val path = url.substringAfter("://", url)
            .substringAfter('/', "")
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
        val segment = path.substringAfterLast('/')
        if (segment.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
        var name = decoded
        repeat(2) {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in BOOK_EXTENSIONS) name = name.substringBeforeLast('.')
        }
        name = name.replace('_', ' ').trim()
        if (name.isEmpty() || name.lowercase() in OPAQUE_SEGMENTS || name.all { it.isDigit() }) return null
        if (name.none { it.isLetter() }) return null
        return name
    }

    private val BOOK_EXTENSIONS = setOf("fb2", "zip", "epub", "pdf", "txt", "fbz")
    private val OPAQUE_SEGMENTS = setOf("fb2", "epub", "pdf", "txt", "download", "get", "file", "mobi", "zip")
}

/** "книга", "книги", "книг". */
fun pluralBooks(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "книг"
        mod10 == 1 -> "книга"
        mod10 in 2..4 -> "книги"
        else -> "книг"
    }
}
