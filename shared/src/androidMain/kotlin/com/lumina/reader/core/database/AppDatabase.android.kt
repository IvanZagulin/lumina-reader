package com.lumina.reader.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The app's database: one instance per process, opened exactly as before the
 * database moved to :shared (same file name, version 8, the same migrations,
 * framework SQLite through Room's compatibility mode since no driver is set).
 */
fun AppDatabase.Companion.getDatabase(context: Context): AppDatabase =
    AndroidDatabaseInstance.value ?: synchronized(AndroidDatabaseInstance) {
        AndroidDatabaseInstance.value
            ?: databaseBuilder(context).build().also { AndroidDatabaseInstance.value = it }
    }

/**
 * A builder for the database file [name] (the app's file by default) with
 * every migration; [getDatabase] builds it once. Tests open other files with it.
 * No SQLiteDriver is set on purpose: Room then keeps using
 * SupportSQLiteOpenHelper over android.database.sqlite, like the app always has.
 */
fun AppDatabase.Companion.databaseBuilder(
    context: Context,
    name: String = DATABASE_NAME
): RoomDatabase.Builder<AppDatabase> =
    Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
        .addMigrations(*ALL_MIGRATIONS)

/** Every migration, in the order Room should know them. */
val AppDatabase.Companion.ALL_MIGRATIONS: Array<Migration>
    get() = AppDatabaseMigrations.ALL

private object AndroidDatabaseInstance {
    @Volatile
    var value: AppDatabase? = null
}
