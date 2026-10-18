package com.omnireader.app.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.omnireader.app.data.MediaItem
import com.omnireader.app.data.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SAF-only import logic. Takes persistable content URIs granted by
 * ACTION_OPEN_DOCUMENT; never touches raw paths or broad storage permissions.
 */
@Singleton
class Importer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Classify a picked document into a [MediaType]; null = unsupported. */
    fun detectType(uri: Uri): MediaType? {
        val name = displayName(uri).lowercase()
        val mime = context.contentResolver.getType(uri) ?: ""
        return when {
            mime == "application/epub+zip" || name.endsWith(".epub") -> MediaType.EPUB
            mime == "application/pdf" || name.endsWith(".pdf") -> MediaType.PDF
            name.endsWith(".cbz") || name.endsWith(".zip") ||
                mime == "application/zip" || mime == "application/x-cbz" -> MediaType.ARCHIVE
            name.endsWith(".txt") || mime == "text/plain" -> MediaType.TEXT
            mime.startsWith("image/") -> MediaType.IMAGE
            else -> null
        }
    }

    fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "untitled"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !cursor.isNull(idx)) name = cursor.getString(idx)
                }
            }
        return name.substringBeforeLast('.')
    }

    /**
     * Build a MediaItem for a freshly picked URI. Copies the cover out of archives
     * into app-private storage so it renders without holding a provider permission.
     */
    suspend fun toItem(uri: Uri): MediaItem? {
        val type = detectType(uri) ?: return null
        val title = displayName(uri)
        val cover = extractCoverUri(uri, type)?.toString()
        return MediaItem(
            title = title,
            type = type,
            uri = uri.toString(),
            coverUri = cover,
        )
    }

    private fun extractCoverUri(uri: Uri, type: MediaType): Uri? = when (type) {
        MediaType.ARCHIVE -> extractFirstImageFromZip(uri)
        MediaType.IMAGE -> uri
        MediaType.EPUB -> extractEpubCover(uri)
        else -> null
    }

    private fun extractFirstImageFromZip(uri: Uri): Uri? = runCatching {
        val coversDir = File(context.cacheDir, "covers").apply { mkdirs() }
        val out = File(coversDir, "${uri.toString().hashCode()}_cover.jpg")
        if (out.exists()) return@runCatching Uri.fromFile(out)
        context.contentResolver.openInputStream(uri)?.use { input ->
            java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    if (!entry.isDirectory && (n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                            n.endsWith(".png") || n.endsWith(".webp"))
                    ) {
                        out.writeBytes(zip.readBytes())
                        return@use Uri.fromFile(out)
                    }
                    entry = zip.nextEntry
                }
                null
            }
        }
    }.getOrNull()

    private fun extractEpubCover(uri: Uri): Uri? = runCatching {
        com.omnireader.app.core.epub.EpubArchive.read(uri, context.contentResolver).cover?.let { bytes ->
            val coversDir = File(context.cacheDir, "covers").apply { mkdirs() }
            val out = File(coversDir, "${uri.toString().hashCode()}_epub.jpg")
            out.writeBytes(bytes)
            Uri.fromFile(out)
        }
    }.getOrNull()
}
