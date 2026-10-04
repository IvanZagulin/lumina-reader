package com.lumina.reader.ui.stats

import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The statistics' Russian date texts used to come from java.time's CLDR data
 * (`DateTimeFormatter.ofPattern(..., Locale("ru", "RU"))`), which common code
 * cannot use. The hand-written tables must print exactly the same, so every
 * month of every pattern is checked against the JVM formatter here, plus a few
 * literal texts so a change in the JVM's own data would show up too.
 */
class StatsDatesTest {

    private val russian = Locale("ru", "RU")

    private fun javaDate(date: LocalDate): java.time.LocalDate =
        java.time.LocalDate.of(date.year, date.month.ordinal + 1, date.day)

    private fun javaMonth(month: YearMonth): java.time.LocalDate =
        java.time.LocalDate.of(month.year, month.month.ordinal + 1, 1)

    private fun format(date: java.time.LocalDate, pattern: String): String =
        date.format(DateTimeFormatter.ofPattern(pattern, russian))

    @Test
    fun everyMonthMatchesTheFormatterAndroidUsed() {
        for (monthNumber in 1..12) {
            // Day 4 for the day-month texts; 2026 and 2005 for the year padding of "yy".
            listOf(2026, 2005).forEach { year ->
                val date = LocalDate(year, monthNumber, 4)
                val month = YearMonth(year, monthNumber)

                assertEquals(format(javaDate(date), "d MMMM yyyy"), RussianDates.dayMonthYear(date))
                assertEquals(format(javaDate(date), "d MMM"), RussianDates.dayMonthShort(date))
                assertEquals(
                    format(javaMonth(month), "LLL").replace(".", "").replaceFirstChar { it.uppercase() },
                    RussianDates.monthShortCapitalized(month)
                )
                assertEquals(
                    format(javaMonth(month), "LLL yy").replace(".", ""),
                    RussianDates.monthShortYear(month)
                )
                assertEquals(
                    format(javaMonth(month), "LLLL yyyy").replaceFirstChar { it.uppercase() },
                    RussianDates.monthLongYear(month)
                )
            }
        }
    }

    @Test
    fun knownTexts() {
        assertEquals("4 октября 2026", RussianDates.dayMonthYear(LocalDate(2026, 10, 4)))
        assertEquals("12 мая", RussianDates.dayMonthShort(LocalDate(2026, 5, 12)))
        assertEquals("12 сент.", RussianDates.dayMonthShort(LocalDate(2026, 9, 12)))
        assertEquals("Сент", RussianDates.monthShortCapitalized(YearMonth(2026, 9)))
        assertEquals("Май", RussianDates.monthShortCapitalized(YearMonth(2026, 5)))
        assertEquals("февр 26", RussianDates.monthShortYear(YearMonth(2026, 2)))
        assertEquals("Март 2026", RussianDates.monthLongYear(YearMonth(2026, 3)))
    }

    @Test
    fun lengthOfYearMatchesJavaTime() {
        for (year in listOf(1900, 2000, 2023, 2024, 2025, 2026, 2100)) {
            assertEquals(
                java.time.LocalDate.of(year, 6, 1).lengthOfYear(),
                LocalDate(year, 6, 1).lengthOfYear()
            )
        }
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun localTimesMatchJavaTimeAcrossZones() {
        val moments = listOf(
            "2026-08-13T23:30:00Z",
            "2026-03-29T00:59:59Z",
            "2026-10-25T01:00:00Z",
            "2000-01-01T00:00:00Z"
        ).map { Instant.parse(it).toEpochMilliseconds() }
        for (zone in listOf("Europe/Moscow", "Europe/Berlin", "America/New_York", "UTC")) {
            val times = LocalTimes(TimeZone.of(zone))
            moments.forEach { millis ->
                val expected = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.of(zone))
                val actual = times.of(millis)
                assertEquals(expected.toLocalDate(), javaDate(actual.date))
                assertEquals(expected.hour, actual.hour)
            }
        }
    }
}
