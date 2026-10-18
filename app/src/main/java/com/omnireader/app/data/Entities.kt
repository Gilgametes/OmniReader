package com.omnireader.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MediaType { ARCHIVE, IMAGE, FOLDER, PDF, EPUB, TEXT, SOURCE }

@Entity(tableName = "media_items")
data class MediaItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val description: String = "",
    val type: MediaType,
    val uri: String,
    val coverUri: String? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastRead: Long = 0,
    val isFavorite: Boolean = false,
    val sourceId: String? = null,
    val repoUrl: String? = null,
    val sourceUrl: String? = null,
)

@Entity(tableName = "repositories", primaryKeys = ["url"])
data class RepositoryEntity(
    val url: String,
    val name: String,
    val enabled: Boolean = true,
    val lastSync: Long = 0,
)

@Entity(tableName = "sources", primaryKeys = ["repoUrl", "sourceId"])
data class SourceEntity(
    val repoUrl: String,
    val sourceId: String,
    val name: String,
    val baseUrl: String,
    val lang: String = "en",
    val enabled: Boolean = true,
    val sourceJson: String,
    val lastUpdated: Long = System.currentTimeMillis(),
)

/** Download states. Kept as strings for backwards compatibility with the existing table. */
object DownloadState {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val PAUSED = "PAUSED"
    const val ERROR = "ERROR"
    const val COMPLETE = "COMPLETE"
}

@Entity(tableName = "downloads", primaryKeys = ["chapterUrl"])
data class DownloadEntity(
    val chapterUrl: String,
    val mediaUrl: String,
    val state: String,
    val progress: Int = 0,
    val totalPages: Int = 0,
    /** v2 columns */
    val title: String = "",        // chapter title, for display in the download manager
    val mediaTitle: String = "",   // parent book/manga title, used for grouping
    val bytesDownloaded: Long = 0,
    val error: String? = null,
)

/** Reading position inside a document (EPUB/TXT/PDF). One row per media item. */
@Entity(tableName = "reading_positions", primaryKeys = ["mediaId"])
data class ReadingPosition(
    val mediaId: Long,
    val spineIndex: Int = 0,   // chapter index in the EPUB spine / PDF page
    val pageIndex: Int = 0,    // paginated page within the chapter
    val scrollY: Int = 0,      // raw scroll offset for continuous mode
    val updatedAt: Long = System.currentTimeMillis(),
)

/** User bookmarks inside a document. */
@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val spineIndex: Int,
    val pageIndex: Int,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** User-defined library collections; many-to-many via [MediaCollectionCrossRef]. */
@Entity(tableName = "collections")
data class Collection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

@Entity(tableName = "media_collections", primaryKeys = ["mediaId", "collectionId"])
data class MediaCollectionCrossRef(val mediaId: Long, val collectionId: Long)
