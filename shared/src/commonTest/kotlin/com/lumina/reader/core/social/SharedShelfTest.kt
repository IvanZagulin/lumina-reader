package com.lumina.reader.core.social

import com.lumina.reader.core.model.Book
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What leaves the device for the circle.
 *
 * This is the privacy boundary of the feature, so the tests are mostly about
 * what is *not* sent: nothing at all while sharing is off, and never a book the
 * reader hid.
 */
class SharedShelfTest {

    private fun book(
        id: Long,
        title: String,
        author: String = "Автор",
        progress: Float = 0f,
        done: Boolean = false,
        startedAt: Long? = null,
        lastRead: Long = 1_000,
        rating: Int = 0,
    ) = Book(
        id = id,
        title = title,
        author = author,
        filePath = "/books/$id",
        currentProgressPercent = progress,
        isCompleted = done,
        startedAt = startedAt,
        lastReadTimestamp = lastRead,
        rating = rating,
    )

    @Test
    fun nothingIsSharedWhileSharingIsOff() {
        val shelf = SharedShelf.build(listOf(book(1, "Дюна"), book(2, "1984")), sharingEnabled = false)

        // An empty shelf is also how "stop sharing" erases what the server held.
        assertTrue(shelf.isEmpty())
    }

    @Test
    fun aHiddenBookStaysOnTheDevice() {
        val books = listOf(book(1, "Дюна"), book(2, "Лолита"))
        val hidden = setOf(SharedShelf.bookKey("Лолита", "Автор"))

        val shelf = SharedShelf.build(books, sharingEnabled = true, hiddenKeys = hidden)

        assertEquals(listOf("Дюна"), shelf.map { it.title })
    }

    @Test
    fun aBookCarriesOnlyWhatTheFeedShows() {
        val shelf = SharedShelf.build(
            listOf(book(1, "  Дюна  ", author = " Герберт ", progress = 42.6f, lastRead = 777)),
            sharingEnabled = true,
        )

        val shared = shelf.single()
        assertEquals("Дюна", shared.title)
        assertEquals("Герберт", shared.author)
        assertEquals(43, shared.progress)
        assertEquals(777, shared.updatedAt)
        // Nothing about where the file is or where it came from.
        assertEquals(SharedShelf.bookKey("Дюна", "Герберт"), shared.bookKey)
    }

    @Test
    fun theStatusSaysFinishedStartedOrUntouched() {
        val shelf = SharedShelf.build(
            listOf(
                book(1, "Прочитана", done = true),
                book(2, "В процессе", progress = 30f),
                book(3, "Открыта но без прогресса", startedAt = 500),
                book(4, "Не начата"),
            ),
            sharingEnabled = true,
        )

        assertEquals(
            listOf(ReadingStatus.DONE, ReadingStatus.READING, ReadingStatus.READING, ReadingStatus.NOT_STARTED),
            shelf.map { it.status },
        )
    }

    @Test
    fun anUnratedBookSaysNothingRatherThanZero() {
        val shelf = SharedShelf.build(
            listOf(book(1, "Без оценки"), book(2, "С оценкой", rating = 4)),
            sharingEnabled = true,
        )

        // Zero out of five would read as a verdict; "not rated" is not one.
        assertEquals(null, shelf.first { it.title == "Без оценки" }.rating)
        assertEquals(4, shelf.first { it.title == "С оценкой" }.rating)
    }

    @Test
    fun theSameBookInTwoLibrariesGetsTheSameKey() {
        // So a feed can say "you are both reading this" without anyone sharing a file.
        assertEquals(
            SharedShelf.bookKey("Дюна", "Фрэнк Герберт"),
            SharedShelf.bookKey("  дюна ", "фрэнк  герберт"),
        )
        assertTrue(SharedShelf.bookKey("Дюна", "Герберт") != SharedShelf.bookKey("Дюна", "Оруэлл"))
    }

    @Test
    fun oneCopyPerBookEvenWhenTheLibraryHasTwo() {
        val shelf = SharedShelf.build(
            listOf(book(1, "Дюна", progress = 10f), book(2, "Дюна", progress = 90f)),
            sharingEnabled = true,
        )

        assertEquals(1, shelf.size)
    }
}
