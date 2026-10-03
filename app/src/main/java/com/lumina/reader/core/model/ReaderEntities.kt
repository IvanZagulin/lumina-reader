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

/**
 * A reading theme. Constants are persisted by name, so they must never be
 * renamed. [accentColor] tints the reader chrome (bookmark ribbon, scrubber,
 * selected controls); [secondaryTextColor] and [accentColor] reach at least
 * 4.5:1 on [backgroundColor].
 */
enum class ReadingTheme(
    val title: String,
    val backgroundColor: Long,
    val textColor: Long,
    val surfaceColor: Long,
    val secondaryTextColor: Long,
    val accentColor: Long,
    /** Light text on a dark page. */
    val isDark: Boolean
) {
    LIGHT(
        title = "Светлая",
        backgroundColor = 0xFFFFFFFF,
        textColor = 0xFF191C1E,
        surfaceColor = 0xFFF1F3F5,
        secondaryTextColor = 0xFF6B6E76,
        accentColor = 0xFFA64A23,
        isDark = false
    ),
    CREAM(
        title = "Кремовая",
        backgroundColor = 0xFFFAF4E8,
        textColor = 0xFF2D241E,
        surfaceColor = 0xFFEDE2CE,
        secondaryTextColor = 0xFF6D5F54,
        accentColor = 0xFF9A4A22,
        isDark = false
    ),
    SEPIA(
        title = "Сепия",
        backgroundColor = 0xFFF4ECD8,
        textColor = 0xFF433422,
        surfaceColor = 0xFFE9DFC6,
        secondaryTextColor = 0xFF6F5E4B,
        accentColor = 0xFF8E4A1C,
        isDark = false
    ),
    DARK_SLATE(
        title = "Сумерки",
        backgroundColor = 0xFF13171F,
        textColor = 0xFFE2E8F0,
        surfaceColor = 0xFF1E2430,
        secondaryTextColor = 0xFF94A3B8,
        accentColor = 0xFFF0A06B,
        isDark = true
    ),
    WARM_AMBER(
        title = "Янтарь",
        backgroundColor = 0xFF1C1713,
        textColor = 0xFFFFD7A8,
        surfaceColor = 0xFF2A231C,
        secondaryTextColor = 0xFFA89482,
        accentColor = 0xFFFFB45E,
        isDark = true
    ),
    OLED_BLACK(
        title = "Чёрная",
        backgroundColor = 0xFF000000,
        textColor = 0xFFE0E0E0,
        surfaceColor = 0xFF121212,
        secondaryTextColor = 0xFF888888,
        accentColor = 0xFFF0A06B,
        isDark = true
    );

    val bgComposeColor: Color get() = Color(backgroundColor)
    val textComposeColor: Color get() = Color(textColor)
    val surfaceComposeColor: Color get() = Color(surfaceColor)
    val secondaryTextComposeColor: Color get() = Color(secondaryTextColor)
    val accentComposeColor: Color get() = Color(accentColor)

    /** Content on an [accentComposeColor] fill: white on light themes, the page colour on dark ones. */
    val onAccentComposeColor: Color get() = if (isDark) Color(backgroundColor) else Color.White

    /**
     * The theme of the opposite brightness that matches this one best. Used
     * to keep the day/night pair of readers who chose «Как в системе» before
     * day and night themes could be picked separately.
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

/**
 * Animation of a page turn in the paged reader. Persisted by name; unknown
 * names read back as [SLIDE].
 */
enum class PageTurnAnimation {
    /** «Сдвиг»: the page slides away over a slightly moving page (default). */
    SLIDE,

    /** «3D-переворот»: the page swings around the spine. */
    FLIP,

    /** «Загиб» (beta): the corner curls over. */
    CURL,

    /** «Без анимации»: pages change instantly (e-ink, reduced motion). */
    NONE
}

/** Where the reading theme comes from. */
enum class ReaderThemeMode {
    /** Always [ReaderSettings.theme]. */
    MANUAL,

    /**
     * «Авто»: [ReaderSettings.dayTheme] while the system is light,
     * [ReaderSettings.nightTheme] while it is dark.
     */
    SYSTEM
}

/** Which part of the page turns pages and which toggles the menu. */
enum class ReaderTapZones {
    /** Left 30 % back, right 30 % forward, centre opens the menu. */
    CLASSIC,

    /** Only the left 20 % goes back; a centre box opens the menu; everything else goes forward. */
    ONE_HAND
}

data class ReaderSettings(
    val fontSizeSp: Int = 18,
    val lineSpacingMultiplier: Float = 1.45f,
    val horizontalPaddingDp: Int = 20,
    /** Id from [ReaderFontIds]; older names are migrated when read. */
    val fontFamily: String = ReaderFontIds.DEFAULT,
    /** The theme being drawn; with «Авто» it follows the day/night choice. */
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
    /** Extra space after every paragraph, in em of the reading font size. */
    val paragraphSpacingEm: Float = DEFAULT_PARAGRAPH_SPACING_EM,
    val pageTurnAnimation: PageTurnAnimation = PageTurnAnimation.SLIDE,
    val themeMode: ReaderThemeMode = ReaderThemeMode.MANUAL,
    /** «Авто»: theme while the system is light. */
    val dayTheme: ReadingTheme = ReadingTheme.CREAM,
    /** «Авто»: theme while the system is dark. */
    val nightTheme: ReadingTheme = ReadingTheme.OLED_BLACK,
    /** Footer shows the estimated reading time left in the chapter. */
    val showTimeLeft: Boolean = true,
    /** A thin book-progress line at the very bottom of the page. */
    val showProgressLine: Boolean = false,
    /** Left edge turns forward and right edge turns back. */
    val tapZonesInverted: Boolean = false,
    val tapZones: ReaderTapZones = ReaderTapZones.CLASSIC
)

/** The theme the reader actually draws with. */
fun ReaderSettings.effectiveTheme(systemInDarkMode: Boolean): ReadingTheme = when (themeMode) {
    ReaderThemeMode.MANUAL -> theme
    ReaderThemeMode.SYSTEM -> if (systemInDarkMode) nightTheme else dayTheme
}

/**
 * Picks a theme in the settings sheet: null is «Авто». A manual choice also
 * becomes the day or night half of the pair, so «Тема» in the bottom bar
 * toggles between the reader's own favourites.
 */
fun ReaderSettings.withThemeChoice(choice: ReadingTheme?): ReaderSettings =
    if (choice == null) {
        copy(themeMode = ReaderThemeMode.SYSTEM)
    } else {
        copy(
            themeMode = ReaderThemeMode.MANUAL,
            theme = choice,
            dayTheme = if (choice.isDark) dayTheme else choice,
            nightTheme = if (choice.isDark) choice else nightTheme
        )
    }

/** «Тема» in the bottom bar: switches to the other half of the day/night pair. */
fun ReaderSettings.toggledDayNight(systemInDarkMode: Boolean): ReaderSettings {
    val current = effectiveTheme(systemInDarkMode)
    return copy(themeMode = ReaderThemeMode.MANUAL, theme = if (current.isDark) dayTheme else nightTheme)
}

/** «Сбросить настройки»: typography and behaviour back to defaults; themes and voice stay. */
fun ReaderSettings.resetToDefaults(): ReaderSettings = ReaderSettings().copy(
    theme = theme,
    themeMode = themeMode,
    dayTheme = dayTheme,
    nightTheme = nightTheme,
    ttsSpeed = ttsSpeed,
    ttsPitch = ttsPitch
)

/**
 * Day and night themes for a reader who chose «Как в системе» before the
 * pair could be picked: the stored theme for its own brightness and its
 * [ReadingTheme.counterpart] for the other one, exactly as it used to work.
 */
fun legacyAutoThemes(theme: ReadingTheme): Pair<ReadingTheme, ReadingTheme> =
    if (theme.isDark) theme.counterpart to theme else theme to theme.counterpart

/** Reading font ids stored in [ReaderSettings.fontFamily]. */
object ReaderFontIds {
    const val LITERATA = "literata"
    const val PT_SERIF = "ptserif"
    const val GOLOS = "golos"
    const val SYSTEM_SERIF = "system_serif"
    const val SYSTEM_SANS = "system_sans"
    const val MONO = "mono"
    const val DEFAULT = LITERATA

    val all: List<String> = listOf(LITERATA, PT_SERIF, GOLOS, SYSTEM_SERIF, SYSTEM_SANS, MONO)

    /**
     * The id for a stored value: the names used before the font catalogue
     * ("Serif", "SansSerif", "Monospace", the retired "Cursive") and unknown
     * values are mapped onto the catalogue.
     */
    fun migrate(stored: String?): String {
        val value = stored ?: return LITERATA
        return when (value) {
            "SansSerif" -> GOLOS
            "Monospace" -> MONO
            in all -> value
            else -> LITERATA // "Serif" (the old default), "Cursive", blank and unknown names
        }
    }
}

/** «Нет · Малый · Обычный · Большой». */
val PARAGRAPH_SPACING_OPTIONS_EM: List<Float> = listOf(0f, 0.35f, 0.7f, 1f)
const val DEFAULT_PARAGRAPH_SPACING_EM = 0.35f

/** Line spacing choices of the settings sheet. */
val LINE_SPACING_OPTIONS: List<Float> = listOf(1.2f, 1.45f, 1.7f, 2.0f)

/** Page margin choices of the settings sheet, in dp. */
val MARGIN_OPTIONS_DP: List<Int> = listOf(12, 20, 32, 44)

/** First-line indent choices, in em. */
val FIRST_LINE_INDENT_OPTIONS_EM: List<Float> = listOf(0f, 1f, 1.5f, 2f)

/** The option closest to [value]; the first one wins a tie. */
fun nearestOption(value: Float, options: List<Float>): Float =
    options.minByOrNull { kotlin.math.abs(it - value) } ?: value

/** The option closest to [value]; the first one wins a tie. */
fun nearestOption(value: Int, options: List<Int>): Int =
    options.minByOrNull { kotlin.math.abs(it - value) } ?: value

/**
 * Paragraph spacing in em for a value stored in dp by older versions
 * (6 dp at 18 sp becomes «Малый», 12 dp becomes «Обычный»).
 */
fun legacyParagraphSpacingEm(spacingDp: Int, fontSizeSp: Int): Float =
    if (spacingDp <= 0) 0f
    else nearestOption(spacingDp.toFloat() / fontSizeSp.coerceAtLeast(1), PARAGRAPH_SPACING_OPTIONS_EM)
