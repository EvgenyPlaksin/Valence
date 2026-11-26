package com.lnight.valence.core.database

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object ValenceDatabaseFactory {
    private const val DATABASE_NAME = "valence.db"

    fun create(
        context: Context,
        passphraseProvider: DatabasePassphraseProvider,
        databaseName: String = DATABASE_NAME,
    ): ValenceDatabase {
        System.loadLibrary("sqlcipher")

        val passphrase = passphraseProvider.takePassphrase()
        val factory = SupportOpenHelperFactory(passphrase)

        val roomDatabase =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    RoomValenceDatabase::class.java,
                    databaseName,
                ).openHelperFactory(factory)
                .build()

        return ValenceDatabaseImpl(roomDatabase)
    }

    fun createInMemory(
        context: Context,
        passphraseProvider: DatabasePassphraseProvider,
    ): ValenceDatabase {
        System.loadLibrary("sqlcipher")

        val passphrase = passphraseProvider.takePassphrase()
        val factory = SupportOpenHelperFactory(passphrase)

        val roomDatabase =
            Room
                .inMemoryDatabaseBuilder(
                    context.applicationContext,
                    RoomValenceDatabase::class.java,
                ).openHelperFactory(factory)
                .build()

        return ValenceDatabaseImpl(roomDatabase)
    }
}
