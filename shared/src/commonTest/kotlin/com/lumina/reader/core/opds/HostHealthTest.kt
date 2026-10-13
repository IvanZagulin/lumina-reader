package com.lumina.reader.core.opds

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The memory of which mirror of a catalogue still answers. What it is for: the
 * searches of a catalogue are built on its own base URL, so before this they
 * waited out the same blocked domain on every single search and ran out of time
 * on a catalogue that browsing reached through its mirror perfectly well.
 */
class HostHealthTest {

    private val flibusta = BuiltInCatalogs.find(BuiltInCatalogs.FLIBUSTA_ID)!!

    @BeforeTest
    fun clear() = HostHealth.reset()

    @AfterTest
    fun cleanUp() = HostHealth.reset()

    @Test
    fun keepsTheGivenOrderWhileEveryHostAnswers() {
        val urls = listOf("https://flibusta.is/opds/search?q=x", "https://flibusta.site/opds/search?q=x")
        assertEquals(urls, HostHealth.order(urls))
    }

    @Test
    fun movesAHostThatDidNotAnswerBehindTheOthersWithoutDroppingIt() {
        HostHealth.markUnreachable("https://flibusta.is/opds")
        assertTrue(HostHealth.isLikelyDown("https://flibusta.is/opds/search?q=x"))

        val ordered = HostHealth.order(
            listOf("https://flibusta.is/opds/search?q=x", "https://flibusta.site/opds/search?q=x")
        )
        assertEquals(
            listOf("https://flibusta.site/opds/search?q=x", "https://flibusta.is/opds/search?q=x"),
            ordered
        )
    }

    @Test
    fun remembersPerHostAndNotPerUrl() {
        HostHealth.markUnreachable("https://flibusta.is/opds")
        // Any other page of the same host is just as unreachable.
        assertTrue(HostHealth.isLikelyDown("https://FLIBUSTA.is/b/1/fb2"))
        assertFalse(HostHealth.isLikelyDown("https://flibusta.site/opds"))
    }

    @Test
    fun anAnswerOfAnyKindBringsAHostBack() {
        HostHealth.markUnreachable("https://flibusta.is/opds")
        HostHealth.markReachable("https://flibusta.is/opds/search?q=x")
        assertFalse(HostHealth.isLikelyDown("https://flibusta.is/opds"))
    }

    @Test
    fun ignoresUrlsWithoutAHost() {
        HostHealth.markUnreachable("not a url")
        HostHealth.markReachable("not a url")
        assertFalse(HostHealth.isLikelyDown("not a url"))
        assertEquals(listOf("not a url"), HostHealth.order(listOf("not a url")))
    }

    @Test
    fun aSearchStartsOnTheMirrorThatStillAnswers() {
        // The order a catalogue prefers, as long as nothing has failed.
        assertEquals("https://flibusta.is", HostHealth.order(flibusta.searchBaseUrls).first())

        HostHealth.markUnreachable(flibusta.url)
        assertEquals("https://flibusta.site", HostHealth.order(flibusta.searchBaseUrls).first())
    }
}
