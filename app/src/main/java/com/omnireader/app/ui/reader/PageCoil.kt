package com.omnireader.app.ui.reader

import android.content.Context
import coil.map.Mapper
import coil.request.Options
import java.io.InputStream

/**
 * Coil model mapper: [PageKey] -> InputStream, so `AsyncImage(model = PageKey(...))`
 * just works for archive/folder/PDF pages. The provider is resolved through the
 * app-scoped [com.omnireader.app.di.ReaderModule] registry.
 */
class PageKeyMapper(
    private val context: Context,
) : Mapper<PageKey, InputStream> {
    override suspend fun map(data: PageKey, options: Options): InputStream? =
        PageProviders.get(data.providerId)?.pageStream(data.index)
}

/** Process-wide, thread-safe holder of active providers (one per open book). */
object PageProviders {
    private val providers = java.util.concurrent.ConcurrentHashMap<String, com.omnireader.app.core.reader.PageProvider>()

    fun register(provider: com.omnireader.app.core.reader.PageProvider): String {
        providers[provider.id] = provider
        return provider.id
    }

    fun get(id: String): com.omnireader.app.core.reader.PageProvider? = providers[id]

    fun release(id: String) {
        providers.remove(id)?.let { runCatching { it.close() } }
    }
}
