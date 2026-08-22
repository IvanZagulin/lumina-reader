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
)

data class Chapter(
    val index: Int,
    val title: String,
    val content: String,
    val href: String = "",
    val paragraphs: List<String> = emptyList(),
    val pdfPageNumber: Int = 0
)

data class TocItem(
    val id: String,
    val title: String,
    val chapterIndex: Int,
    val level: Int = 0
)

data class ParsedBook(
    val title: String,
    val author: String,
    val description: String = "",
    val seriesName: String = "",
    val seriesOrder: Int = 0,
    val coverBytes: ByteArray? = null,
    val chapters: List<Chapter>,
    val tableOfContents: List<TocItem> = emptyList(),
    val images: Map<String, ByteArray> = emptyMap(),
    val format: BookFormat
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ParsedBook
        return title == other.title && author == other.author && chapters == other.chapters
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + chapters.hashCode()
        return result
    }
}
