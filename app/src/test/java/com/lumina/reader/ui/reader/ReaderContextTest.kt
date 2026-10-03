package com.lumina.reader.ui.reader

import android.app.Activity
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import com.lumina.reader.ui.reader.settings.findActivity
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderContextTest {

    @Test
    fun findsTheActivityBehindSheetContexts() {
        val activity = Activity()
        assertSame(activity, activity.findActivity())
        // A bottom sheet's dialog hands its content a themed wrapper of the activity.
        val sheetContext = ContextThemeWrapper(ContextWrapper(activity), 0)
        assertSame(activity, sheetContext.findActivity())
    }

    @Test
    fun applicationContextHasNoActivity() {
        assertNull(RuntimeEnvironment.getApplication().findActivity())
        assertNull(ContextWrapper(RuntimeEnvironment.getApplication()).findActivity())
    }
}
