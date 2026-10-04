package com.lumina.reader.core.opds

import com.lumina.reader.platform.AppClock
import com.lumina.reader.platform.Ids
import okio.FileSystem
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpdsFeedCacheTest {

    private val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("lumina-feed-cache-test-" + Ids.randomUuid())

    private fun withCache(now: () -> Long = AppClock::nowMillis, block: (OpdsFeedCache) -> Unit) {
        try {
            block(OpdsFeedCache(FileSystem.SYSTEM, dir, now))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
        }
    }

    @Test
    fun aFeedComesBackWithItsFinalUrlAndBytes() = withCache { cache ->
        val body = "<feed>Привет</feed>".encodeToByteArray()
        cache.write("https://flibusta.is/opds", "https://flibusta.is/opds/", body)
        val hit = assertNotNull(cache.read("https://flibusta.is/opds", OpdsFeedCache.BROWSE_MAX_AGE_MILLIS))
        assertEquals("https://flibusta.is/opds/", hit.finalUrl)
        assertContentEquals(body, hit.body)
    }

    @Test
    fun anUnknownUrlHasNoCopy() = withCache { cache ->
        assertNull(cache.read("https://example.org/never-fetched"))
    }

    @Test
    fun aCopyOlderThanTheLimitIsNotUsedButStillThereAsALastResort() {
        val start = AppClock.nowMillis()
        var time = start
        withCache(now = { time }) { cache ->
            cache.write("https://flibusta.is/opds/new", "https://flibusta.is/opds/new", "x".encodeToByteArray())
            time = start + 2L * 24 * 60 * 60 * 1000
            assertNull(cache.read("https://flibusta.is/opds/new", OpdsFeedCache.BROWSE_MAX_AGE_MILLIS))
            assertNotNull(cache.read("https://flibusta.is/opds/new"))
        }
    }

    @Test
    fun searchesAreKeptForLessTimeThanCatalogues() {
        assertEquals(OpdsFeedCache.SEARCH_MAX_AGE_MILLIS, OpdsFeedCache.maxAgeFor("https://flibusta.is/opds/search?searchTerm=x"))
        assertEquals(OpdsFeedCache.BROWSE_MAX_AGE_MILLIS, OpdsFeedCache.maxAgeFor("https://flibusta.is/opds/new"))
    }

    @Test
    fun theFileNameOfAUrlIsStable() {
        assertEquals(OpdsFeedCache.fnv1a("https://flibusta.is/opds"), OpdsFeedCache.fnv1a("https://flibusta.is/opds"))
        assertTrue(OpdsFeedCache.fnv1a("a") != OpdsFeedCache.fnv1a("b"))
        assertEquals(16, OpdsFeedCache.fnv1a("anything").length)
    }
}
