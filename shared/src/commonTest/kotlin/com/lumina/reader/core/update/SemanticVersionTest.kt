package com.lumina.reader.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticVersionTest {

    @Test
    fun minorReleaseIsNewer() {
        assertTrue(SemanticVersion.isNewer("v1.2.0", "1.1.9"))
    }

    @Test
    fun majorReleaseWinsEvenWithSmallerMinorComponent() {
        assertTrue(SemanticVersion.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun missingNumericComponentsAreTreatedAsZero() {
        assertEquals(0, SemanticVersion.compare("v1.2", "1.2.0"))
    }

    @Test
    fun buildMetadataDoesNotCreateAnUpdate() {
        assertFalse(SemanticVersion.isNewer("1.4.0+github.25", "1.4.0+local.3"))
    }

    @Test
    fun stableReleaseIsNewerThanPrerelease() {
        assertTrue(SemanticVersion.isNewer("1.5.0", "1.5.0-rc.2"))
        assertFalse(SemanticVersion.isNewer("1.5.0-beta.2", "1.5.0"))
    }

    @Test
    fun semanticPrereleaseIdentifiersAreOrderedCorrectly() {
        assertTrue(SemanticVersion.isNewer("1.0.0-rc.10", "1.0.0-rc.2"))
        assertTrue(SemanticVersion.isNewer("1.0.0-beta", "1.0.0-11"))
    }

    @Test
    fun invalidTagCannotTriggerAnUpdate() {
        assertNull(SemanticVersion.compare("latest", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("latest", "1.0.0"))
    }
}
