package com.lumina.reader.core.download

import com.lumina.reader.core.library.PortableStreams
import com.lumina.reader.core.opds.HostHealth
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
 * A download that stopped part-way is picked up where it stopped.
 *
 * This is the part that must not be approximately right: appending to a file
 * that is not the same file any more, or to one the server started over, gives
 * a book of the right size and the wrong contents. So what is checked here is
 * not only that resuming happens, but that it is given up on - and the part
 * thrown away - whenever the server will not promise the rest of exactly that
 * file.
 *
 * A break is served as a body that ends before its Content-Length. That is the
 * shape no engine warns about (a connection the other side closes cleanly, a
 * proxy that gives up), and so the one the downloader has to notice itself.
 */
class BookDownloaderTest {

    private val body = ByteArray(4_000) { (it % 251).toByte() }
    private val prefixLength = 1_500

    private val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("lumina-download-test-" + Ids.randomUuid())
    private val target = dir / "book.fb2"
    private val requests = mutableListOf<Pair<String, String?>>()

    @BeforeTest
    fun setUp() {
        requests.clear()
        HostHealth.reset()
        FileSystem.SYSTEM.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        HostHealth.reset()
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    /** What the server does with one request; [requests] records the Range asked for. */
    private sealed interface Reply {
        /** Promises the rest of the file but stops after [sends] bytes. */
        data class Broken(val sends: Int) : Reply

        /** Honours the Range it was given: the rest of the file, whole. */
        data object Rest : Reply

        /** Ignores Range and sends the whole file as 200. */
        data object Whole : Reply

        data class Status(val code: Int) : Reply

        data object Silent : Reply
    }

    private fun downloaderOf(reply: (url: String, range: String?) -> Reply): BookDownloader {
        val engine = MockEngine { request ->
            val range = request.headers[HttpHeaders.Range]
            val url = request.url.toString()
            requests += url to range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
            val partial = if (from > 0) HttpStatusCode.PartialContent else HttpStatusCode.OK
            val rest = headersOf(HttpHeaders.ContentLength, (body.size - from).toString())
            when (val answer = reply(url, range)) {
                // The length header is the promise the body then fails to keep.
                is Reply.Broken -> respond(
                    content = ByteReadChannel(body.copyOfRange(from, minOf(from + answer.sends, body.size))),
                    status = partial,
                    headers = rest
                )
                Reply.Rest -> respond(
                    content = ByteReadChannel(body.copyOfRange(from, body.size)),
                    status = partial,
                    headers = rest
                )
                Reply.Whole -> respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, body.size.toString())
                )
                is Reply.Status -> respond(
                    content = ByteReadChannel(ByteArray(0)),
                    status = HttpStatusCode.fromValue(answer.code)
                )
                Reply.Silent -> throw IOException("no route to host")
            }
        }
        val client = HttpClient(engine)
        return BookDownloader(clientProvider = { client })
    }

    private fun requestFor(url: String, mirrors: List<String> = emptyList()) =
        DownloadRequest(url = url, title = "Книга", mirrorBaseUrls = mirrors)

    private fun fileBytes(): ByteArray = FileSystem.SYSTEM.read(target) { readByteArray() }

    private fun assertIsTheWholeBook(actual: ByteArray) {
        assertEquals(body.size, actual.size, "the file is ${actual.size} bytes, expected ${body.size}")
        assertTrue(body.contentEquals(actual), "the file's contents are not what the server sent")
    }

    // ---- Resuming ---------------------------------------------------------------------

    @Test
    fun aBrokenTransferIsPickedUpWhereItStopped() = runTest {
        var served = 0
        val downloader = downloaderOf { _, _ ->
            if (served++ == 0) Reply.Broken(prefixLength) else Reply.Rest
        }

        val file = downloader.download(requestFor("https://books.example/b/1.fb2"), target) { _, _ -> }

        assertEquals(body.size.toLong(), file.bytes)
        assertIsTheWholeBook(fileBytes())
        assertEquals(PortableStreams.sha256(target), file.sha256)
        assertEquals(
            listOf(null, "bytes=$prefixLength-"),
            requests.map { it.second },
            "the second request must ask only for the rest of the file"
        )
    }

    @Test
    fun aServerThatIgnoresRangeStartsTheFileAgainInsteadOfDoublingIt() = runTest {
        var served = 0
        // The second answer is a plain 200 with the whole file: appending that to
        // what is already on disk would give a file of the right name and 5500
        // bytes of nonsense.
        val downloader = downloaderOf { _, _ ->
            if (served++ == 0) Reply.Broken(prefixLength) else Reply.Whole
        }

        val file = downloader.download(requestFor("https://books.example/b/1.fb2"), target) { _, _ -> }

        assertEquals(body.size.toLong(), file.bytes)
        assertIsTheWholeBook(fileBytes())
    }

    @Test
    fun aRangeTheServerWillNotServeThrowsThePartAway() = runTest {
        var served = 0
        val downloader = downloaderOf { _, range ->
            when {
                served++ == 0 -> Reply.Broken(prefixLength)
                range != null -> Reply.Status(416)
                else -> Reply.Rest
            }
        }

        val file = downloader.download(requestFor("https://books.example/b/1.fb2"), target) { _, _ -> }

        assertEquals(body.size.toLong(), file.bytes)
        assertIsTheWholeBook(fileBytes())
        assertEquals(listOf(null, "bytes=$prefixLength-", null), requests.map { it.second })
    }

    @Test
    fun aBookThatNeverFinishesFailsInsteadOfBeingImportedShort() = runTest {
        // After the first piece the server keeps promising the rest and sending
        // nothing. Most of a book must not pass for the whole of it.
        var served = 0
        val downloader = downloaderOf { _, _ ->
            if (served++ == 0) Reply.Broken(prefixLength) else Reply.Broken(0)
        }

        assertFailsWith<Exception> {
            downloader.download(requestFor("https://books.example/b/1.fb2"), target) { _, _ -> }
        }
    }

    // ---- Mirrors -----------------------------------------------------------------------

    @Test
    fun anotherMirrorStartsFromNothing() = runTest {
        // What one host sent is no promise about the file on another, so the part
        // already on disk is dropped rather than continued there.
        var servedByFirst = 0
        val downloader = downloaderOf { url, _ ->
            when {
                "first.example" !in url -> Reply.Rest
                servedByFirst++ == 0 -> Reply.Broken(prefixLength)
                else -> Reply.Broken(0)
            }
        }

        val file = downloader.download(
            requestFor("https://first.example/b/1.fb2", listOf("https://first.example", "https://second.example")),
            target
        ) { _, _ -> }

        assertEquals(body.size.toLong(), file.bytes)
        assertIsTheWholeBook(fileBytes())
        val onSecond = requests.filter { "second.example" in it.first }
        assertTrue(onSecond.isNotEmpty(), "the mirror was never tried: $requests")
        assertTrue(
            onSecond.all { it.second == null },
            "the mirror was asked to continue another host's file: $onSecond"
        )
    }

    @Test
    fun aHostThatNeverAnswersIsNotWaitedForOverAndOver() = runTest {
        // Nothing arrived, so there is nothing to resume: retrying here would spend
        // three connect timeouts before the mirror is even tried.
        val downloader = downloaderOf { url, _ ->
            if ("dead.example" in url) Reply.Silent else Reply.Rest
        }

        downloader.download(
            requestFor("https://dead.example/b/1.fb2", listOf("https://dead.example", "https://alive.example")),
            target
        ) { _, _ -> }

        assertEquals(1, requests.count { "dead.example" in it.first }, "the dead host was asked more than once")
        assertTrue(HostHealth.isLikelyDown("https://dead.example/b/1.fb2"))
    }
}
