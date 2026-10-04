package com.lumina.reader.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lumina.reader.platform.IosAppDirs

/** The iPhone app's database, opened once per process. */
fun AppDatabase.Companion.getDatabase(): AppDatabase = IosDatabaseInstance.value

/**
 * Opens the database file at [path] (by default `Application Support/lumina_reader.db`)
 * with the SQLite that is compiled into the app. A new install starts at the
 * current version, so no migrations are registered.
 */
fun createAppDatabase(
    path: String = IosAppDirs.inApplicationSupport(AppDatabase.DATABASE_NAME)
): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = path, factory = { AppDatabaseConstructor.initialize() })
        .setDriver(BundledSQLiteDriver())
        .build()

private object IosDatabaseInstance {
    val value: AppDatabase by lazy { createAppDatabase() }
}
