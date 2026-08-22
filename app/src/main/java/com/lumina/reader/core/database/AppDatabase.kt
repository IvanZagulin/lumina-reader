package com.lumina.reader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.Bookmark
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.core.model.ReadingStats

@Database(
    entities = [
        Book::class,
        Bookmark::class,
        ReadingHighlight::class,
        ReadingStats::class
    ],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingStatsDao(): ReadingStatsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private fun tableColumns(database: SupportSQLiteDatabase): MutableSet<String> {
            val result = mutableSetOf<String>()
            database.query("PRAGMA table_info(`books`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) result += cursor.getString(nameIndex)
            }
            return result
        }

        private fun migrateLibraryOrganization(database: SupportSQLiteDatabase) {
            val existingColumns = tableColumns(database)
            fun addColumnIfMissing(name: String, definition: String) {
                if (name !in existingColumns) {
                    database.execSQL("ALTER TABLE `books` ADD COLUMN `$name` $definition")
                    existingColumns += name
                }
            }

            addColumnIfMissing("isFavorite", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("isCompleted", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("collection", "TEXT NOT NULL DEFAULT 'Основная'")
            addColumnIfMissing("tags", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing("seriesName", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing("seriesOrder", "INTEGER NOT NULL DEFAULT 0")
        }

        private fun migrateCompletionHistory(database: SupportSQLiteDatabase) {
            val existingColumns = tableColumns(database)
            if ("completedAt" !in existingColumns) {
                database.execSQL("ALTER TABLE `books` ADD COLUMN `completedAt` INTEGER")
            }
            database.execSQL(
                """
                UPDATE `books`
                SET `completedAt` = `lastReadTimestamp`
                WHERE `completedAt` IS NULL
                  AND (`isCompleted` = 1 OR `currentProgressPercent` >= 99.0)
                """.trimIndent()
            )
        }

        private fun migrateStartedHistory(database: SupportSQLiteDatabase) {
            val existingColumns = tableColumns(database)
            if ("startedAt" !in existingColumns) {
                database.execSQL("ALTER TABLE `books` ADD COLUMN `startedAt` INTEGER")
            }

            // Prefer the first real reading session. This keeps historical
            // completion-duration analytics useful after upgrading from v6.
            database.execSQL(
                """
                UPDATE `books`
                SET `startedAt` = (
                    SELECT MIN(`timestamp`)
                    FROM `reading_stats`
                    WHERE `reading_stats`.`bookId` = `books`.`id`
                )
                WHERE `startedAt` IS NULL
                  AND (`currentProgressPercent` > 0.0 OR `isCompleted` = 1)
                  AND EXISTS (
                    SELECT 1 FROM `reading_stats`
                    WHERE `reading_stats`.`bookId` = `books`.`id`
                  )
                """.trimIndent()
            )
            database.execSQL(
                """
                UPDATE `books`
                SET `startedAt` = COALESCE(`completedAt`, `lastReadTimestamp`)
                WHERE `startedAt` IS NULL
                  AND (`currentProgressPercent` > 0.0 OR `isCompleted` = 1)
                """.trimIndent()
            )
        }

        val MIGRATION_1_5 = object : Migration(1, 5) {
            override fun migrate(database: SupportSQLiteDatabase) = migrateLibraryOrganization(database)
        }
        val MIGRATION_2_5 = object : Migration(2, 5) {
            override fun migrate(database: SupportSQLiteDatabase) = migrateLibraryOrganization(database)
        }
        val MIGRATION_3_5 = object : Migration(3, 5) {
            override fun migrate(database: SupportSQLiteDatabase) = migrateLibraryOrganization(database)
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) = migrateLibraryOrganization(database)
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) = migrateCompletionHistory(database)
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) = migrateStartedHistory(database)
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lumina_reader.db"
                ).addMigrations(
                    MIGRATION_1_5,
                    MIGRATION_2_5,
                    MIGRATION_3_5,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
