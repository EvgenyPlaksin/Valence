package com.lnight.valence.core.database

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideValenceDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: DatabasePassphraseProvider,
    ): ValenceDatabase = ValenceDatabaseFactory.create(context, passphraseProvider)

    @Provides
    fun provideLocalKeyStore(database: ValenceDatabase): LocalKeyStore = database.keyStore

    @Provides
    fun provideLocalSessionStore(database: ValenceDatabase): LocalSessionStore = database.sessionStore

    @Provides
    fun provideLocalContactStore(database: ValenceDatabase): LocalContactStore = database.contactStore

    @Provides
    fun provideLocalConversationStore(database: ValenceDatabase): LocalConversationStore = database.conversationStore

    @Provides
    fun provideLocalMessageStore(database: ValenceDatabase): LocalMessageStore = database.messageStore

    @Provides
    fun provideLocalOutboxStore(database: ValenceDatabase): LocalOutboxStore = database.outboxStore
}
