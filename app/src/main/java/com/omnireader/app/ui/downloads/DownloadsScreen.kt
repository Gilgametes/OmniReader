package com.omnireader.app.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.omnireader.app.core.downloads.DownloadWorker
import com.omnireader.app.data.DownloadDao
import com.omnireader.app.data.DownloadEntity
import com.omnireader.app.data.DownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.runtime.State

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadDao: DownloadDao,
    private val workManager: WorkManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    val active: StateFlow<List<DownloadEntity>> = downloadDao.observeAll()
        .map { list -> list.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<DownloadEntity>> = downloadDao.observeAll()
        .map { list -> list.filter { it.state != DownloadState.DOWNLOADING && it.state != DownloadState.QUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cancel(entity: DownloadEntity) {
        DownloadWorker.cancel(workManager, entity.chapterUrl)
    }

    /** Re-queue a paused/failed chapter from scratch (files already on disk are skipped by the worker). */
    fun retry(entity: DownloadEntity) = viewModelScope.launch {
        downloadDao.setState(entity.chapterUrl, DownloadState.QUEUED)
    }

    fun delete(entity: DownloadEntity) = viewModelScope.launch {
        DownloadWorker.purge(appContext, downloadDao, entity)
    }

    fun clearCompleted() = viewModelScope.launch {
        downloadDao.deleteByState(DownloadState.COMPLETE)
    }
}

/** Global Download Manager: live queue + finished/failed history with storage controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    modifier: Modifier = Modifier,
    vm: DownloadsViewModel = hiltViewModel(),
) {
    val active by vm.active.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    FilterChip(selected = showHistory, onClick = { showHistory = !showHistory },
                        label = { Text("History") })
                },
            )
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            if (active.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No active downloads.\nStart one from a source's chapter list.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(active, key = { it.chapterUrl }) { e ->
                        ListItem(
                            headlineContent = { Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    Text(e.mediaTitle, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    LinearProgressIndicator(
                                        progress = { if (e.totalPages > 0) e.progress.toFloat() / e.totalPages else 0f },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    )
                                    Text("${e.progress}/${e.totalPages} pages · ${e.bytesDownloaded / 1024} KB",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { vm.cancel(e) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                                }
                            },
                        )
                    }
                }
            }

            if (showHistory) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(onClick = vm::clearCompleted) { Text("Clear completed") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(history, key = { it.chapterUrl }) { e ->
                        ListItem(
                            headlineContent = { Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    when (e.state) {
                                        DownloadState.COMPLETE -> "Completed · ${e.totalPages} pages"
                                        DownloadState.ERROR -> "Failed: ${e.error ?: "unknown"}"
                                        DownloadState.PAUSED -> "Paused at ${e.progress}/${e.totalPages}"
                                        else -> e.state.lowercase()
                                    },
                                    color = if (e.state == DownloadState.ERROR) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                    if (e.state == DownloadState.ERROR || e.state == DownloadState.PAUSED) {
                                        IconButton(onClick = { vm.retry(e) }) {
                                            Icon(Icons.Default.Close, contentDescription = "Retry")
                                        }
                                    }
                                    IconButton(onClick = { vm.delete(e) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete files")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
