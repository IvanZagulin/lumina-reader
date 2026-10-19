package com.lumina.reader.ui.social

import com.lumina.reader.core.model.Book
import com.lumina.reader.core.social.SharedBook
import com.lumina.reader.core.social.SharedShelf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Arranging a library for the send sheet, and a friend's shelf for the feed.
 *
 * The ordering is the point: a series has to read as one, two, three, and the
 * two warnings - "they have it" and "too big to send" - have to be on the row
 * before anyone ticks it.
 */
class SendSelectionTest {

    private fun book(
        id: Long,
        title: String,
        author: String = "Автор",
        series: String = "",
        order: Int = 0,
        size: Long = 1024,
        rating: Int = 0,
    ) = Book(
        id = id,
        title = title,
        author = author,
        filePath = "/books/$id.epub",
        seriesName = series,
        seriesOrder = order,
        fileSizeBytes = size,
        rating = rating,
    )

    @Test
    fun aSeriesComesFirstInVolumeOrderAndLooseBooksFollowByTitle() {
        val groups = groupForSending(
            books = listOf(
                book(1, "Яблоко"),
                book(2, "Мессия Дюны", series = "Дюна", order = 2),
                book(3, "Арбуз"),
                book(4, "Дюна", series = "Дюна", order = 1),
            ),
            theirBookKeys = emptySet(),
        )

        assertEquals(2, groups.size)
        assertEquals("Дюна", groups[0].series)
        assertEquals(listOf("Дюна", "Мессия Дюны"), groups[0].books.map { it.book.title })
        assertEquals(null, groups[1].series, "loose books go last, in their own group")
        assertEquals(listOf("Арбуз", "Яблоко"), groups[1].books.map { it.book.title })
    }

    @Test
    fun anUnnumberedVolumeSitsAfterTheNumberedOnes() {
        val groups = groupForSending(
            books = listOf(
                book(1, "Приложение", series = "Дюна", order = 0),
                book(2, "Дюна", series = "Дюна", order = 1),
            ),
            theirBookKeys = emptySet(),
        )

        assertEquals(listOf("Дюна", "Приложение"), groups.single().books.map { it.book.title })
    }

    @Test
    fun severalSeriesAreOrderedByName() {
        val groups = groupForSending(
            books = listOf(
                book(1, "Ведьмак", series = "Ведьмак", order = 1),
                book(2, "Дюна", series = "Дюна", order = 1),
            ),
            theirBookKeys = emptySet(),
        )

        assertEquals(listOf("Ведьмак", "Дюна"), groups.map { it.series })
    }

    @Test
    fun theSearchLooksAtTitleAuthorAndSeries() {
        val library = listOf(
            book(1, "Дюна", author = "Герберт", series = "Хроники Дюны", order = 1),
            book(2, "Ведьмак", author = "Сапковский"),
            book(3, "Солярис", author = "Лем"),
        )

        assertEquals(
            listOf("Ведьмак"),
            groupForSending(library, emptySet(), query = "сапков").flatMap { it.books }.map { it.book.title },
        )
        assertEquals(
            listOf("Дюна"),
            groupForSending(library, emptySet(), query = "хроники").flatMap { it.books }.map { it.book.title },
        )
        assertEquals(
            listOf("Солярис"),
            groupForSending(library, emptySet(), query = "СОЛЯР").flatMap { it.books }.map { it.book.title },
        )
        assertTrue(groupForSending(library, emptySet(), query = "ничего такого").isEmpty())
    }

    @Test
    fun aBookTheyAlreadyHaveIsMarkedButStillSelectable() {
        val mine = book(1, "Дюна", author = "Герберт")
        val theirs = setOf(SharedShelf.bookKey("Дюна", "Герберт"))

        val sendable = groupForSending(listOf(mine), theirs).single().books.single()

        assertTrue(sendable.alreadyTheirs)
        // Marked, not forbidden: re-sending is how a lost shelf gets fixed.
        assertTrue(sendable.selectable)
    }

    @Test
    fun aBookOverTheCapIsMarkedAndCannotBeChosen() {
        val groups = groupForSending(
            books = listOf(book(1, "Атлас", size = 80L * 1024 * 1024)),
            theirBookKeys = emptySet(),
            maxBytes = 50L * 1024 * 1024,
        )

        val sendable = groups.single().books.single()
        assertTrue(sendable.tooLarge)
        assertFalse(sendable.selectable)
        assertTrue(groups.single().selectableIds.isEmpty(), "select-all must skip what cannot be sent")
    }

    @Test
    fun aBookWithoutATitleIsNotOfferedAtAll() {
        val groups = groupForSending(listOf(book(1, "   ")), emptySet())
        assertTrue(groups.isEmpty())
    }

    // ---- the feed side -------------------------------------------------------------

    private fun shared(
        title: String,
        series: String? = null,
        order: Int = 0,
        updatedAt: Long = 0,
    ) = SharedBook(
        bookKey = SharedShelf.bookKey(title, "Автор"),
        title = title,
        author = "Автор",
        seriesName = series,
        seriesOrder = order,
        updatedAt = updatedAt,
    )

    @Test
    fun aFriendsShelfGroupsItsSeriesAndKeepsLooseBooksRecentFirst() {
        val groups = groupSharedShelf(
            listOf(
                shared("Старое", updatedAt = 10),
                shared("Мессия Дюны", series = "Дюна", order = 2),
                shared("Свежее", updatedAt = 99),
                shared("Дюна", series = "Дюна", order = 1),
            ),
        )

        assertEquals("Дюна", groups[0].series)
        assertEquals(listOf("Дюна", "Мессия Дюны"), groups[0].books.map { it.title })
        assertEquals(null, groups[1].series)
        assertEquals(listOf("Свежее", "Старое"), groups[1].books.map { it.title })
    }

    @Test
    fun aShelfWithNoSeriesIsJustOneGroup() {
        val groups = groupSharedShelf(listOf(shared("Одна"), shared("Другая")))
        assertEquals(1, groups.size)
        assertEquals(null, groups.single().series)
    }

    @Test
    fun sizesReadTheWayAPersonWouldSayThem() {
        assertEquals("", formatBookSize(0))
        assertEquals("512 Б", formatBookSize(512))
        assertEquals("2 КБ", formatBookSize(2048))
        assertEquals("1,5 МБ", formatBookSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("42 МБ", formatBookSize(42L * 1024 * 1024))
    }
}
