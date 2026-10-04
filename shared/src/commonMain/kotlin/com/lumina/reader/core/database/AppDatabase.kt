package com.lumina.reader.core.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.Bookmark
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.core.model.ReadingStats

/**
 * The library database. Entities, DAOs and this declaration are common code;
 * each platform opens it in its own source set:
 * - Android (androidMain, `AppDatabase.getDatabase(context)`): the same file
 *   (`lumina_reader.db` in the app's database directory), version and
 *   migrations as before the move, on the framework SQLite (Room's
 *   compatibility mode, no driver), so installed apps keep their data.
 * - iOS (iosMain, `createAppDatabase()`): `Application Support/lumina_reader.db`
 *   with the bundled SQLite driver.
 *
 * The schema is version 8 and must stay byte-for-byte what Room generated for
 * it before (identity hash `794b6e5292a14de48d2533fabe428539`, checked by the
 * Android tests): any change needs a new version and a migration.
 */
@Database(
    entities = [
        Book::class,
        Bookmark::class,
        ReadingHighlight::class,
        ReadingStats::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingStatsDao(): ReadingStatsDao

    /** Platform builders hang off the companion (`AppDatabase.getDatabase(context)` on Android). */
    companion object {
        /** File name of the database on every platform. */
        const val DATABASE_NAME: String = "lumina_reader.db"
    }
}

/** Room generates the `actual` object for every target (KSP, see shared/build.gradle.kts). */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
