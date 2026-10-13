package com.lumina.reader.core.opds

import com.lumina.reader.platform.AppClock
import com.lumina.reader.platform.Ids
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.SYSTEM
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the repository asks, and in which order.
 *
 * That order is the whole behaviour of this class and it was never covered,
 * which is how three bugs lived here at once: searches always started on the
 * domain that is blocked for the user, every built-in catalogue had its search
 * URL guessed before it was asked how it wants to be asked, and a catalogue
 * reached through its mirror still failed because the deadline ran out while
 * the dead host was being waited for. The answers come from a mock engine, so
 * what is asserted is the sequence of URLs, not a network.
 */
class OpdsRepositoryTest {

    // ---- The catalogues under test ------------------------------------------------

    /** Flibusta: two mirrors, and a scoped search of its own. */
    private val mirrored = OpdsCatalogConfig(
        id = "test:mirrored",
        name = "Mirrored",
        url = "https://dead.example/opds",
        builtIn = true,
        mirrorBaseUrls = listOf("https://dead.example", "https://alive.example"),
        supportsScopedSearch = true
    )

    /** A catalogue with one host that declares its own search template. */
    private val declaring = OpdsCatalogConfig(
        id = "test:declaring",
        name = "Declaring",
        url = "https://books.example/opds",
        builtIn = true,
        mirrorBaseUrls = listOf("https://books.example")
    )

    // ---- Feeds ---------------------------------------------------------------------

    private fun feed(title: String, entries: String = "") = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>$title</title>
          $entries
        </feed>
    """.trimIndent()

    private fun book(title: String) = """
        <entry>
          <title>$title</title>
          <link href="/b/1.epub" rel="http://opds-spec.org/acquisition" type="application/epub+zip"/>
        </entry>
    """.trimIndent()

    /** A root feed that advertises where its search lives. */
    private fun rootDeclaring(template: String) =
        feed("Root", """<link href="$template" rel="search" type="application/atom+xml"/>""")

    /** What iknigi answers a guessed URL with: a notice, which carries no link. */
    private val errorNotice = feed("Поиск книг", "<entry><title>Ошибка поиска</title></entry>")

    // ---- Harness ---------------------------------------------------------------------

    private val requests = mutableListOf<String>()
    private val cacheDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("lumina-opds-repo-test-" + Ids.randomUuid())

    @BeforeTest
    fun clear() {
        requests.clear()
        HostHealth.reset()
    }

    @AfterTest
    fun cleanUp() {
        HostHealth.reset()
        FileSystem.SYSTEM.deleteRecursively(cacheDir, mustExist = false)
    }

    /** The answer a host gives for a URL: a body, an HTTP error, or nothing at all. */
    private sealed interface Answer {
        data class Body(val xml: String) : Answer
        data class Status(val code: Int) : Answer
        /** A host that never answers - a blocked domain, not a refused request. */
        data object Silent : Answer
    }

    private fun repositoryOf(cache: OpdsFeedCache = freshCache(), answer: (String) -> Answer): OpdsRepository {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            requests += url
            when (val reply = answer(url)) {
                is Answer.Body -> respond(
                    content = ByteReadChannel(reply.xml.encodeToByteArray()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/atom+xml")
                )
                is Answer.Status -> respond(
                    content = ByteReadChannel(ByteArray(0)),
                    status = HttpStatusCode.fromValue(reply.code)
                )
                Answer.Silent -> throw IOException("no route to host")
            }
        }
        val client = HttpClient(engine)
        return OpdsRepository(clientProvider = { client }, feedCache = cache)
    }

    private fun freshCache(now: () -> Long = AppClock::nowMillis) =
        OpdsFeedCache(FileSystem.SYSTEM, cacheDir, now)

    private fun paths() = requests.map { it.substringAfter("://") }

    // ---- A mirror that does not answer -------------------------------------------------

    @Test
    fun aDeadMirrorIsTriedOnceAndThenGoesLast() = runTest {
        val repository = repositoryOf { url ->
            if ("dead.example" in url) Answer.Silent else Answer.Body(feed("Alive"))
        }

        assertEquals("Alive", repository.fetchFeed("https://dead.example/opds/new", mirrored).title)
        assertEquals(
            listOf("dead.example/opds/new", "alive.example/opds/new"),
            paths(),
            "the catalogue's own host is tried first while nothing is known about it"
        )
        assertTrue(HostHealth.isLikelyDown("https://dead.example/opds"))

        // The next feed goes straight to the host that answered, and because that
        // one works the dead domain is not asked at all.
        requests.clear()
        repository.fetchFeed("https://dead.example/opds/authors", mirrored)
        assertEquals(
            listOf("alive.example/opds/authors"),
            paths(),
            "a host that just failed must not be waited for again before the mirror"
        )
    }

    @Test
    fun aSearchStartsOnTheMirrorThatAnswers() = runTest {
        val repository = repositoryOf { url ->
            when {
                "dead.example" in url -> Answer.Silent
                "searchType=books" in url -> Answer.Body(feed("Found", book("Ведьмак")))
                else -> Answer.Body(feed("Empty"))
            }
        }
        // One feed is enough to learn that the main domain does not answer.
        repository.fetchFeed(mirrored.url, mirrored)
        requests.clear()

        val found = repository.searchCatalog(mirrored, "Ведьмак", OpdsSearchType.BOOKS)

        assertEquals(1, found.publications.size)
        assertTrue(
            paths().first().startsWith("alive.example"),
            "a search must not be built on the domain that was just found dead: ${paths()}"
        )
    }

    @Test
    fun theStoredCopyOfAnyMirrorAnswersForTheOthers() = runTest {
        val cache = freshCache()
        repositoryOf(cache) { Answer.Body(feed("Fresh")) }
            .fetchFeed("https://alive.example/opds/new", mirrored)

        // A new session: nothing remembers which host works, and none of them answers.
        HostHealth.reset()
        requests.clear()
        val offline = repositoryOf(cache) { Answer.Silent }
        assertEquals("Fresh", offline.fetchFeed("https://dead.example/opds/new", mirrored).title)
        assertEquals(emptyList<String>(), paths(), "a stored copy of the same feed must spare the network entirely")
    }

    // ---- Which search URL is asked first -------------------------------------------------

    @Test
    fun aCatalogueIsAskedTheWayItSaysItWantsToBe() = runTest {
        val repository = repositoryOf { url ->
            when {
                url.endsWith("/opds") -> Answer.Body(rootDeclaring("/opds/search?query={searchTerms}"))
                "query=" in url -> Answer.Body(feed("Found", book("Ведьмак")))
                // Every guessed shape answers like iknigi does: a notice, not books.
                else -> Answer.Body(errorNotice)
            }
        }

        val found = repository.searchCatalog(declaring, "Ведьмак", OpdsSearchType.BOOKS)

        assertEquals(1, found.publications.size)
        assertEquals(2, requests.size, "nothing should be guessed before the declared search is tried: ${paths()}")
        assertEquals("books.example/opds", paths()[0], "the root feed is where the search link comes from")
        assertTrue("query=" in paths()[1], "the catalogue's own search must be the first one asked: ${paths()}")
    }

    @Test
    fun aCatalogueWithAScopedSearchOfItsOwnKeepsIt() = runTest {
        val repository = repositoryOf { url ->
            when {
                "alive.example" !in url -> Answer.Silent
                "searchType=books" in url -> Answer.Body(feed("Found", book("Ведьмак")))
                else -> Answer.Body(feed("Empty"))
            }
        }
        repository.fetchFeed(mirrored.url, mirrored)
        requests.clear()

        repository.searchCatalog(mirrored, "Ведьмак", OpdsSearchType.BOOKS)

        assertTrue(
            "searchType=books" in paths().first(),
            "searchType=books is the only way to search books alone, so it goes first: ${paths()}"
        )
    }

    @Test
    fun aNoticeWithoutBooksIsNoResultAndTheNextShapeIsTried() = runTest {
        // Every URL answers with the "Ошибка поиска" notice, whose entry carries no
        // link at all and is therefore no entry: the search must keep looking and
        // end up reporting nothing found, not "here are your results".
        val repository = repositoryOf { url ->
            if (url.endsWith("/opds")) Answer.Body(rootDeclaring("/opds/search?query={searchTerms}"))
            else Answer.Body(errorNotice)
        }

        val found = repository.searchCatalog(declaring, "Ведьмак", OpdsSearchType.BOOKS)

        assertEquals(0, found.entries.size)
        assertTrue(
            paths().count { "search" in it } > 1,
            "a feed with nothing in it must not end the search: ${paths()}"
        )
    }

    @Test
    fun aRefusedLoginIsReportedInsteadOfBeingRetriedOnEveryMirror() = runTest {
        val repository = repositoryOf { Answer.Status(401) }

        assertFailsWith<com.lumina.reader.core.download.HttpStatusException> {
            repository.fetchFeed("https://dead.example/opds/new", mirrored)
        }
        assertEquals(1, requests.size, "wrong credentials are the same on every mirror: ${paths()}")
    }
}
