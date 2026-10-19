package com.lumina.social

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A registered reader. */
data class User(val id: String, val displayName: String)

/**
 * All storage of the service, over plain JDBC.
 *
 * The schema is three tables and the queries are a handful, so there is no ORM:
 * the SQL is standard enough to run on PostgreSQL in production and on H2 in the
 * tests unchanged. Every call hops to [Dispatchers.IO] because JDBC blocks and
 * Ktor's handlers do not.
 */
class Database(private val dataSource: DataSource) {

    companion object {
        fun postgres(url: String, user: String, password: String): Database {
            val config = HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 8
                driverClassName = "org.postgresql.Driver"
            }
            return Database(HikariDataSource(config))
        }
    }

    fun migrate() = tx { connection ->
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id VARCHAR PRIMARY KEY,
                    display_name VARCHAR NOT NULL,
                    token_hash VARCHAR NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_token ON users(token_hash)")
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS invites (
                    code VARCHAR PRIMARY KEY,
                    created_at BIGINT NOT NULL,
                    redeemed_by VARCHAR
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS shared_books (
                    user_id VARCHAR NOT NULL,
                    book_key VARCHAR NOT NULL,
                    title VARCHAR NOT NULL,
                    author VARCHAR NOT NULL,
                    rating INT,
                    progress INT NOT NULL,
                    status VARCHAR NOT NULL,
                    updated_at BIGINT NOT NULL,
                    series_name VARCHAR,
                    series_order INT,
                    PRIMARY KEY (user_id, book_key)
                )
                """.trimIndent(),
            )
            // The series arrived after the shelf did: a running server needs the
            // columns added, so a feed can group volumes instead of listing them flat.
            statement.executeUpdate("ALTER TABLE shared_books ADD COLUMN IF NOT EXISTS series_name VARCHAR")
            statement.executeUpdate("ALTER TABLE shared_books ADD COLUMN IF NOT EXISTS series_order INT")
            // Books in transit. The row is the record of what is on disk, so
            // the quota is a sum over it and the file is deleted with the row.
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS transfers (
                    id VARCHAR PRIMARY KEY,
                    from_user VARCHAR NOT NULL,
                    to_user VARCHAR NOT NULL,
                    title VARCHAR NOT NULL,
                    author VARCHAR NOT NULL,
                    series_name VARCHAR,
                    series_order INT NOT NULL,
                    format VARCHAR NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    shelf VARCHAR
                )
                """.trimIndent(),
            )
            // The shelf arrived after the table did, so a server that is already
            // running needs the column added rather than the table created.
            statement.executeUpdate("ALTER TABLE transfers ADD COLUMN IF NOT EXISTS shelf VARCHAR")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transfers_to ON transfers(to_user)")
            // "Send me this one": a note for the owner's app, which fulfils it
            // by uploading the book the next time it runs. It holds no file, so
            // it costs a row and nothing more.
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS book_requests (
                    id VARCHAR PRIMARY KEY,
                    from_user VARCHAR NOT NULL,
                    to_user VARCHAR NOT NULL,
                    book_key VARCHAR NOT NULL,
                    title VARCHAR NOT NULL,
                    author VARCHAR NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_requests_to ON book_requests(to_user)")
            // Asking twice for the same book is the same ask, not two.
            statement.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_requests_once ON book_requests(from_user, to_user, book_key)",
            )
        }
    }

    // ---- Invites -------------------------------------------------------------------

    suspend fun createInvite(code: String, now: Long): Unit = io {
        tx { connection ->
            connection.prepareStatement("INSERT INTO invites(code, created_at, redeemed_by) VALUES (?, ?, NULL)").use {
                it.setString(1, code)
                it.setLong(2, now)
                it.executeUpdate()
            }
        }
    }

    /** True when the code exists and no one has used it yet. */
    private fun isInviteOpen(connection: Connection, code: String): Boolean {
        connection.prepareStatement("SELECT redeemed_by FROM invites WHERE code = ?").use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return false
                rows.getString("redeemed_by")
                return rows.wasNull()
            }
        }
    }

    // ---- Users ---------------------------------------------------------------------

    /**
     * Turns an open invite into a user, inside one transaction so the same code
     * can never make two users even if it is redeemed twice at once. Returns the
     * new user and the raw token (stored only as its hash), or null when the code
     * is unknown or already used.
     */
    suspend fun redeemInvite(code: String, displayName: String, now: Long): RedeemResponse? = io {
        tx { connection ->
            connection.autoCommit = false
            try {
                if (!isInviteOpen(connection, code)) {
                    connection.rollback()
                    return@tx null
                }
                val userId = java.util.UUID.randomUUID().toString()
                val token = Secrets.newToken()
                connection.prepareStatement(
                    "INSERT INTO users(id, display_name, token_hash, created_at) VALUES (?, ?, ?, ?)",
                ).use {
                    it.setString(1, userId)
                    it.setString(2, displayName)
                    it.setString(3, Secrets.sha256(token))
                    it.setLong(4, now)
                    it.executeUpdate()
                }
                connection.prepareStatement("UPDATE invites SET redeemed_by = ? WHERE code = ? AND redeemed_by IS NULL").use {
                    it.setString(1, userId)
                    it.setString(2, code)
                    val changed = it.executeUpdate()
                    if (changed != 1) {
                        connection.rollback()
                        return@tx null
                    }
                }
                connection.commit()
                RedeemResponse(userId = userId, token = token)
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    suspend fun userByToken(token: String): User? = io {
        val hash = Secrets.sha256(token)
        tx { connection ->
            connection.prepareStatement("SELECT id, display_name FROM users WHERE token_hash = ?").use { statement ->
                statement.setString(1, hash)
                statement.executeQuery().use { rows ->
                    if (rows.next()) User(rows.getString("id"), rows.getString("display_name")) else null
                }
            }
        }
    }

    // ---- Shared shelves ------------------------------------------------------------

    /** Replaces the caller's whole shared shelf: what the app pushes is the truth. */
    suspend fun replaceBooks(userId: String, books: List<SharedBook>): Unit = io {
        tx { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM shared_books WHERE user_id = ?").use {
                    it.setString(1, userId)
                    it.executeUpdate()
                }
                if (books.isNotEmpty()) {
                    connection.prepareStatement(
                        """
                        INSERT INTO shared_books(user_id, book_key, title, author, rating, progress, status, updated_at, series_name, series_order)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        for (book in books) {
                            statement.setString(1, userId)
                            statement.setString(2, book.bookKey)
                            statement.setString(3, book.title)
                            statement.setString(4, book.author)
                            if (book.rating == null) statement.setNull(5, java.sql.Types.INTEGER) else statement.setInt(5, book.rating)
                            statement.setInt(6, book.progress)
                            statement.setString(7, book.status)
                            statement.setLong(8, book.updatedAt)
                            if (book.seriesName == null) {
                                statement.setNull(9, java.sql.Types.VARCHAR)
                            } else {
                                statement.setString(9, book.seriesName)
                            }
                            statement.setInt(10, book.seriesOrder)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /** Everyone except [excludingUserId], each with their shared shelf. */
    suspend fun circle(excludingUserId: String): List<Member> = io {
        tx { connection ->
            val names = HashMap<String, String>()
            connection.prepareStatement("SELECT id, display_name FROM users WHERE id <> ?").use { statement ->
                statement.setString(1, excludingUserId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) names[rows.getString("id")] = rows.getString("display_name")
                }
            }
            if (names.isEmpty()) return@tx emptyList()

            val booksByUser = HashMap<String, MutableList<SharedBook>>()
            connection.prepareStatement(
                """
                SELECT user_id, book_key, title, author, rating, progress, status, updated_at,
                       series_name, series_order
                FROM shared_books WHERE user_id <> ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, excludingUserId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val ratingValue = rows.getInt("rating")
                        val rating = if (rows.wasNull()) null else ratingValue
                        val book = SharedBook(
                            bookKey = rows.getString("book_key"),
                            title = rows.getString("title"),
                            author = rows.getString("author"),
                            rating = rating,
                            progress = rows.getInt("progress"),
                            status = rows.getString("status"),
                            updatedAt = rows.getLong("updated_at"),
                            seriesName = rows.getString("series_name"),
                            seriesOrder = rows.getInt("series_order"),
                        )
                        booksByUser.getOrPut(rows.getString("user_id")) { mutableListOf() }.add(book)
                    }
                }
            }
            names.entries
                .sortedBy { it.value.lowercase() }
                .map { (id, name) ->
                    Member(id, name, booksByUser[id].orEmpty().sortedByDescending { it.updatedAt })
                }
        }
    }

    // ---- Transfers ------------------------------------------------------------------

    /** Is this a real member? A book may only be sent to someone in the circle. */
    suspend fun userExists(userId: String): Boolean = io {
        tx { connection ->
            connection.prepareStatement("SELECT 1 FROM users WHERE id = ?").use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rows -> rows.next() }
            }
        }
    }

    /** Bytes waiting to be collected, all members together - the quota is checked against this. */
    suspend fun pendingTransferBytes(): Long = io {
        tx { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COALESCE(SUM(size_bytes), 0) FROM transfers").use { rows ->
                    if (rows.next()) rows.getLong(1) else 0L
                }
            }
        }
    }

    suspend fun createTransfer(
        id: String,
        fromUser: String,
        toUser: String,
        title: String,
        author: String,
        seriesName: String?,
        seriesOrder: Int,
        format: String,
        sizeBytes: Long,
        now: Long,
        shelf: String? = null,
    ): Unit = io {
        tx { connection ->
            connection.prepareStatement(
                """
                INSERT INTO transfers(id, from_user, to_user, title, author, series_name, series_order, format, size_bytes, created_at, shelf)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use {
                it.setString(1, id)
                it.setString(2, fromUser)
                it.setString(3, toUser)
                it.setString(4, title)
                it.setString(5, author)
                if (seriesName == null) it.setNull(6, java.sql.Types.VARCHAR) else it.setString(6, seriesName)
                it.setInt(7, seriesOrder)
                it.setString(8, format)
                it.setLong(9, sizeBytes)
                it.setLong(10, now)
                if (shelf == null) it.setNull(11, java.sql.Types.VARCHAR) else it.setString(11, shelf)
                it.executeUpdate()
            }
        }
    }

    /** Everything sent to [userId] and not yet collected, oldest first. */
    suspend fun incomingTransfers(userId: String): List<TransferInfo> = io {
        tx { connection ->
            connection.prepareStatement(
                """
                SELECT t.id, t.from_user, u.display_name, t.title, t.author, t.series_name,
                       t.series_order, t.format, t.size_bytes, t.created_at, t.shelf
                FROM transfers t LEFT JOIN users u ON u.id = t.from_user
                WHERE t.to_user = ?
                ORDER BY t.created_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rows ->
                    val found = mutableListOf<TransferInfo>()
                    while (rows.next()) found += rows.toTransferInfo()
                    found
                }
            }
        }
    }

    /** The transfer, only if [userId] is the one it was sent to. */
    suspend fun transferForRecipient(id: String, userId: String): TransferInfo? = io {
        tx { connection ->
            connection.prepareStatement(
                """
                SELECT t.id, t.from_user, u.display_name, t.title, t.author, t.series_name,
                       t.series_order, t.format, t.size_bytes, t.created_at, t.shelf
                FROM transfers t LEFT JOIN users u ON u.id = t.from_user
                WHERE t.id = ? AND t.to_user = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, userId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.toTransferInfo() else null
                }
            }
        }
    }

    /** Who sent [id], so a sender can cancel what nobody collected yet. */
    suspend fun transferSender(id: String): String? = io {
        tx { connection ->
            connection.prepareStatement("SELECT from_user FROM transfers WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }
    }

    suspend fun deleteTransfer(id: String): Unit = io {
        tx { connection ->
            connection.prepareStatement("DELETE FROM transfers WHERE id = ?").use {
                it.setString(1, id)
                it.executeUpdate()
            }
        }
    }

    /** Ids of transfers older than [createdBefore] - what the sweeper drops. */
    suspend fun expiredTransferIds(createdBefore: Long): List<String> = io {
        tx { connection ->
            connection.prepareStatement("SELECT id FROM transfers WHERE created_at < ?").use { statement ->
                statement.setLong(1, createdBefore)
                statement.executeQuery().use { rows ->
                    val ids = mutableListOf<String>()
                    while (rows.next()) ids += rows.getString(1)
                    ids
                }
            }
        }
    }

    /** Every known id, so files with no row behind them can be found and removed. */
    suspend fun allTransferIds(): Set<String> = io {
        tx { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM transfers").use { rows ->
                    val ids = mutableSetOf<String>()
                    while (rows.next()) ids += rows.getString(1)
                    ids
                }
            }
        }
    }

    // ---- Requests -------------------------------------------------------------------

    /** How many asks are already waiting for [ownerId] to answer; the cap is checked against this. */
    suspend fun pendingRequestCount(ownerId: String): Int = io {
        tx { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM book_requests WHERE to_user = ?").use { statement ->
                statement.setString(1, ownerId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        }
    }

    /**
     * Records an ask, or leaves the existing one alone: the unique index makes
     * a repeated tap the same request rather than a second one. Returns the id
     * of the row that now stands.
     */
    suspend fun createRequest(
        id: String,
        fromUser: String,
        toUser: String,
        bookKey: String,
        title: String,
        author: String,
        now: Long,
    ): String = io {
        tx { connection ->
            connection.prepareStatement(
                """
                SELECT id FROM book_requests WHERE from_user = ? AND to_user = ? AND book_key = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, fromUser)
                statement.setString(2, toUser)
                statement.setString(3, bookKey)
                statement.executeQuery().use { rows -> if (rows.next()) return@tx rows.getString(1) }
            }
            connection.prepareStatement(
                """
                INSERT INTO book_requests(id, from_user, to_user, book_key, title, author, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use {
                it.setString(1, id)
                it.setString(2, fromUser)
                it.setString(3, toUser)
                it.setString(4, bookKey)
                it.setString(5, title)
                it.setString(6, author)
                it.setLong(7, now)
                it.executeUpdate()
            }
            id
        }
    }

    /** What [ownerId] has been asked for, oldest first. */
    suspend fun pendingRequests(ownerId: String): List<BookRequest> = io {
        tx { connection ->
            connection.prepareStatement(
                """
                SELECT r.id, r.from_user, u.display_name, r.book_key, r.title, r.author, r.created_at
                FROM book_requests r LEFT JOIN users u ON u.id = r.from_user
                WHERE r.to_user = ?
                ORDER BY r.created_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, ownerId)
                statement.executeQuery().use { rows ->
                    val found = mutableListOf<BookRequest>()
                    while (rows.next()) {
                        found += BookRequest(
                            id = rows.getString("id"),
                            fromUserId = rows.getString("from_user"),
                            fromDisplayName = rows.getString("display_name") ?: "Бывший участник",
                            bookKey = rows.getString("book_key"),
                            title = rows.getString("title"),
                            author = rows.getString("author"),
                            createdAt = rows.getLong("created_at"),
                        )
                    }
                    found
                }
            }
        }
    }

    /** Removes the ask when either side is done with it; false when it was not theirs. */
    suspend fun deleteRequest(id: String, actingUserId: String): Boolean = io {
        tx { connection ->
            connection.prepareStatement(
                "DELETE FROM book_requests WHERE id = ? AND (to_user = ? OR from_user = ?)",
            ).use {
                it.setString(1, id)
                it.setString(2, actingUserId)
                it.setString(3, actingUserId)
                it.executeUpdate() > 0
            }
        }
    }

    private fun java.sql.ResultSet.toTransferInfo(): TransferInfo = TransferInfo(
        id = getString("id"),
        fromUserId = getString("from_user"),
        // A sender who left the circle leaves the row behind; name the gap.
        fromDisplayName = getString("display_name") ?: "Бывший участник",
        title = getString("title"),
        author = getString("author"),
        seriesName = getString("series_name"),
        seriesOrder = getInt("series_order"),
        format = getString("format"),
        sizeBytes = getLong("size_bytes"),
        createdAt = getLong("created_at"),
        shelf = getString("shelf"),
    )

    private fun <T> tx(block: (Connection) -> T): T =
        dataSource.connection.use { connection -> block(connection) }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
