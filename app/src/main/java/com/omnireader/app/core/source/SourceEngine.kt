package com.omnireader.app.core.source

import kotlinx.serialization.Serializable
import java.util.Base64
import java.net.URLEncoder

@Serializable data class ListSelector(
    val item: String,
    val title: String,
    val url: String? = null,
    val cover: String? = null,
)

@Serializable data class DetailsSelector(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    val cover: String? = null,
)

@Serializable data class ChapterSelector(val item: String, val title: String, val url: String)

@Serializable data class PageSelector(val image: String, val script: String? = null)

/** Extension source definition shipped inside repository JSON manifests. */
@Serializable
data class Source(
    val id: String,
    val name: String,
    val baseUrl: String,
    val lang: String = "en",
    val popularUrl: String? = null,
    val latestUrl: String? = null,
    val searchUrl: String? = null, // contains $query placeholder
    val list: ListSelector,
    val details: DetailsSelector? = null,
    val chapters: ChapterSelector? = null,
    val pages: PageSelector? = null,
)

@Serializable
data class RepoManifest(
    val name: String,
    val sources: List<Source>,
)

/**
 * Navigation tokens. Complex URLs (with query strings, #fragments, unicode) MUST be
 * passed through Nav args Base64-URL encoded — plain Uri encoding corrupts them.
 */
object Tokens {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(value: String): String = encoder.encodeToString(value.toByteArray())
    fun decode(token: String): String = String(decoder.decode(token))

    fun encodeSource(url: String): String = encode(url)
    fun decodeSource(token: String): String = decode(token)
}

/** Jsoup-driven fetcher that maps a [Source] selector onto parsed entries. */
data class ListEntry(val title: String, val url: String, val cover: String?)
data class ChapterEntry(val title: String, val url: String)

class SourceFetcher(
    private val http: com.omnireader.app.core.HttpFetcher,
) {
    suspend fun fetchList(source: Source, kind: ListKind, query: String? = null): List<ListEntry> {
        val url = when (kind) {
            ListKind.POPULAR -> source.popularUrl?.let { source.baseUrl + it }
            ListKind.LATEST -> source.latestUrl?.let { source.baseUrl + it }
            ListKind.SEARCH -> source.searchUrl
                ?.replace("\$query", URLEncoder.encode(query ?: "", "UTF-8"))
                ?.let { source.baseUrl + it }
        } ?: return emptyList()
        val html = http.getString(url)
        return parseList(source, html)
    }

    private fun parseList(source: Source, html: String): List<ListEntry> {
        val doc = org.jsoup.Jsoup.parse(html)
        val sel = source.list
        return doc.select(sel.item).mapNotNull { el ->
            val title = el.selectFirst(sel.title)?.text()?.trim() ?: return@mapNotNull null
            val href = if (sel.url != null) el.selectFirst(sel.url)?.attr("href")
            else el.selectFirst(sel.title)?.attr("href") ?: el.attr("href")
            val cover = sel.cover?.let { el.selectFirst(it)?.imgSrc() }
            val abs = href?.let { absolutize(source.baseUrl, it) } ?: return@mapNotNull null
            ListEntry(title = title, url = abs, cover = cover?.let { absolutize(source.baseUrl, it) })
        }
    }

    suspend fun fetchChapters(source: Source, detailsUrl: String): List<ChapterEntry> {
        val cs = source.chapters ?: return emptyList()
        val html = http.getString(detailsUrl)
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.select(cs.item).mapNotNull { el ->
            val title = el.selectFirst(cs.title)?.text()?.trim() ?: el.text().trim()
            val href = el.selectFirst(cs.url)?.attr("href").takeUnless { it.isNullOrBlank() }
                ?: el.attr("href")
            if (title.isBlank() || href.isBlank()) null
            else ChapterEntry(title, absolutize(source.baseUrl, href))
        }.reversed() // most sites list newest first; reading order is oldest first
    }

    suspend fun fetchPages(source: Source, chapterUrl: String): List<String> {
        val ps = source.pages ?: return emptyList()
        val html = http.getString(chapterUrl)
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.select(ps.image).mapNotNull { it.imgSrc() }
            .filter { it.isNotBlank() }
            .map { absolutize(source.baseUrl, it) }
    }

    private fun org.jsoup.nodes.Element.imgSrc(): String? =
        attr("data-src").ifBlank { attr("data-lazy-src").ifBlank { attr("src").ifBlank { null } } }

    private fun absolutize(base: String, ref: String): String = runCatching {
        java.net.URL(java.net.URI(base).resolve(ref)?.toString() ?: ref).toString()
    }.getOrDefault(if (ref.startsWith("http")) ref else base.trimEnd('/') + "/" + ref.trimStart('/'))

    enum class ListKind { POPULAR, LATEST, SEARCH }
}
