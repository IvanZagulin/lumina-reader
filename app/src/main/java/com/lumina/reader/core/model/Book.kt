package com.lumina.reader.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class BookFormat {
    EPUB,
    FB2,
    FB2_ZIP,
    PDF,
    TXT;

    companion object {
        fun fromExtension(ext: String): BookFormat {
            return when (ext.lowercase()) {
                "epub" -> EPUB
                "fb2" -> FB2
                "zip" -> FB2_ZIP
                "pdf" -> PDF
                "txt", "text", "md" -> TXT
                else -> TXT
            }
        }

        fun fromFileName(fileName: String): BookFormat {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".epub") -> EPUB
                lower.endsWith(".fb2.zip") -> FB2_ZIP
                lower.endsWith(".fb2") -> FB2
                lower.endsWith(".pdf") -> PDF
                lower.endsWith(".txt") || lower.endsWith(".md") -> TXT
                else -> TXT
            }
        }
    }
}

enum class ReadingStatus(val title: String) {
    UNREAD("Непрочитанные"),
    ALL("Все"),
    READING("Читаю"),
    FAVORITES("Избранное"),
    COMPLETED("Прочитано"),
    COLLECTIONS("По полкам")
}

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String = "Неизвестный автор",
    val filePath: String,
    val coverPath: String? = null,
    val format: BookFormat = BookFormat.EPUB,
    val currentChapterIndex: Int = 0,
    val currentParagraphIndex: Int = 0,
    val currentProgressPercent: Float = 0f,
    val totalChapters: Int = 1,
    val lastReadTimestamp: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val fileSizeBytes: Long = 0L,
    val language: String = "ru",
    val description: String = "",
    val isFavorite: Boolean = false,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val collection: String = "Основная",
    val tags: String = "",
    val seriesName: String = "",
    val seriesOrder: Int = 0
) {
    fun isDone(): Boolean = isCompleted || currentProgressPercent >= 99f
}
