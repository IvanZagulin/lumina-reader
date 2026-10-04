package com.lumina.reader

import com.lumina.reader.core.bionic.BionicReadingHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The parser tests that shared this file (ParserTest) moved to :shared's commonTest in stage 6.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BionicReadingHelperTest {

    @Test
    fun testBionicReadingHelper() {
        val input = "Lumina Reader флагманская читалка"
        val annotated = BionicReadingHelper.transform(input)
        assertNotNull(annotated)
        assertEquals(input, annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }
}
