package com.omnireader.app.ui.document

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.omnireader.app.core.epub.EpubArchive
import com.omnireader.app.core.epub.EpubBook
import com.omnireader.app.core.epub.EpubTocEntry
import com.omnireader.app.data.Bookmark
import com.omnireader.app.data.BookmarkDao
import com.omnireader.app.data.MediaDao
import com.omnireader.app.data.ReadingPosition
import com.omnireader.app.data.ReadingPositionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EpubUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val book: EpubBook? = null,
    val spineIndex: Int = 0,
    val pageIndex: Int = 0,
    val chapterPageCount: Int = 1,
    val scrollY: Int = 0,
    val paginated: Boolean = true,
    val fontSizePx: Int = 18,
    val lineHeight: Float = 1.5f,
    val theme: ReadTheme = ReadTheme.AUTO,
) {
    val progressPercent: Int
        get() {
            val b = book ?: return 0
            if (b.spine.isEmpty()) return 0
            val perChapter = 100f / b.spine.size
            val within = if (chapterPageCount > 0) pageIndex.toFloat() / chapterPageCount else 0f
            return ((spineIndex + within) * perChapter).toInt().coerceIn(0, 100)
        }
}

enum class ReadTheme { LIGHT, DARK, SEPIA, AUTO }

/**
 * Drives the EPUB reading experience: pagination state, position persistence,
 * TOC and bookmarks. All positions are saved to Room on every change (debounced
 * by the fact that page turns are user-paced).
 */
class EpubReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val positionDao: ReadingPositionDao,
    private val bookmarkDao: BookmarkDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // mediaId arrives as a nav argument; token style keeps parity with URL routes.
    private val mediaId: Long = savedStateHandle.get<Long>("mediaId") ?: -1L

    private val _state = MutableStateFlow(EpubUiState())
    val state: StateFlow<EpubUiState> = _state.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> =
        bookmarkDao.observeFor(mediaId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { load() }

    private fun load() = viewModelScope.launch {
        val item = mediaDao.byId(mediaId)
        if (item == null) {
            _state.value = _state.value.copy(loading = false, error = "Media item not found")
            return@launch
        }
        try {
            val uri = android.net.Uri.parse(item.uri)
            val book = EpubArchive.read(uri, context.contentResolver)
            val saved = positionDao.get(mediaId)
            _state.value = _state.value.copy(
                loading = false,
                error = null,
                book = book,
                spineIndex = (saved?.spineIndex ?: 0).coerceIn(0, (book.spine.size - 1).coerceAtLeast(0)),
                pageIndex = saved?.pageIndex ?: 0,
                scrollY = saved?.scrollY ?: 0,
            )
            mediaDao.touchLastRead(mediaId)
        } catch (t: Throwable) {
            _state.value = _state.value.copy(loading = false, error = t.message ?: "Failed to open book")
        }
    }

    /** Called by the WebView bridge once JS pagination finished for the current chapter. */
    fun onChapterPaginated(totalPages: Int) {
        _state.update {
            it.copy(
                chapterPageCount = totalPages.coerceAtLeast(1),
                pageIndex = it.pageIndex.coerceIn(0, totalPages - 1),
            )
        }
        persist()
    }

    fun onPageChange(index: Int) {
        _state.update { it.copy(pageIndex = index.coerceIn(0, (it.chapterPageCount - 1).coerceAtLeast(0))) }
        persist()
    }

    fun nextPage() = _state.update { it.copy(pageIndex = (it.pageIndex + 1)) }
    fun prevPage() = _state.update { it.copy(pageIndex = (it.pageIndex - 1).coerceAtLeast(0)) }

    fun nextChapter() = goToSpine(_state.value.spineIndex + 1)
    fun prevChapter() = goToSpine(_state.value.spineIndex - 1)

    fun goToSpine(index: Int, page: Int = 0) = _state.update { s ->
        val b = s.book ?: return@update s
        val clamped = index.coerceIn(0, (b.spine.size - 1).coerceAtLeast(0))
        s.copy(spineIndex = clamped, pageIndex = page, chapterPageCount = 1)
    }.also { persist() }

    fun onScroll(y: Int) {
        _state.update { it.copy(scrollY = y) }
        persist()
    }

    fun setPaginated(value: Boolean) {
        _state.update { it.copy(paginated = value) }
        persist()
    }

    fun setFontSize(px: Int) { _state.update { it.copy(fontSizePx = px.coerceIn(12, 40)) } }
    fun setLineHeight(v: Float) { _state.update { it.copy(lineHeight = v.coerceIn(1.1f, 2.2f)) } }
    fun setTheme(t: ReadTheme) { _state.update { it.copy(theme = t) } }

    // ---- Bookmarks ----------------------------------------------------------

    fun toggleBookmarkAtCurrentPage(labelHint: String) = viewModelScope.launch {
        val s = _state.value
        if (bookmarkDao.existsAt(mediaId, s.spineIndex, s.pageIndex)) {
            bookmarkDao.removeAt(mediaId, s.spineIndex, s.pageIndex)
        } else {
            val tocTitle = s.book?.toc?.firstOrNull { it.spineIndex == s.spineIndex }?.title
            bookmarkDao.insert(
                Bookmark(
                    mediaId = mediaId,
                    spineIndex = s.spineIndex,
                    pageIndex = s.pageIndex,
                    label = listOfNotNull(tocTitle, labelHint.takeIf { it.isNotBlank() })
                        .joinToString(" · ")
                        .ifBlank { "Page ${s.pageIndex + 1}" },
                )
            )
        }
    }

    fun jumpTo(bookmark: Bookmark) = goToSpine(bookmark.spineIndex, bookmark.pageIndex)

    fun deleteBookmark(bookmark: Bookmark) = viewModelScope.launch { bookmarkDao.delete(bookmark) }

    // ---- Position persistence ----------------------------------------------

    private fun persist() = viewModelScope.launch {
        val s = _state.value
        positionDao.save(
            ReadingPosition(
                mediaId = mediaId,
                spineIndex = s.spineIndex,
                pageIndex = s.pageIndex,
                scrollY = s.scrollY,
            )
        )
    }

    private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) { value = block(value) }
}
