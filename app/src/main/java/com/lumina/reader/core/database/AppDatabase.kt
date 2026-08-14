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
    version = 5,
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

        /**
         * Older builds added the library organization fields over several schema
         * versions. Each migration inspects the actual table first so every
         * supported version is upgraded without replacing the user's library.
         */
        private fun migrateLibraryOrganization(database: SupportSQLiteDatabase) {
            val existingColumns = mutableSetOf<String>()
            database.query("PRAGMA table_info(`books`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    existingColumns += cursor.getString(nameIndex)
                }
            }

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
                    MIGRATION_4_5
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
