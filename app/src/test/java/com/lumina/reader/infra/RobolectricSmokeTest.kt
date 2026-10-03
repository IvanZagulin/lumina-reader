package com.lumina.reader.infra

import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Guards the Robolectric setup that parser and database tests rely on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricSmokeTest {
    @Test
    fun frameworkXmlParserIsAvailable() {
        assertNotNull(Xml.newPullParser())
    }

    @Test
    fun applicationContextIsAvailable() {
        assertNotNull(ApplicationProvider.getApplicationContext<android.content.Context>())
    }
}
