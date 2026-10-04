package com.lumina.reader.ui.stats

import com.lumina.reader.platform.AppClock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/**
 * Russian date texts of the statistics, exactly as Android printed them with
 * `DateTimeFormatter.ofPattern(..., Locale("ru", "RU"))` (CLDR data). The
 * formatter and its locale data are JVM-only and kotlinx-datetime ships only
 * English month names, so the four CLDR month tables are written out here.
 * StatsDatesTest pins every month of every form against the JVM formatter.
 */
object RussianDates {
    /** CLDR format-wide (genitive) months: "d MMMM". */
    private val genitive = arrayOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря"
    )

    /** CLDR format-abbreviated months: "d MMM". */
    private val genitiveShort = arrayOf(
        "янв.", "февр.", "мар.", "апр.", "мая", "июн.",
        "июл.", "авг.", "сент.", "окт.", "нояб.", "дек."
    )

    /** CLDR stand-alone abbreviated months: "LLL". */
    private val nominativeShort = arrayOf(
        "янв.", "февр.", "март", "апр.", "май", "июнь",
        "июль", "авг.", "сент.", "окт.", "нояб.", "дек."
    )

    /** CLDR stand-alone wide months: "LLLL". */
    private val nominative = arrayOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"
    )

    /** Was "d MMMM yyyy": «4 октября 2026». */
    fun dayMonthYear(date: LocalDate): String =
        "${date.day} ${genitive[date.month.ordinal]} ${fourDigitYear(date.year)}"

    /** Was "d MMM": «4 окт.», «12 мая». */
    fun dayMonthShort(date: LocalDate): String = "${date.day} ${genitiveShort[date.month.ordinal]}"

    /** Was "LLL" without the dot, capitalised: «Окт», «Май», «Сент». */
    fun monthShortCapitalized(month: YearMonth): String =
        nominativeShort[month.month.ordinal].replace(".", "").replaceFirstChar { it.uppercase() }

    /** Was "LLL yy" without the dot: «окт 26». */
    fun monthShortYear(month: YearMonth): String {
        val yy = (month.year % 100).toString().padStart(2, '0')
        return "${nominativeShort[month.month.ordinal]} $yy".replace(".", "")
    }

    /** Was "LLLL yyyy", capitalised: «Октябрь 2026». */
    fun monthLongYear(month: YearMonth): String =
        "${nominative[month.month.ordinal]} ${fourDigitYear(month.year)}".replaceFirstChar { it.uppercase() }

    /** "yyyy" pads years below 1000 to four digits. */
    private fun fourDigitYear(year: Int): String = year.toString().padStart(4, '0')
}

/**
 * Calendar fields of session timestamps in one time zone, each computed once
 * per calculation. The calculators look at every session's date dozens of
 * times (periods, months, hours, weekdays); a zone conversion is cheap with
 * java.time but a transition-table search in Kotlin/Native's kotlinx-datetime,
 * and the statistics recalculate every minute.
 */
class LocalTimes(private val timeZone: TimeZone) {
    private val cache = HashMap<Long, LocalDateTime>()

    @OptIn(ExperimentalTime::class)
    fun of(epochMillis: Long): LocalDateTime = cache.getOrPut(epochMillis) {
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    }

    fun date(epochMillis: Long): LocalDate = of(epochMillis).date
}

/** java.time's `LocalDate.lengthOfYear()`: 365, or 366 in a leap year. */
fun LocalDate.lengthOfYear(): Int = LocalDate(year, 1, 1).daysUntil(LocalDate(year + 1, 1, 1))

/** java.time's `LocalDate.now()`: today in the device's time zone. */
@OptIn(ExperimentalTime::class)
fun statsToday(): LocalDate =
    Instant.fromEpochMilliseconds(AppClock.nowMillis()).toLocalDateTime(TimeZone.currentSystemDefault()).date
