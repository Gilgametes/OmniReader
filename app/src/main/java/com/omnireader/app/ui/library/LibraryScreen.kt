package com.omnireader.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.OutlineFavorite
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnireader.app.data.MediaItem
import com.omnireader.app.ui.common.CoverImage
import com.omnireader.app.ui.common.EmptyState

@Composable
fun LibraryScreen(
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importTree(uri)
    }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                pickFile.launch(arrayOf(
                    "application/epub+zip", "application/zip", "application/pdf",
                    "text/plain", "image/*", "*/*",
                ))
            }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Import") })
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { vm.filter.value = f },
                        label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
                Box(Modifier.weight(1f))
                FilterChip(
                    selected = false,
                    onClick = { pickFolder.launch(null) },
                    label = { Text("Import folder") },
                )
            }

            val visible = when (filter) {
                LibraryFilter.ALL -> items
                LibraryFilter.RECENT -> items.sortedByDescending { it.lastRead }.takeWhile { it.lastRead > 0 }
                LibraryFilter.FAVORITES -> items.filter { it.isFavorite }
            }

            if (visible.isEmpty()) {
                EmptyState(
                    title = "Your library is empty",
                    subtitle = "Tap Import to add CBZ, PDF, EPUB or TXT files.\nEverything stays on your device.",
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = androidx.compose.foundation.layout.Padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = { it.id }) { item ->
                        LibraryCard(item = item, onOpen = { onOpen(item) }, onFav = { vm.toggleFavorite(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(item: MediaItem, onOpen: () -> Unit, onFav: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Box {
            CoverImage(
                url = item.coverUri ?: item.uri,
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
            )
            Icon(
                imageVector = if (item.isFavorite) Icons.Default.Favorite else Icons.Default.OutlineFavorite,
                contentDescription = "Favorite",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .clickable(onClick = onFav),
            )
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
