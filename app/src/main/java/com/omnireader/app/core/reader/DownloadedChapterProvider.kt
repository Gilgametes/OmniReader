package com.omnireader.app.core.reader

import android.content.Context
import com.omnireader.app.core.downloads.DownloadWorker
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Reads a chapter that was previously downloaded by [com.omnireader.app.core.downloads.DownloadWorker]
 * into filesDir/downloads/<hash>/. Pages are served in natural file order (page_0001.jpg …),
 * which matches the worker's naming scheme, so offline chapters open exactly like CBZ archives.
 */
class DownloadedChapterPageProvider(
    private val context: Context,
    val chapterUrl: String,
) : PageProvider {

    private val dir: File = DownloadWorker.chapterDir(context, chapterUrl)
    override val id: String = "download:${chapterUrl.hashCode().toUInt()}"

    private val files: List<File> by lazy {
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("page_") }
            ?.sortedWith { a, b -> naturalCompare(a.name, b.name) }
            ?: emptyList()
    }

    override suspend fun pageCount(): Int = files.size

    override suspend fun pageStream(index: Int): InputStream {
        val f = files.getOrElse(index) { throw IndexOutOfBoundsException("No page $index in $dir") }
        return FileInputStream(f)
    }

    override fun close() { /* nothing held open */ }

    companion object {
        /** True when at least one page file exists on disk for this chapter. */
        fun hasContent(context: Context, chapterUrl: String): Boolean =
            DownloadWorker.chapterDir(context, chapterUrl)
                .listFiles()?.any { it.name.startsWith("page_") } == true
    }
}
