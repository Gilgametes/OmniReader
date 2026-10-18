package com.omnireader.app.core.source

import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds/removes/syncs extension repositories. A repository is a JSON manifest:
 * { "name": "...", "sources": [ {Source}, ... ] }
 */
@Singleton
class RepositoryManager @Inject constructor(
    private val http: com.omnireader.app.core.HttpFetcher,
    private val repoDao: com.omnireader.app.data.RepositoryDao,
    private val sourceDao: com.omnireader.app.data.SourceDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun addRepository(url: String): Result<Int> = runCatching {
        val manifest = json.decodeFromString<RepoManifest>(http.getString(url))
        repoDao.upsert(RepositoryEntity(url, manifest.name, enabled = true, lastSync = System.currentTimeMillis()))
        manifest.sources.forEach { s ->
            sourceDao.upsert(
                SourceEntity(
                    repoUrl = url,
                    sourceId = s.id,
                    name = s.name,
                    baseUrl = s.baseUrl,
                    lang = s.lang,
                    enabled = true,
                    sourceJson = json.encodeToString(Source.serializer(), s),
                )
            )
        }
        manifest.sources.size
    }

    suspend fun syncAll() {
        repoDao.enabledOnce().forEach { repo ->
            runCatching { addRepository(repo.url) }
            repoDao.touchSync(repo.url)
        }
    }

    suspend fun removeRepository(url: String) {
        sourceDao.deleteByRepo(url)
        repoDao.delete(RepositoryEntity(url, "", false))
    }

    /** Decode stored sources back into engine models. */
    suspend fun enabledSources(): List<Pair<String, Source>> =
        sourceDao.enabledOnce().mapNotNull { e ->
            runCatching { json.decodeFromString<Source>(e.sourceJson) to e.repoUrl }.getOrNull()
        }.map { (s, repo) -> repo to s }

    fun decode(entity: SourceEntity): Source? =
        runCatching { json.decodeFromString<Source>(entity.sourceJson) }.getOrNull()
}

private typealias RepositoryEntity = com.omnireader.app.data.RepositoryEntity
private typealias SourceEntity = com.omnireader.app.data.SourceEntity
