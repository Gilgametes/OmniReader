package com.omnireader.app.core.downloads

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.omnireader.app.core.HttpFetcher
import com.omnireader.app.data.DownloadDao
import com.omnireader.app.data.DownloadEntity
import com.omnireader.app.data.DownloadState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Downloads every page image of a chapter into filesDir/downloads/<hash>/page_NNNN.<ext>.
 * Updates DownloadEntity progress/state as it goes so the global Download Manager UI
 * (and the inline Source Details chips) can render live status. Pages are fetched with a
 * small bounded parallel pool for a big speed-up on long chapters.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val http: HttpFetcher,
    private val downloadDao: DownloadDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val chapterUrl = inputData.getString(KEY_CHAPTER_URL) ?: return Result.failure()
        val mediaUrl = inputData.getString(KEY_MEDIA_URL) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: ""
        val mediaTitle = inputData.getString(KEY_MEDIA_TITLE) ?: ""
        val pages = inputData.getStringArray(KEY_PAGES)?.toList() ?: emptyList()
        if (pages.isEmpty()) {
            downloadDao.setError(chapterUrl, "No pages found")
            return Result.failure()
        }

        // Retry path: the original page URLs may be unavailable offline. If every page
        // already exists on disk we can complete without touching the network at all.
        val preDir = chapterDir(applicationContext, chapterUrl)
        val existingCount = preDir.listFiles()?.count { it.name.startsWith("page_") } ?: 0
        val resumeOnly = pages.all { !it.startsWith("http") } || existingCount >= pages.size

        downloadDao.upsert(
            DownloadEntity(
                chapterUrl = chapterUrl, mediaUrl = mediaUrl, state = DownloadState.DOWNLOADING,
                totalPages = pages.size, title = title, mediaTitle = mediaTitle,
            )
        )

        val dir = chapterDir(applicationContext, chapterUrl).apply { mkdirs() }
        // Guard shared counters / DB row writes from parallel page jobs.
        val lock = Any()
        var bytes = 0L
        var done = 0
        try {
            coroutineScope {
                Executors.newFixedThreadPool(PARALLELISM).asCoroutineDispatcher().use { dispatcher ->
                    pages.mapIndexed { i, url ->
                        async(dispatcher) {
                            if (isStopped) return@async
                            val ext = url.substringAfterLast('.', "jpg").take(4).ifBlank { "jpg" }
                            val out = File(dir, "page_%04d.%s".format(i + 1, ext))
                            val size = if (!out.exists()) {
                                if (resumeOnly) return@async // offline resume: skip missing page
                                val data = http.getBytes(url)
                                withContext(Dispatchers.IO) { out.writeBytes(data) }
                                data.size.toLong()
                            } else {
                                out.length()
                            }
                            synchronized(lock) {
                                bytes += size
                                done++
                                downloadDao.setProgress(chapterUrl, done, pages.size, bytes)
                            }
                        }
                    }.awaitAll()
                }
            }
            if (isStopped) {
                downloadDao.setState(chapterUrl, DownloadState.PAUSED)
                return Result.success()
            }
            downloadDao.setState(chapterUrl, DownloadState.COMPLETE)
            return Result.success()
        } catch (t: Throwable) {
            if (isStopped) {
                downloadDao.setState(chapterUrl, DownloadState.PAUSED)
                return Result.success()
            }
            if (runAttemptCount < MAX_RETRIES) return Result.retry()
            downloadDao.setError(chapterUrl, t.message ?: "Download failed")
            return Result.failure()
        }
    }

    companion object {
        const val KEY_CHAPTER_URL = "chapterUrl"
        const val KEY_MEDIA_URL = "mediaUrl"
        const val KEY_TITLE = "title"
        const val KEY_MEDIA_TITLE = "mediaTitle"
        const val KEY_PAGES = "pages"
        const val TAG = "omni_download"
        private const val MAX_RETRIES = 2
        private const val PARALLELISM = 4

        fun chapterDir(context: Context, chapterUrl: String): File =
            File(context.filesDir, "downloads/${chapterUrl.hashCode().toUInt()}")

        /** Delete downloaded files for a chapter and forget the row. */
        suspend fun purge(context: Context, dao: DownloadDao, entity: DownloadEntity) {
            runCatching {
                withContext(Dispatchers.IO) { chapterDir(context, entity.chapterUrl).deleteRecursively() }
            }
            dao.deleteByChapter(entity.chapterUrl)
        }

        /** Queue a chapter download; idempotent per chapter (KEEP policy). */
        fun enqueue(
            workManager: WorkManager,
            chapterUrl: String,
            mediaUrl: String,
            title: String,
            mediaTitle: String,
            pages: List<String>,
        ): UUID {
            val data = workDataOf(
                KEY_CHAPTER_URL to chapterUrl,
                KEY_MEDIA_URL to mediaUrl,
                KEY_TITLE to title,
                KEY_MEDIA_TITLE to mediaTitle,
                KEY_PAGES to pages.toTypedArray(),
            )
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(uniqueName(chapterUrl), ExistingWorkPolicy.KEEP, request)
            return request.id
        }

        /** Re-queue a paused/failed chapter with its original page list. */
        fun retry(workManager: WorkManager, context: Context, entity: DownloadEntity) {
            val dir = chapterDir(context, entity.chapterUrl)
            val pages = dir.listFiles()?.filter { it.name.startsWith("page_") }
                ?.sortedBy { it.name }?.map { it.name } ?: emptyList()
            // Prefer the recorded total; pad missing names so worker resumes cleanly.
            val urls = if (pages.isNotEmpty()) {
                List(entity.totalPages.coerceAtLeast(pages.size)) { i ->
                    "local:%s".format(pages.getOrNull(i)?.name ?: "page_%04d".format(i + 1))
                }
            } else {
                List(entity.totalPages) { i -> "page_%04d".format(i + 1) }
            }
            val data = workDataOf(
                KEY_CHAPTER_URL to entity.chapterUrl,
                KEY_MEDIA_URL to entity.mediaUrl,
                KEY_TITLE to entity.title,
                KEY_MEDIA_TITLE to entity.mediaTitle,
                KEY_PAGES to urls.toTypedArray(),
            )
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(uniqueName(entity.chapterUrl), ExistingWorkPolicy.REPLACE, request)
        }

        /** Cancel an in-flight download (worker flips state to PAUSED). */
        fun cancel(workManager: WorkManager, chapterUrl: String) =
            workManager.cancelUniqueWork(uniqueName(chapterUrl))

        fun uniqueName(chapterUrl: String): String = "dl_${chapterUrl.hashCode().toUInt()}"
    }
}
