package com.omnireader.app.core.catalog

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.omnireader.app.data.MediaDao
import com.omnireader.app.data.MediaItem
import com.omnireader.app.data.MediaType
import com.omnireader.app.core.Importer
import com.omnireader.app.core.HttpFetcher as Http
import com.omnireader.app.core.source.Source
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class CatalogOrigin { LOCAL, REMOTE }

data class UnifiedMedia(
    val key: String,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val origin: CatalogOrigin,
    val sourceName: String = "",
    val localId: Long? = null,
    val localUri: String? = null,
    val localType: MediaType? = null,
    val sourceId: String? = null,
    val repoUrl: String? = null,
    val remoteUrl: String? = null,
    val lastRead: Long = 0,
    val dateAdded: Long = 0,
)

/** Collapses duplicates across local library + remote sources; prefers local copies. */
object UnifiedDeduper {
    private fun norm(s: String) = s.lowercase()
        .replace(Regex("[^a-z0-9 ]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun dedupe(items: List<UnifiedMedia>): List<UnifiedMedia> {
        val byKey = LinkedHashMap<String, UnifiedMedia>()
        for (item in items) {
            val k = norm(item.title) + "|" + norm(item.author)
            val existing = byKey[k]
            byKey[k] = when {
                existing == null -> item
                item.origin == CatalogOrigin.LOCAL && existing.origin == CatalogOrigin.REMOTE -> item
                else -> existing
            }
        }
        return byKey.values.sortedWith(
            compareByDescending<UnifiedMedia> { it.lastRead }.thenByDescending { it.dateAdded }
        )
    }
}

/**
 * Merges the local library with popular listings from every enabled repository/source.
 * Remote fetches run in parallel and failures are isolated per-source.
 */
@Singleton
class UnifiedCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val repoDao: com.omnireader.app.data.RepositoryDao,
    private val sourceDao: com.omnireader.app.data.SourceDao,
    private val http: Http,
    private val importer: Importer,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): List<UnifiedMedia> = coroutineScope {
        val locals = mediaDao.allOnce().map { it.toUnified() }
        val remotes = sourceDao.enabledOnce().map { entity ->
            async {
                runCatching {
                    val source = json.decodeFromString<Source>(entity.sourceJson)
                    val url = source.baseUrl + (source.popularUrl ?: source.latestUrl ?: return@runCatching emptyList())
                    val html = http.getString(url)
                    val doc = org.jsoup.Jsoup.parse(html)
                    doc.select(source.list.item).mapNotNull { el ->
                        val title = el.selectFirst(source.list.title)?.text()?.trim() ?: return@mapNotNull null
                        UnifiedMedia(
                            key = "r:${entity.sourceId}:$title",
                            title = title,
                            coverUrl = source.list.cover?.let { c ->
                                el.selectFirst(c)?.attr("src")?.takeIf { it.isNotBlank() }
                            },
                            origin = CatalogOrigin.REMOTE,
                            sourceName = entity.name,
                            sourceId = entity.sourceId,
                            repoUrl = entity.repoUrl,
                            remoteUrl = el.selectFirst(source.list.title)?.attr("href")
                                ?.let { Uri.parse(entity.baseUrl).buildUpon().fragment(null).toString() + it },
                        )
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAllOrEmpty().flatten()
        UnifiedDeduper.dedupe(locals + remotes)
    }

    /** Import a whole SAF tree directory of CBZ/PDF/EPUB files. */
    suspend fun importTree(treeUri: Uri): Int {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return 0
        var count = 0
        root.listFiles().forEach { file ->
            if (!file.isFile) return@forEach
            val item = importer.toItem(file.uri) ?: return@forEach
            context.contentResolver.takePersistableUriPermission(
                file.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            if (mediaDao.insert(item) > 0) count++
        }
        return count
    }

    private fun MediaItem.toUnified() = UnifiedMedia(
        key = "l:$id",
        title = title,
        author = author,
        coverUrl = coverUri,
        origin = CatalogOrigin.LOCAL,
        sourceName = "Library",
        localId = id,
        localUri = uri,
        localType = type,
        lastRead = lastRead,
        dateAdded = dateAdded,
    )
}

private suspend fun List<kotlinx.coroutines.Deferred<List<UnifiedMedia>>>.awaitAllOrEmpty(): List<List<UnifiedMedia>> =
    map { runCatching { it.await() }.getOrDefault(emptyList()) }
