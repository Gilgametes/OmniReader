package com.omnireader.app.core.reader

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.omnireader.app.data.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.inject.Inject

/** The engine contract: every readable format implements this. */
interface PageProvider {
    val id: String
    suspend fun pageCount(): Int
    suspend fun pageStream(index: Int): InputStream
    fun close()
}

data class PageKey(val providerId: String, val index: Int)

/** Natural sort so `2.jpg` comes before `10.jpg`. */
fun naturalCompare(a: String, b: String): Int {
    val na = a.lowercase(); val nb = b.lowercase()
    var i = 0; var j = 0
    while (i < na.length && j < nb.length) {
        val ca = na[i]; val cb = nb[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ia = i; var jb = j
            while (ia < na.length && na[ia].isDigit()) ia++
            while (jb < nb.length && nb[jb].isDigit()) jb++
            val la = na.substring(i, ia).trimStart('0').ifEmpty { "0" }
            val lb = nb.substring(j, jb).trimStart('0').ifEmpty { "0" }
            if (la.length != lb.length) return la.length - lb.length
            val cmp = la.compareTo(lb)
            if (cmp != 0) return cmp
            i = ia; j = jb
        } else {
            if (ca != cb) return ca - cb
            i++; j++
        }
    }
    return (na.length - i) - (nb.length - j)
}

class ZipPageProvider(
    private val context: Context,
    private val uri: Uri,
) : PageProvider {
    override val id = "zip:${uri}"
    private val images: List<String> by lazy { listImages() }
    private var cachedIndex: Pair<String, ByteArray>? = null

    private fun listImages(): List<String> =
        context.contentResolver.openInputStream(uri)?.use { input ->
            java.util.zip.ZipInputStream(input.buffered()).let { zip ->
                buildList {
                    var e = zip.nextEntry
                    while (e != null) {
                        val n = e.name.lowercase()
                        if (!e.isDirectory && (n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                                n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif"))
                        ) add(e.name)
                        e = zip.nextEntry
                    }
                }.sortedWith { a, b -> naturalCompare(a.substringAfterLast('/'), b.substringAfterLast('/')) }
            }
        } ?: emptyList()

    override suspend fun pageCount() = images.size

    override suspend fun pageStream(index: Int): InputStream {
        val target = images[index]
        cachedIndex?.let { (name, bytes) -> if (name == target) return ByteArrayInputStream(bytes) }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == target) {
                        val bytes = zip.readBytes()
                        cachedIndex = target to bytes
                        return@use ByteArrayInputStream(bytes)
                    }
                    e = zip.nextEntry
                }
                throw IllegalStateException("Entry $target vanished from ${uri}")
            }
        } ?: throw IllegalStateException("Cannot open $uri (SAF permission lost?)")
    }

    override fun close() { cachedIndex = null }
}

class FolderPageProvider(
    private val root: File,
) : PageProvider {
    override val id = "folder:${root.absolutePath}"
    private val files: List<File> by lazy {
        root.walkTopDown()
            .filter { it.isFile && IMAGE_EXTS.contains(it.extension.lowercase()) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            .toList()
    }

    override suspend fun pageCount() = files.size

    override suspend fun pageStream(index: Int): InputStream = FileInputStream(files[index])

    override fun close() {}

    companion object {
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
    }
}

class PdfPageProvider(
    private val context: Context,
    private val uri: Uri,
) : PageProvider {
    override val id = "pdf:${uri}"
    @Volatile private var renderer: PdfRenderer? = null
    @Volatile private var pfd: ParcelFileDescriptor? = null

    private fun ensure(): PdfRenderer = renderer ?: synchronized(this) {
        renderer ?: run {
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open $uri")
            pfd = fd
            PdfRenderer(fd).also { renderer = it }
        }
    }

    override suspend fun pageCount(): Int = ensure().pageCount

    override suspend fun pageStream(index: Int): InputStream {
        val r = ensure()
        r.openPage(index).use { page ->
            val bmp = android.graphics.Bitmap.createBitmap(
                page.width * 2, page.height * 2, android.graphics.Bitmap.Config.ARGB_8888,
            )
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val out = java.io.ByteArrayOutputStream().also {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
            }
            bmp.recycle()
            return ByteArrayInputStream(out.toByteArray())
        }
    }

    override fun close() = synchronized(this) {
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
        renderer = null; pfd = null
    }
}

/** Resolves a MediaItem into the right PageProvider. */
class ReaderEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun providerFor(type: MediaType, uriString: String): PageProvider? {
        val uri = Uri.parse(uriString)
        return when (type) {
            MediaType.ARCHIVE -> ZipPageProvider(context, uri)
            MediaType.PDF -> PdfPageProvider(context, uri)
            MediaType.FOLDER -> FolderPageProvider(File(uriString))
            MediaType.IMAGE -> ZipPageProvider(context, uri) // single images still render via Coil directly
            else -> null // EPUB/TEXT/SOURCE use the document reader instead
        }
    }

    /** Provider for a chapter that was downloaded by DownloadWorker (offline reading). */
    fun providerForDownloadedChapter(chapterUrl: String): PageProvider =
        DownloadedChapterPageProvider(context, chapterUrl)
}
