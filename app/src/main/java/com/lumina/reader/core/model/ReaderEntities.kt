package com.lumina.reader.core.model

import androidx.compose.ui.graphics.Color
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A saved place in a book. The position is the first visible character of a
 * page: [paragraphIndex] plus [charOffset] in that paragraph's
 * [ParagraphMarkup.plainText].
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index("bookId")]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val paragraphIndex: Int = 0,
    val chapterTitle: String = "",
    val snippet: String,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val charOffset: Int = 0
)

/**
 * A highlighted passage inside one paragraph. [startOffset] (inclusive) and
 * [endOffset] (exclusive) are offsets in the paragraph's
 * [ParagraphMarkup.plainText]. Rows created before offsets existed have an
 * empty range and are not drawn.
 */
@Entity(
    tableName = "highlights",
    indices = [Index("bookId")]
)
data class ReadingHighlight(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val selectedText: String,
    val note: String? = null,
    val colorHex: String = "#FFEB3B",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val paragraphIndex: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val startOffset: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val endOffset: Int = 0
)

@Entity(
    tableName = "reading_stats",
    indices = [Index("bookId")]
)
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

    /** Light text on a dark page. */
    val isDark: Boolean
        get() = when (this) {
            OLED_BLACK, DARK_SLATE, WARM_AMBER -> true
            SEPIA, CREAM, LIGHT -> false
        }

    /**
     * The theme of the opposite brightness that matches this one best. Used
     * when the reader follows the system light/dark setting.
     */
    val counterpart: ReadingTheme
        get() = when (this) {
            OLED_BLACK -> LIGHT
            DARK_SLATE -> CREAM
            WARM_AMBER -> SEPIA
            SEPIA -> WARM_AMBER
            CREAM -> DARK_SLATE
            LIGHT -> OLED_BLACK
        }
}

/** How a paragraph is aligned horizontally. */
enum class ReaderTextAlign {
    /** Both edges aligned, like a printed book. */
    JUSTIFY,

    /** Ragged right edge. */
    START
}

/** Animation of a page turn in the paged reader. */
enum class PageTurnAnimation {
    SLIDE,
    FLIP,
    CURL
}

/** Where the reading theme comes from. */
enum class ReaderThemeMode {
    /** Always [ReaderSettings.theme]. */
    MANUAL,

    /**
     * [ReaderSettings.theme] while its brightness matches the system dark
     * mode, otherwise its [ReadingTheme.counterpart].
     */
    SYSTEM
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
    val ttsPitch: Float = 1.0f,
    /** Footer shows book-wide page numbers instead of the percentage. */
    val showBookPagesInFooter: Boolean = false,
    val textAlign: ReaderTextAlign = ReaderTextAlign.JUSTIFY,
    /** Automatic hyphenation (Russian patterns for Cyrillic books). */
    val hyphenation: Boolean = true,
    /** First-line indent of ordinary paragraphs, in em. */
    val firstLineIndentEm: Float = 1.5f,
    /** Extra space after every paragraph. */
    val paragraphSpacingDp: Int = 6,
    val pageTurnAnimation: PageTurnAnimation = PageTurnAnimation.SLIDE,
    val themeMode: ReaderThemeMode = ReaderThemeMode.MANUAL,
    /** Footer shows the estimated reading time left in the chapter. */
    val showTimeLeft: Boolean = true,
    /** Left edge turns forward and right edge turns back. */
    val tapZonesInverted: Boolean = false
)

/** The theme the reader actually draws with. */
fun ReaderSettings.effectiveTheme(systemInDarkMode: Boolean): ReadingTheme = when (themeMode) {
    ReaderThemeMode.MANUAL -> theme
    ReaderThemeMode.SYSTEM -> if (theme.isDark == systemInDarkMode) theme else theme.counterpart
}
