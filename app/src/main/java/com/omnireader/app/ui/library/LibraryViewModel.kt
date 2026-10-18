package com.omnireader.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnireader.app.data.MediaDao
import com.omnireader.app.data.MediaItem
import com.omnireader.app.core.Importer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryFilter { ALL, RECENT, FAVORITES }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val mediaDao: MediaDao,
    private val importer: Importer,
    private val catalog: com.omnireader.app.core.catalog.UnifiedCatalogRepository,
) : ViewModel() {

    val items: StateFlow<List<MediaItem>> =
        mediaDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filter = MutableStateFlow(LibraryFilter.ALL)

    fun importUri(uri: android.net.Uri) = viewModelScope.launch {
        val item = importer.toItem(uri) ?: return@launch
        mediaDao.insert(item)
    }

    /** Persist a whole SAF tree picked with OpenDocumentTree. */
    fun importTree(treeUri: android.net.Uri) = viewModelScope.launch {
        catalog.importTree(treeUri)
    }

    fun toggleFavorite(item: MediaItem) = viewModelScope.launch {
        mediaDao.setFavorite(item.id, !item.isFavorite)
    }

    fun remove(item: MediaItem) = viewModelScope.launch { mediaDao.delete(item) }
}
