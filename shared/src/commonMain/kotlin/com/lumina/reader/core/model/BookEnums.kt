package com.lumina.reader.core.model

// Split from app's Book.kt so that platform-neutral code can use the formats
// and shelves; the package is unchanged, so no import had to move. The Room
// entity Book stays in :app and still stores BookFormat through Converters.

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
