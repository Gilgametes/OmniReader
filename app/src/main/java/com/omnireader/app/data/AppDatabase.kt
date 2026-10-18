package com.omnireader.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MediaItem::class,
        RepositoryEntity::class,
        SourceEntity::class,
        DownloadEntity::class,
        ReadingPosition::class,
        Bookmark::class,
        Collection::class,
        MediaCollectionCrossRef::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun repositoryDao(): RepositoryDao
    abstract fun sourceDao(): SourceDao
    abstract fun downloadDao(): DownloadDao
    abstract fun readingPositionDao(): ReadingPositionDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        /**
         * v1 -> v2: adds metadata columns to `downloads` and creates the new tables for
         * reading positions, bookmarks and collections. Purely additive; no data is lost.
         * NO fallbackToDestructiveMigration anywhere — this migration is required.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN title TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN mediaTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN bytesDownloaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN error TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reading_positions (
                        mediaId INTEGER NOT NULL PRIMARY KEY,
                        spineIndex INTEGER NOT NULL DEFAULT 0,
                        pageIndex INTEGER NOT NULL DEFAULT 0,
                        scrollY INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mediaId INTEGER NOT NULL,
                        spineIndex INTEGER NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_mediaId ON bookmarks(mediaId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS collections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_collections (
                        mediaId INTEGER NOT NULL,
                        collectionId INTEGER NOT NULL,
                        PRIMARY KEY (mediaId, collectionId)
                    )
                    """.trimIndent()
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "omnireader.db")
                .addMigrations(MIGRATION_1_2)
                // Deliberately NO fallbackToDestructiveMigration().
                .build()
    }
}
