package com.lumina.reader.core.download

import com.lumina.reader.core.text.formatDecimal

/** State of one book download, keyed by its URL in [DownloadStates]. */
sealed interface DownloadState {
    /** Waiting for a free download slot. */
    data object Queued : DownloadState

    /**
     * Transferring ([isImporting] = false) or adding the received file to the
     * library ([isImporting] = true). [totalBytes] is null when the server
     * does not send Content-Length.
     */
    data class Running(
        val bytesRead: Long,
        val totalBytes: Long?,
        val isImporting: Boolean = false
    ) : DownloadState {
        /** 0..1 when the size is known, otherwise null (indeterminate). */
        val fraction: Float?
            get() = if (totalBytes != null && totalBytes > 0) {
                (bytesRead.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
            } else {
                null
            }

        val percent: Int?
            get() = fraction?.let { (it * 100).toInt() }
    }

    /** The book is in the library; [alreadyInLibrary] when it was a duplicate. */
    data class Completed(
        val bookId: Long,
        val title: String,
        val alreadyInLibrary: Boolean = false
    ) : DownloadState

    data class Failed(val message: String) : DownloadState

    val isActive: Boolean
        get() = this is Queued || this is Running

    val isFinished: Boolean
        get() = this is Completed || this is Failed
}

sealed interface DownloadEvent {
    val key: String

    data class Enqueued(override val key: String) : DownloadEvent
    data class Progress(override val key: String, val bytesRead: Long, val totalBytes: Long?) : DownloadEvent
    data class Importing(override val key: String) : DownloadEvent
    data class Succeeded(
        override val key: String,
        val bookId: Long,
        val title: String,
        val alreadyInLibrary: Boolean
    ) : DownloadEvent

    data class Failed(override val key: String, val message: String) : DownloadEvent

    /** Cancelled or dismissed: the key disappears from the map. */
    data class Cleared(override val key: String) : DownloadEvent
}

/** Pure reducer for the per-URL download map. */
object DownloadStates {

    /** A download may (re)start unless it is already queued or running. */
    fun canStart(current: DownloadState?): Boolean = current == null || current.isFinished

    fun reduce(states: Map<String, DownloadState>, event: DownloadEvent): Map<String, DownloadState> {
        val current = states[event.key]
        val next: DownloadState? = when (event) {
            is DownloadEvent.Enqueued ->
                if (canStart(current)) DownloadState.Queued else current
            is DownloadEvent.Progress ->
                if (current != null && current.isActive) {
                    DownloadState.Running(
                        bytesRead = event.bytesRead.coerceAtLeast(0),
                        totalBytes = event.totalBytes?.takeIf { it > 0 }
                    )
                } else {
                    current
                }
            is DownloadEvent.Importing ->
                when (current) {
                    is DownloadState.Running -> current.copy(isImporting = true)
                    DownloadState.Queued -> DownloadState.Running(0, null, isImporting = true)
                    else -> current
                }
            is DownloadEvent.Succeeded ->
                if (current != null && current.isActive) {
                    DownloadState.Completed(event.bookId, event.title, event.alreadyInLibrary)
                } else {
                    current
                }
            is DownloadEvent.Failed ->
                if (current != null && current.isActive) DownloadState.Failed(event.message) else current
            is DownloadEvent.Cleared -> null
        }
        if (next == current) return states
        return if (next == null) states - event.key else states + (event.key to next)
    }
}

/**
 * Rate limits progress reports: one per [minIntervalMillis], or sooner when the
 * percentage changed by at least [minPercentStep]. The first and the final
 * report always pass.
 */
class ProgressThrottle(
    private val minIntervalMillis: Long,
    private val minPercentStep: Int = 100
) {
    private var lastTime = Long.MIN_VALUE
    private var lastPercent = -1

    fun shouldEmit(bytesRead: Long, totalBytes: Long?, nowMillis: Long): Boolean {
        val percent = if (totalBytes != null && totalBytes > 0) {
            ((bytesRead.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
        } else {
            -1
        }
        val first = lastTime == Long.MIN_VALUE
        val finished = totalBytes != null && totalBytes > 0 && bytesRead >= totalBytes
        val timeElapsed = !first && nowMillis - lastTime >= minIntervalMillis
        val bigStep = percent >= 0 && lastPercent >= 0 && percent - lastPercent >= minPercentStep
        val emit = first || timeElapsed || bigStep || (finished && percent != lastPercent)
        if (emit) {
            lastTime = nowMillis
            lastPercent = percent
        }
        return emit
    }
}

/** "850 КБ", "12,4 МБ" — sizes for progress texts. */
fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return formatDecimal(kb, 0) + " КБ"
    val mb = kb / 1024.0
    if (mb < 1024) return formatDecimal(mb, 1, decimalSeparator = ',') + " МБ"
    return formatDecimal(mb / 1024.0, 2, decimalSeparator = ',') + " ГБ"
}

/** "1,2 из 3,4 МБ · 35%" or "1,2 МБ" when the total is unknown. */
fun describeProgress(bytesRead: Long, totalBytes: Long?): String {
    if (totalBytes == null || totalBytes <= 0) return formatByteSize(bytesRead)
    val percent = ((bytesRead.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
    return "${formatByteSize(bytesRead)} из ${formatByteSize(totalBytes)} · $percent%"
}
