package com.omnireader.app.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin OkHttp wrapper. Single shared client with sane timeouts and a browser-ish UA,
 * since extension sources scrape public websites.
 */
@Singleton
class HttpFetcher @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        execute(Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()) { body -> body.string() }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        execute(Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()) { body -> body.bytes() }

    /** Streams a response body; the block runs with the body open. Returns block result. */
    suspend fun <T> execute(request: Request, block: (okhttp3.ResponseBody) -> T): T {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${request.url}")
            val body = response.body ?: throw IOException("Empty body for ${request.url}")
            return block(body)
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 OmniReader/0.1"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(req)
            }
            .build()
    }
}
