package com.omnireader.app.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnireader.app.core.catalog.CatalogOrigin
import com.omnireader.app.core.catalog.UnifiedCatalogRepository
import com.omnireader.app.core.catalog.UnifiedMedia
import com.omnireader.app.ui.common.CoverImage
import com.omnireader.app.ui.common.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val catalog: UnifiedCatalogRepository,
) : ViewModel() {

    /** Latest merged view of local library + remote popular listings. */
    val loaded = MutableStateFlow<List<UnifiedMedia>>(emptyList())

    val loading = MutableStateFlow(true)

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        loading.value = true
        loaded.value = catalog.load()
        loading.value = false
    }
}

/**
 * The Unified Catalog: local library merged with remote source listings, deduped
 * so a downloaded/imported book always wins over its remote twin.
 */
@Composable
fun CatalogScreen(
    onOpenLocal: (Long) -> Unit,
    onOpenRemote: (repoUrl: String, sourceId: String, urlToken: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: CatalogViewModel = hiltViewModel(),
) {
    val items by vm.loaded.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    Scaffold(modifier = modifier) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Filter your shelf…") },
            )
            val visible = if (query.isBlank()) items else items.filter {
                it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
            }
            if (visible.isEmpty()) {
                EmptyState(
                    title = "Nothing here yet",
                    subtitle = "Import files in Library or add repositories in Extensions —\nthen everything shows up in this unified view.",
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = { it.key }) { media ->
                        Column(Modifier.fillMaxWidth().clickable {
                            when (media.origin) {
                                CatalogOrigin.LOCAL -> media.localId?.let(onOpenLocal)
                                CatalogOrigin.REMOTE -> {
                                    val repo = media.repoUrl ?: return@clickable
                                    val src = media.sourceId ?: return@clickable
                                    val url = media.remoteUrl ?: return@clickable
                                    onOpenRemote(repo, src, com.omnireader.app.core.source.Tokens.encode(url))
                                }
                            }
                        }) {
                            CoverImage(
                                url = media.coverUrl,
                                contentDescription = media.title,
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                            )
                            Text(
                                media.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Row {
                                Text(
                                    text = if (media.origin == CatalogOrigin.LOCAL) "Local" else media.sourceName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
