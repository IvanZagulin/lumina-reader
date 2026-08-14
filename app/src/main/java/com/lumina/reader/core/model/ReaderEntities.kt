package com.lumina.reader.core.model

import androidx.compose.ui.graphics.Color
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val paragraphIndex: Int = 0,
    val chapterTitle: String = "",
    val snippet: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "highlights")
data class ReadingHighlight(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val selectedText: String,
    val note: String? = null,
    val colorHex: String = "#FFEB3B",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reading_stats")
data class ReadingStats(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val sessionDurationSeconds: Long,
    val wordsReadCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ReadingTheme(
    val title: String,
    val backgroundColor: Long,
    val textColor: Long,
    val surfaceColor: Long,
    val secondaryTextColor: Long
) {
    OLED_BLACK(
        title = "OLED Black",
        backgroundColor = 0xFF000000,
        textColor = 0xFFE0E0E0,
        surfaceColor = 0xFF121212,
        secondaryTextColor = 0xFF888888
    ),
    DARK_SLATE(
        title = "Slate Dark",
        backgroundColor = 0xFF13171F,
        textColor = 0xFFE2E8F0,
        surfaceColor = 0xFF1E2430,
        secondaryTextColor = 0xFF94A3B8
    ),
    SEPIA(
        title = "Сепия",
        backgroundColor = 0xFFF4ECD8,
        textColor = 0xFF433422,
        surfaceColor = 0xFFE9DFC6,
        secondaryTextColor = 0xFF7D6B57
    ),
    CREAM(
        title = "Кремовая бумага",
        backgroundColor = 0xFFFAF4E8,
        textColor = 0xFF2D241E,
        surfaceColor = 0xFFEDE2CE,
        secondaryTextColor = 0xFF6D5F54
    ),
    WARM_AMBER(
        title = "Тёплый янтарь",
        backgroundColor = 0xFF1C1713,
        textColor = 0xFFFFD7A8,
        surfaceColor = 0xFF2A231C,
        secondaryTextColor = 0xFFA89482
    ),
    LIGHT(
        title = "Светлая",
        backgroundColor = 0xFFFFFFFF,
        textColor = 0xFF191C1E,
        surfaceColor = 0xFFF1F3F5,
        secondaryTextColor = 0xFF74777F
    );

    val bgComposeColor: Color get() = Color(backgroundColor)
    val textComposeColor: Color get() = Color(textColor)
    val surfaceComposeColor: Color get() = Color(surfaceColor)
    val secondaryTextComposeColor: Color get() = Color(secondaryTextColor)
}

data class ReaderSettings(
    val fontSizeSp: Int = 18,
    val lineSpacingMultiplier: Float = 1.45f,
    val horizontalPaddingDp: Int = 20,
    val fontFamily: String = "Serif",
    val theme: ReadingTheme = ReadingTheme.OLED_BLACK,
    val isBionicReadingEnabled: Boolean = false,
    val isContinuousScroll: Boolean = false,
    val keepScreenOn: Boolean = true,
    val volumeKeyNavigation: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f
)
