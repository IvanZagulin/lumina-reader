package com.lumina.reader.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun `minor release is newer`() {
        assertTrue(SemanticVersion.isNewer("v1.2.0", "1.1.9"))
    }

    @Test
    fun `major release wins even with smaller minor component`() {
        assertTrue(SemanticVersion.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `missing numeric components are treated as zero`() {
        assertEquals(0, SemanticVersion.compare("v1.2", "1.2.0"))
    }

    @Test
    fun `build metadata does not create an update`() {
        assertFalse(SemanticVersion.isNewer("1.4.0+github.25", "1.4.0+local.3"))
    }

    @Test
    fun `stable release is newer than prerelease`() {
        assertTrue(SemanticVersion.isNewer("1.5.0", "1.5.0-rc.2"))
        assertFalse(SemanticVersion.isNewer("1.5.0-beta.2", "1.5.0"))
    }

    @Test
    fun `semantic prerelease identifiers are ordered correctly`() {
        assertTrue(SemanticVersion.isNewer("1.0.0-rc.10", "1.0.0-rc.2"))
        assertTrue(SemanticVersion.isNewer("1.0.0-beta", "1.0.0-11"))
    }

    @Test
    fun `invalid tag cannot trigger an update`() {
        assertNull(SemanticVersion.compare("latest", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("latest", "1.0.0"))
    }
}
