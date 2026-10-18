package com.omnireader.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.WorkManager
import com.omnireader.app.data.AppDatabase
import com.omnireader.app.data.BookmarkDao
import com.omnireader.app.data.CollectionDao
import com.omnireader.app.data.DownloadDao
import com.omnireader.app.data.MediaDao
import com.omnireader.app.data.ReadingPositionDao
import com.omnireader.app.data.RepositoryDao
import com.omnireader.app.data.SourceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase = AppDatabase.create(context)

    @Provides fun mediaDao(db: AppDatabase): MediaDao = db.mediaDao()
    @Provides fun repositoryDao(db: AppDatabase): RepositoryDao = db.repositoryDao()
    @Provides fun sourceDao(db: AppDatabase): SourceDao = db.sourceDao()
    @Provides fun downloadDao(db: AppDatabase): DownloadDao = db.downloadDao()
    @Provides fun readingPositionDao(db: AppDatabase): ReadingPositionDao = db.readingPositionDao()
    @Provides fun bookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun collectionDao(db: AppDatabase): CollectionDao = db.collectionDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun okHttp(): OkHttpClient = com.omnireader.app.core.HttpFetcher.defaultClient()

    @Provides @Singleton
    fun httpFetcher(client: OkHttpClient) = com.omnireader.app.core.HttpFetcher(client)

    @Provides @Singleton
    fun sourceFetcher(http: com.omnireader.app.core.HttpFetcher) =
        com.omnireader.app.core.source.SourceFetcher(http)

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {
    @Provides @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.dataStore
}
