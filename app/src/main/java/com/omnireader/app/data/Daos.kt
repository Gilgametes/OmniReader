package com.omnireader.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items ORDER BY lastRead DESC, dateAdded DESC")
    fun observeAll(): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_items WHERE isFavorite = 1 ORDER BY lastRead DESC")
    fun observeFavorites(): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun byId(id: Long): MediaItem?

    @Query("SELECT * FROM media_items WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): MediaItem?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: MediaItem): Long

    @Update
    suspend fun update(item: MediaItem)

    @Delete
    suspend fun delete(item: MediaItem)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE media_items SET lastRead = :ts WHERE id = :id")
    suspend fun touchLastRead(id: Long, ts: Long = System.currentTimeMillis())

    @Query("UPDATE media_items SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("SELECT * FROM media_items")
    suspend fun allOnce(): List<MediaItem>
}

@Dao
interface RepositoryDao {
    @Query("SELECT * FROM repositories ORDER BY name")
    fun observeAll(): Flow<List<RepositoryEntity>>

    @Query("SELECT * FROM repositories WHERE enabled = 1")
    suspend fun enabledOnce(): List<RepositoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(repo: RepositoryEntity)

    @Query("UPDATE repositories SET enabled = :enabled WHERE url = :url")
    suspend fun setEnabled(url: String, enabled: Boolean)

    @Query("UPDATE repositories SET lastSync = :ts WHERE url = :url")
    suspend fun touchSync(url: String, ts: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(repo: RepositoryEntity)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1")
    suspend fun enabledOnce(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE repoUrl = :repoUrl AND sourceId = :sourceId LIMIT 1")
    suspend fun one(repoUrl: String, sourceId: String): SourceEntity?

    @Query("SELECT * FROM sources")
    suspend fun allOnce(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    @Query("UPDATE sources SET enabled = :enabled WHERE repoUrl = :repoUrl AND sourceId = :sourceId")
    suspend fun setEnabled(repoUrl: String, sourceId: String, enabled: Boolean)

    @Query("DELETE FROM sources WHERE repoUrl = :repoUrl")
    suspend fun deleteByRepo(repoUrl: String)
}

/** A download plus its resolved parent media item (if the book was imported). */
data class DownloadWithMedia(
    @Embedded val download: DownloadEntity,
    @Relation(parentColumn = "mediaUrl", entityColumn = "uri")
    val media: MediaItem?,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY mediaTitle, title")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Transaction
    @Query("SELECT * FROM downloads ORDER BY mediaTitle, title")
    fun observeAllWithMedia(): Flow<List<DownloadWithMedia>>

    @Query("SELECT * FROM downloads WHERE mediaUrl = :mediaUrl")
    suspend fun forMedia(mediaUrl: String): List<DownloadEntity>

    /** Live rows for one book (used by Source Details chips + reading offline chapters). */
    @Query("SELECT * FROM downloads WHERE mediaUrl = :mediaUrl")
    fun observeForMedia(mediaUrl: String): Flow<List<DownloadEntity>>

    /** Grouped live view of every download belonging to a media item. */
    @Transaction
    @Query("SELECT * FROM downloads WHERE mediaUrl = :mediaUrl ORDER BY title")
    fun observeWithMedia(mediaUrl: String): Flow<List<DownloadWithMedia>>

    @Query("SELECT * FROM downloads WHERE chapterUrl = :chapterUrl LIMIT 1")
    suspend fun byChapter(chapterUrl: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("UPDATE downloads SET state = :state, error = NULL WHERE chapterUrl = :chapterUrl")
    suspend fun setState(chapterUrl: String, state: String)

    @Query("UPDATE downloads SET progress = :progress, totalPages = :total, bytesDownloaded = :bytes WHERE chapterUrl = :chapterUrl")
    suspend fun setProgress(chapterUrl: String, progress: Int, total: Int, bytes: Long)

    @Query("UPDATE downloads SET state = :state, error = :error WHERE chapterUrl = :chapterUrl")
    suspend fun setError(chapterUrl: String, error: String, state: String = DownloadState.ERROR)

    @Query("DELETE FROM downloads WHERE chapterUrl = :chapterUrl")
    suspend fun deleteByChapter(chapterUrl: String)

    /** Bulk cleanup helper (e.g. "clear completed"). Files must be purged separately. */
    @Query("DELETE FROM downloads WHERE state = :state")
    suspend fun deleteByState(state: String)

    @Query("DELETE FROM downloads WHERE mediaUrl = :mediaUrl")
    suspend fun deleteByMedia(mediaUrl: String)

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('QUEUED','DOWNLOADING')")
    fun observeActiveCount(): Flow<Int>
}

@Dao
interface ReadingPositionDao {
    @Query("SELECT * FROM reading_positions WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): ReadingPosition?

    @Upsert
    suspend fun save(position: ReadingPosition)

    @Query("DELETE FROM reading_positions WHERE mediaId = :mediaId")
    suspend fun clear(mediaId: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE mediaId = :mediaId ORDER BY spineIndex, pageIndex")
    fun observeFor(mediaId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun insert(bookmark: Bookmark): Long

    @Delete
    suspend fun delete(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE mediaId = :mediaId AND spineIndex = :spine AND pageIndex = :page")
    suspend fun removeAt(mediaId: Long, spine: Int, page: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE mediaId = :mediaId AND spineIndex = :spine AND pageIndex = :page)")
    suspend fun existsAt(mediaId: Long, spine: Int, page: Int): Boolean
}

data class CollectionWithItems(
    @Embedded val collection: Collection,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = MediaCollectionCrossRef::class,
            parentColumn = "collectionId",
            entityColumn = "mediaId",
        ),
    )
    val items: List<MediaItem>,
)

@Dao
interface CollectionDao {
    @Transaction
    @Query("SELECT * FROM collections ORDER BY sortOrder, name")
    fun observeAllWithItems(): Flow<List<CollectionWithItems>>

    @Query("SELECT * FROM collections ORDER BY name")
    suspend fun allOnce(): List<Collection>

    @Insert
    suspend fun insert(collection: Collection): Long

    @Update
    suspend fun update(collection: Collection)

    @Delete
    suspend fun delete(collection: Collection)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: MediaCollectionCrossRef)

    @Query("DELETE FROM media_collections WHERE mediaId = :mediaId AND collectionId = :collectionId")
    suspend fun unlink(mediaId: Long, collectionId: Long)

    @Query("DELETE FROM media_collections WHERE mediaId = :mediaId")
    suspend fun unlinkAllForMedia(mediaId: Long)

    @Query("DELETE FROM media_collections WHERE collectionId = :collectionId")
    suspend fun unlinkAllForCollection(collectionId: Long)
}
