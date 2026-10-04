package com.lumina.reader.ui.shell

import com.lumina.reader.core.preferences.ReminderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ShellServicesTest {

    private class FakeReminders : ReminderControl {
        override val settings: Flow<ReminderSettings> = flowOf(ReminderSettings())
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun setTime(hour: Int, minute: Int) = Unit
    }

    /** Installing must not build anything (it runs in Application.onCreate); first use builds once. */
    @Test
    fun installedRemindersAreBuiltLazilyAndOnce() {
        var built = 0
        val fake = FakeReminders()
        ShellServices.installReminders {
            built++
            fake
        }
        assertEquals(0, built)
        assertSame(fake, ShellServices.reminders)
        assertSame(fake, ShellServices.reminders)
        assertEquals(1, built)
    }
}
