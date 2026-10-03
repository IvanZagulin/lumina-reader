package com.lumina.reader.core.reminder

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingReminderSchedulerTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val timeZone: TimeZone = TimeZone.getTimeZone(zone)

    private fun millis(day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `reminder later today is scheduled for today`() {
        val next = ReadingReminderScheduler.nextTriggerMillis(millis(3, 10, 0), 20, 0, timeZone)
        assertEquals(millis(3, 20, 0), next)
    }

    @Test
    fun `reminder time already passed is scheduled for tomorrow`() {
        val next = ReadingReminderScheduler.nextTriggerMillis(millis(3, 21, 15), 20, 0, timeZone)
        assertEquals(millis(4, 20, 0), next)
    }

    @Test
    fun `alarm firing exactly on time does not schedule itself again today`() {
        val next = ReadingReminderScheduler.nextTriggerMillis(millis(3, 20, 0), 20, 0, timeZone)
        assertEquals(millis(4, 20, 0), next)
    }

    @Test
    fun `alarm firing a few seconds early does not repeat`() {
        val next = ReadingReminderScheduler.nextTriggerMillis(millis(3, 19, 59, 50), 20, 0, timeZone)
        assertEquals(millis(4, 20, 0), next)
    }

    @Test
    fun `custom time and out of range values are clamped`() {
        assertEquals(
            millis(3, 7, 30),
            ReadingReminderScheduler.nextTriggerMillis(millis(3, 6, 0), 7, 30, timeZone)
        )
        assertEquals(
            millis(3, 23, 59),
            ReadingReminderScheduler.nextTriggerMillis(millis(3, 6, 0), 42, 99, timeZone)
        )
    }
}
