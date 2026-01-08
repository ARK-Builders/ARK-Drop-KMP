@file:OptIn(ExperimentalForeignApi::class)

package dev.arkbuilders.drop.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.arkbuilders.drop.data.db.DropDatabase
import dev.arkbuilders.drop.data.settings.createDataStore
import dev.arkbuilders.drop.data.settings.dataStoreFileName
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual val platformModule: Module = module {

    single<DropDatabase> {
        val dbFilePath = documentDirectory() + "/${DropDatabase.DB_NAME}"

        Room.databaseBuilder<DropDatabase>(
            name = dbFilePath,
        ).setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    single<DataStore<Preferences>> {
        createDataStore {
            documentDirectory() + "/$dataStoreFileName"
        }
    }
}

private fun documentDirectory(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory?.path)
}
