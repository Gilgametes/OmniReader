package com.omnireader.app.core.downloads

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omnireader.app.core.HttpFetcher
import com.omnireader.app.data.DownloadDao
import com.omnireader.app.data.DownloadEntity
import com.omnireader.app.data.DownloadState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Downloads every page image of a chapter into filesDir/downloads/<hash>/page_NNNN.<ext>.
 * Updates DownloadEntity progress/state as it goes so the global Download Manager UI
 * (and the inline Source Details chips) can render live status.
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
        @Suppress("UNCHECKED_CAST")
        val pages = inputData.getStringArray(KEY_PAGES)?.toList() ?: emptyList()
        if (pages.isEmpty()) {
            downloadDao.setError(chapterUrl, "No pages found")
            return Result.failure()
        }

        downloadDao.upsert(
            DownloadEntity(
                chapterUrl = chapterUrl, mediaUrl = mediaUrl, state = DownloadState.DOWNLOADING,
                totalPages = pages.size, title = title, mediaTitle = mediaTitle,
            )
        )

        val dir = chapterDir(applicationContext, chapterUrl).apply { mkdirs() }
        var bytes = 0L
        try {
            pages.forEachIndexed { i, url ->
                if (isStopped) {
                    downloadDao.setState(chapterUrl, DownloadState.PAUSED)
                    return Result.success()
                }
                val ext = url.substringAfterLast('.', "jpg").take(4).ifBlank { "jpg" }
                val out = File(dir, "page_%04d.%s".format(i + 1, ext))
                if (!out.exists()) {
                    val data = http.getBytes(url)
                    out.writeBytes(data)
                    bytes += data.size
                } else {
                    bytes += out.length()
                }
                downloadDao.setProgress(chapterUrl, i + 1, pages.size, bytes)
            }
            downloadDao.setState(chapterUrl, DownloadState.COMPLETE)
            return Result.success()
        } catch (t: Throwable) {
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
        private const val MAX_RETRIES = 2

        fun chapterDir(context: Context, chapterUrl: String): File =
            File(context.filesDir, "downloads/${chapterUrl.hashCode().toUInt()}")

        /** Delete downloaded files for a chapter and forget the row. */
        suspend fun purge(context: Context, dao: DownloadDao, entity: DownloadEntity) {
            runCatching { chapterDir(context, entity.chapterUrl).deleteRecursively() }
            dao.deleteByChapter(entity.chapterUrl)
        }
    }
}
