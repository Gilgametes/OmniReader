package com.omnireader.app.ui.source

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.omnireader.app.core.downloads.DownloadWorker
import com.omnireader.app.core.source.ListKind
import com.omnireader.app.core.source.ListEntry
import com.omnireader.app.core.source.RepositoryManager
import com.omnireader.app.core.source.Source
import com.omnireader.app.core.source.SourceFetcher
import com.omnireader.app.core.source.Tokens
import com.omnireader.app.data.DownloadDao
import com.omnireader.app.data.DownloadEntity
import com.omnireader.app.data.DownloadState
import com.omnireader.app.data.MediaDao
import com.omnireader.app.data.MediaItem
import com.omnireader.app.data.MediaType
import com.omnireader.app.data.SourceDao
import com.omnireader.app.ui.common.CoverImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceBrowseState(
    val loading: Boolean = true,
    val error: String? = null,
    val sourceName: String = "",
    val entries: List<ListEntry> = emptyList(),
)

@HiltViewModel
class SourceBrowseViewModel @Inject constructor(
    private val sourceDao: SourceDao,
    private val manager: RepositoryManager,
    private val fetcher: SourceFetcher,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val repoUrl: String = Tokens.decode(savedStateHandle.get<String>("repoToken") ?: "")
    private val sourceId: String = Tokens.decode(savedStateHandle.get<String>("sourceIdToken") ?: "")

    private val _state = MutableStateFlow(SourceBrowseState())
    val state: StateFlow<SourceBrowseState> = _state.asStateFlow()

    private var source: Source? = null

    init { load(ListKind.POPULAR, null) }

    fun load(kind: ListKind, query: String?) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        try {
            val entity = sourceDao.one(repoUrl, sourceId)
                ?: throw IllegalStateException("Source not found — sync the repository first")
            val s = manager.decode(entity) ?: throw IllegalStateException("Corrupt source definition")
            source = s
            val entries = fetcher.fetchList(s, kind, query)
            _state.value = SourceBrowseState(
                loading = false,
                sourceName = s.name,
                entries = entries,
            )
        } catch (t: Throwable) {
            _state.value = _state.value.copy(loading = false, error = t.message ?: "Fetch failed")
        }
    }

    fun openDetails(entry: ListEntry): String = Tokens.encode(entry.url)

    companion object {
        val KINDS = listOf(ListKind.POPULAR, ListKind.LATEST, ListKind.SEARCH)
    }
}

/** Popular/Latest/Search browsing for a single extension source. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(
    onBack: () -> Unit,
    onOpenDetails: (repoUrl: String, sourceId: String, detailsToken: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: SourceBrowseViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf(ListKind.POPULAR) }
    var query by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.sourceName.ifBlank { "Source" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
            )
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = kind == ListKind.POPULAR, onClick = {
                    kind = ListKind.POPULAR; vm.load(kind, null)
                }, label = { Text("Popular") })
                FilterChip(selected = kind == ListKind.LATEST, onClick = {
                    kind = ListKind.LATEST; vm.load(kind, null)
                }, label = { Text("Latest") })
            }
            if (kind == ListKind.SEARCH || query.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
                FilterChip(selected = false, onClick = { vm.load(ListKind.SEARCH, query) },
                    label = { Text("Search remote") }, modifier = Modifier.padding(start = 12.dp))
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.entries, key = { it.url }) { entry ->
                        Column(Modifier.fillMaxWidth().clickable {
                            // repo/source tokens are re-derived in details screen from the same nav args
                            onOpenDetails("", "", vm.openDetails(entry), entry.title)
                        }) {
                            CoverImage(entry.cover, entry.title, Modifier.fillMaxWidth().aspectRatio(0.7f))
                            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Details + chapters + inline download chips
// ---------------------------------------------------------------------------

data class ChapterRow(
    val title: String,
    val url: String,
    val download: DownloadEntity?,
)

@HiltViewModel
class SourceDetailsViewModel @Inject constructor(
    private val sourceDao: SourceDao,
    private val mediaDao: MediaDao,
    private val downloadDao: DownloadDao,
    private val manager: RepositoryManager,
    private val fetcher: SourceFetcher,
    private val workManager: WorkManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val detailsUrl: String = Tokens.decode(savedStateHandle.get<String>("detailsToken") ?: "")
    private val passedTitle: String = Tokens.decode(savedStateHandle.get<String>("titleToken") ?: "")

    /** Key of the local library row mirroring this remote entry (its URL is used as mediaUrl). */
    private val mediaUrlKey: String get() = detailsUrl

    private val _chapters = MutableStateFlow<List<ChapterRow>>(emptyList())
    val chapters: StateFlow<List<ChapterRow>> = _chapters.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Live download rows for this book, keyed by chapter URL. */
    val downloads: StateFlow<Map<String, DownloadEntity>> =
        downloadDao.observeForMedia(mediaUrlKey)
            .map { list -> list.associateBy { it.chapterUrl } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init { load() }

    private fun load() = viewModelScope.launch {
        _loading.value = true
        _error.value = null
        try {
            val srcEntity = findSourceFor(detailsUrl)
                ?: throw IllegalStateException("Cannot resolve which source this page belongs to")
            val source = manager.decode(srcEntity) ?: throw IllegalStateException("Corrupt source")
            val cs = fetcher.fetchChapters(source, detailsUrl)
            _chapters.value = cs.map { ChapterRow(it.title, it.url, null) }
        } catch (t: Throwable) {
            _error.value = t.message ?: "Failed to load chapters"
        } finally {
            _loading.value = false
        }
    }

    private suspend fun findSourceFor(url: String): com.omnireader.app.data.SourceEntity? =
        sourceDao.enabledOnce().firstOrNull { url.startsWith(it.baseUrl) }

    fun queueChapterDownload(chapter: ChapterRow) = viewModelScope.launch {
        val srcEntity = findSourceFor(chapter.url) ?: return@launch
        val source = manager.decode(srcEntity) ?: return@launch
        runCatching {
            val pages = fetcher.fetchPages(source, chapter.url)
            DownloadWorker.enqueue(
                workManager = workManager,
                chapterUrl = chapter.url,
                mediaUrl = mediaUrlKey,
                title = chapter.title,
                mediaTitle = passedTitle.ifBlank { source.name },
                pages = pages,
            )
        }.onFailure { _error.value = it.message }
    }

    fun cancelDownload(chapterUrl: String) {
        DownloadWorker.cancel(workManager, chapterUrl)
    }

    fun deleteDownload(chapterUrl: String) = viewModelScope.launch {
        downloadDao.byChapter(chapterUrl)?.let {
            DownloadWorker.purge(appContext, downloadDao, it)
        }
    }

    /** Import the whole remote book into the library as a SOURCE item (reads via downloaded files later). */
    fun importToLibrary() = viewModelScope.launch {
        if (mediaDao.byUri(mediaUrlKey) != null) return@launch
        mediaDao.insert(
            MediaItem(
                title = passedTitle.ifBlank { "Untitled" },
                type = MediaType.SOURCE,
                uri = mediaUrlKey,
                sourceUrl = detailsUrl,
            )
        )
    }

}

/** Details screen with per-chapter inline download status chips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDetailsScreen(
    onBack: () -> Unit,
    onOpenReaderFromDownloads: (mediaUrl: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: SourceDetailsViewModel = hiltViewModel(),
) {
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val dlMap by vm.downloads.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Chapters") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
            )
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            if (loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            error?.let {
                Text(it, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(chapters, key = { it.url }) { ch ->
                    val dl = dlMap[ch.url]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ch.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        InlineDownloadChip(
                            state = dl?.state,
                            progress = dl?.progress ?: 0,
                            total = dl?.totalPages ?: 0,
                            onStart = { vm.queueChapterDownload(ch) },
                            onCancel = { vm.cancelDownload(ch.url) },
                            onDelete = { vm.deleteDownload(ch.url) },
                        )
                    }
                }
            }
        }
    }
}

/** Compact chip: idle → ⬇, queued/downloading → live progress, error → retry, complete → ✓/delete. */
@Composable
fun InlineDownloadChip(
    state: String?,
    progress: Int,
    total: Int,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    when (state) {
        null, DownloadState.PAUSED -> IconButton(onClick = onStart) {
            Icon(Icons.Default.Download, contentDescription = "Download chapter")
        }
        DownloadState.QUEUED, DownloadState.DOWNLOADING -> Column(horizontalAlignment = Alignment.End) {
            LinearProgressIndicator(
                progress = { if (total > 0) progress.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(0.35f),
            )
            Text("$progress/$total", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        DownloadState.ERROR -> FilterChip(selected = true, onClick = onStart,
            label = { Text("Retry") }, trailingIcon = {
                Icon(Icons.Default.Download, null, modifier = Modifier.clickable(onClick = onDelete))
            })
        DownloadState.COMPLETE -> FilterChip(selected = true, onClick = onDelete,
            label = { Text("✓ $total") })
        else -> {}
    }
}
