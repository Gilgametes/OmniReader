package com.omnireader.app.core.epub

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A resolved, in-memory EPUB: spine order + TOC + lazy resource access. */
@Serializable
data class EpubResource(val href: String, val mediaType: String)

data class EpubTocEntry(
    val title: String,
    val href: String,          // resolved relative to OPF dir
    val level: Int,            // 0 = top level
    val spineIndex: Int,       // index into spine (-1 if unresolved)
    val children: List<EpubTocEntry> = emptyList(),
)

class EpubBook internal constructor(
    val title: String,
    val author: String,
    /** Exposed for library cover extraction. */
    val cover: ByteArray?,
    /** (x)html resources in reading order; each entry is the raw bytes provider by href */
    val spine: List<String>,
    val toc: List<EpubTocEntry>,
    private val resources: Map<String, ByteArray>,
) {
    fun chapterHtml(spineIndex: Int, css: String): String {
        val href = spine[spineIndex]
        val raw = resources[href]?.decodeToString() ?: "<html><body><p>Missing chapter</p></body></html>"
        val doc = Jsoup.parse(raw)
        doc.outputSettings().charset(Charsets.UTF_8)
        // Rewrite local image refs to data URIs so WebView needs no file access at all.
        doc.select("img[src], image[xlink|href], image[href]").forEach { el ->
            val ref = (el.attr("src").ifBlank { el.attr("href") })
            val resolved = resolveHref(href, ref)
            resources[resolved]?.let { bytes ->
                el.attr("src", "data:${mime(resolved)};base64,${java.util.Base64.getEncoder().encodeToString(bytes)}")
            }
        }
        doc.head().append("<style>$css</style>")
        return doc.html()
    }

    companion object {
        private fun mime(href: String): String = when (href.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
            "svg" -> "image/svg+xml"; "webp" -> "image/webp"; else -> "image/jpeg"
        }
    }
}

/**
 * Parses container.xml -> OPF -> spine/toc (both nav.xhtml and toc.ncx supported).
 * Everything runs off the main thread; input is a SAF content URI.
 */
object EpubArchive {
    suspend fun read(uri: Uri, resolver: android.content.ContentResolver): EpubBook =
        withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { input ->
                read(input.buffered())
            } ?: throw IllegalStateException("Cannot open $uri")
        }

    fun read(input: InputStream): EpubBook {
        val resources = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) resources[e.name.trimStart('/')] = zip.readBytes()
                e = zip.nextEntry
            }
        }

        val opfPath = findOpfPath(resources)
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opfDoc = Jsoup.parse(String(resources.getValue(opfPath)))

        val meta = opfDoc.select("metadata")
        val title = meta.selectFirst("dc\\:title")?.text()?.ifBlank { null }
            ?: meta.getElementsByTag("dc:title").firstOrNull()?.text() ?: "Untitled"
        val author = meta.getElementsByTag("dc:creator").firstOrNull()?.text() ?: ""

        val manifest = opfDoc.select("manifest > item").associateBy(
            keySelector = { it.attr("id") },
            valueSelector = { Triple(resolveHref(opfDir, it.attr("href")), it.attr("media-type"), it.attr("properties")) },
        )

        val spine = opfDoc.select("spine > itemref")
            .mapNotNull { manifest[it.attr("idref")] }
            .filter { it.second.contains("html") }
            .map { it.first }

        val coverHref = manifest.values
            .firstOrNull { it.third.contains("cover-image") }?.first
            ?: opfDoc.select("meta[name=cover]").first()?.attr("content")?.let { manifest[it] }?.first
            ?.let { resolveHref(opfDir, it) }
            ?: resources.keys.firstOrNull { it.lowercase().contains("cover") && it.lowercase().endsWith(".jpg") }

        val toc = buildToc(opfDir, manifest, spine, resources)

        EpubBook(
            title = title,
            author = author,
            cover = coverHref?.let { resources[it] },
            spine = spine,
            toc = toc,
            resources = resources,
        )
    }

    private fun findOpfPath(resources: Map<String, ByteArray>): String {
        val container = resources.entries.first { it.key.equals("META-INF/container.xml", true) }
        val doc = Jsoup.parse(String(container.value))
        val rootfile = doc.selectFirst("rootfile")?.attr("full-path")
            ?: throw IllegalStateException("No rootfile in container.xml")
        return rootfile.trimStart('/')
    }

    private fun buildToc(
        opfDir: String,
        manifest: Map<String, Triple<String, String, String>>,
        spine: List<String>,
        resources: Map<String, ByteArray>,
    ): List<EpubTocEntry> {
        val navItem = manifest.values.firstOrNull { it.third.contains("nav") }
        if (navItem != null) resources[navItem.first]?.let { bytes ->
            val toc = parseNavXhtml(String(bytes), navItem.first, opfDir, spine)
            if (toc.isNotEmpty()) return flatten(toc)
        }
        val ncxItem = manifest.values.firstOrNull { it.second == "application/x-dtbncx+xml" }
        if (ncxItem != null) resources[ncxItem.first]?.let { bytes ->
            return flatten(parseNcx(String(bytes), ncxItem.first, spine))
        }
        // Fallback: one TOC entry per spine item.
        return spine.mapIndexed { i, href ->
            EpubTocEntry(title = href.substringAfterLast('/').substringBeforeLast('.'), href = href, level = 0, spineIndex = i)
        }
    }

    /** Depth-first flattening so UI lists can render with indentation from [EpubTocEntry.level]. */
    private fun flatten(entries: List<EpubTocEntry>): List<EpubTocEntry> =
        entries.flatMap { listOf(it.copy(children = emptyList())) + flatten(it.children) }

    private fun parseNavXhtml(html: String, navHref: String, opfDir: String, spine: List<String>): List<EpubTocEntry> {
        val doc = Jsoup.parse(html)
        val navEl = doc.selectFirst("nav[type~=toc]")
            ?: doc.body().select("nav").firstOrNull()
            ?: doc.body()
        val root = navEl.selectFirst("ol") ?: return emptyList()
        return parseOl(root, navHref, opfDir, spine, 0)
    }

    private fun parseOl(
        ol: org.jsoup.nodes.Element,
        navHref: String,
        opfDir: String,
        spine: List<String>,
        level: Int,
    ): List<EpubTocEntry> = ol.children().filter { it.tagName() == "li" }.flatMap { li ->
        val a = li.selectFirst("a") ?: li.selectFirst("span")
        val title = a?.text()?.trim().orEmpty()
        val hrefRaw = a?.attr("href").orEmpty()
        val resolved = resolveHref(navHref.substringBeforeLast('/', opfDir), hrefRaw.substringBefore('#'))
        val children = li.children().firstOrNull { it.tagName() == "ol" }
        val entry = if (title.isNotBlank()) EpubTocEntry(
            title = title,
            href = resolved,
            level = level,
            spineIndex = spine.indexOf(resolved),
        ) else null
        listOfNotNull(entry) + (children?.let { parseOl(it, navHref, opfDir, spine, level + 1) } ?: emptyList())
    }

    private fun parseNcx(xml: String, ncxHref: String, spine: List<String>): List<EpubTocEntry> {
        val doc = Jsoup.parse(xml, "http://www.daisy.org/z3986/2005/ncx/", org.jsoup.parser.Parser.xmlParser())
        val rootMap = doc.selectFirst("navMap") ?: return emptyList()
        fun walk(el: org.jsoup.nodes.Element, level: Int): List<EpubTocEntry> =
            el.children().filter { it.tagName() == "navPoint" }.flatMap { np ->
                val title = np.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
                val src = np.selectFirst("content")?.attr("src").orEmpty().substringBefore('#')
                val resolved = resolveHref(ncxHref.substringBeforeLast('/'), src)
                val entry = if (title.isNotBlank()) EpubTocEntry(
                    title = title, href = resolved, level = level, spineIndex = spine.indexOf(resolved),
                ) else null
                listOfNotNull(entry) + walk(np, level + 1)
            }
        return walk(rootMap, 0)
    }

    /** Resolves an href found inside [fromPath] relative to its own directory. */
    internal fun resolveHref(fromPath: String, ref: String): String {
        if (ref.isBlank()) return fromPath
        val clean = ref.removePrefix("./").removePrefix("/")
        if (ref.startsWith("/")) return clean
        val dir = fromPath.substringBeforeLast('/', "")
        val combined = if (dir.isEmpty()) clean else "$dir/$clean"
        // normalize ../ segments
        val parts = mutableListOf<String>()
        combined.split('/').forEach { p ->
            when (p) {
                ".", "" -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }
}
