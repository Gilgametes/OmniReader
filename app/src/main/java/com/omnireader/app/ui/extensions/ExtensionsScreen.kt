package com.omnireader.app.ui.extensions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.omnireader.app.core.source.RepositoryManager
import com.omnireader.app.data.RepositoryDao
import com.omnireader.app.data.RepositoryEntity
import com.omnireader.app.data.SourceDao
import com.omnireader.app.data.SourceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExtensionsUiState(
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    private val repoDao: RepositoryDao,
    private val sourceDao: SourceDao,
    private val manager: RepositoryManager,
) : ViewModel() {

    val repositories: StateFlow<List<RepositoryEntity>> =
        repoDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sources: StateFlow<List<SourceEntity>> =
        sourceDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = mutableStateOf(ExtensionsUiState())
    val state get() = _state.value

    fun addRepo(url: String) = viewModelScope.launch {
        _state.value = ExtensionsUiState(busy = true)
        manager.addRepository(url).fold(
            onSuccess = { n -> _state.value = ExtensionsUiState(message = "Added $n sources") },
            onFailure = { t -> _state.value = ExtensionsUiState(message = "Failed: ${t.message}") },
        )
    }

    fun syncAll() = viewModelScope.launch {
        _state.value = ExtensionsUiState(busy = true)
        manager.syncAll()
        _state.value = ExtensionsUiState(message = "Repositories synced")
    }

    fun removeRepo(url: String) = viewModelScope.launch {
        manager.removeRepository(url)
    }

    fun setRepoEnabled(url: String, enabled: Boolean) = viewModelScope.launch {
        repoDao.setEnabled(url, enabled)
    }

    fun setSourceEnabled(repoUrl: String, sourceId: String, enabled: Boolean) = viewModelScope.launch {
        sourceDao.setEnabled(repoUrl, sourceId, enabled)
    }
}

/** Browse/manage extension repositories and the sources they provide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    onOpenSource: (repoUrl: String, sourceId: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: ExtensionsViewModel = hiltViewModel(),
) {
    val repos by vm.repositories.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var onlyEnabled by remember { mutableStateOf(false) }

    Scaffold(modifier = modifier) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Extensions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilterChip(selected = onlyEnabled, onClick = { onlyEnabled = !onlyEnabled },
                    label = { Text("Enabled only") })
                IconButton(onClick = vm::syncAll) { Icon(Icons.Default.Sync, contentDescription = "Sync all") }
                IconButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add repository") }
            }

            if (vm.state.busy) Text("Working…", Modifier.padding(12.dp))
            vm.state.message?.let {
                Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.secondary)
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(repos, key = { it.url }) { repo ->
                    RepoCard(
                        repo = repo,
                        sources = sources.filter { it.repoUrl == repo.url }
                            .let { list -> if (onlyEnabled) list.filter { it.enabled } else list },
                        onToggleRepo = { vm.setRepoEnabled(repo.url, !repo.enabled) },
                        onRemove = { vm.removeRepo(repo.url) },
                        onToggleSource = { src -> vm.setSourceEnabled(repo.url, src.sourceId, !src.enabled) },
                        onOpenSource = { src -> onOpenSource(repo.url, src.sourceId) },
                    )
                }
                if (repos.isEmpty()) {
                    item {
                        Text(
                            "No repositories yet.\nTap + to add a repo JSON URL.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add repository") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("https://…/repo.json") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (url.isNotBlank()) { vm.addRepo(url.trim()); showAddDialog = false }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RepoCard(
    repo: RepositoryEntity,
    sources: List<SourceEntity>,
    onToggleRepo: () -> Unit,
    onRemove: () -> Unit,
    onToggleSource: (SourceEntity) -> Unit,
    onOpenSource: (SourceEntity) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(repo.name.ifBlank { repo.url }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                Text("${sources.size} sources", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = repo.enabled, onCheckedChange = { onToggleRepo() })
            IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove repo") }
        }
        sources.forEach { src ->
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${src.name}  ·  ${src.lang}",
                    modifier = Modifier.weight(1f).clickable(enabled = src.enabled) { onOpenSource(src) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = src.enabled, onCheckedChange = { onToggleSource(src) })
            }
        }
    }
}
