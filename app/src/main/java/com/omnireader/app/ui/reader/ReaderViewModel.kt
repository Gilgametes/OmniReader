package com.omnireader.app.ui.reader

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnireader.app.core.downloads.DownloadWorker
import com.omnireader.app.data.MediaDao
import com.omnireader.app.data.MediaType
import com.omnireader.app.core.reader.PageProvider
import com.omnireader.app.core.reader.ReaderEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ReadingDirection { LTR, RTL, WEBTOON }

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val direction: ReadingDirection = ReadingDirection.LTR,
    val keepScreenOn: Boolean = true,
)

/**
 * Image-sequence reader for CBZ/ZIP folders/PDF pages. Position persists into
 * [com.omnireader.app.data.ReadingPosition] via the mediaId (spineIndex slot).
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    application: Application,
    private val mediaDao: MediaDao,
    private val engine: ReaderEngine,
    private val positionDao: com.omnireader.app.data.ReadingPositionDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val mediaId: Long = savedStateHandle.get<Long>("mediaId") ?: -1L
    private var provider: PageProvider? = null

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    init { open() }

    private fun open() = viewModelScope.launch {
        val item = mediaDao.byId(mediaId)
        if (item == null) {
            _state.value = _state.value.copy(loading = false, error = "Media item not found")
            return@launch
        }
        try {
            val p = engine.providerFor(item.type, item.uri)
                ?: throw IllegalStateException("Unsupported format: ${item.type}")
            provider = p
            val count = withContext(Dispatchers.IO) { p.pageCount() }
            val saved = positionDao.get(mediaId)?.spineIndex ?: 0
            _state.value = _state.value.copy(
                loading = false,
                error = null,
                title = item.title,
                pageCount = count,
                currentPage = saved.coerceIn(0, (count - 1).coerceAtLeast(0)),
                direction = when (item.type) {
                    MediaType.ARCHIVE -> ReadingDirection.RTL // manga default; user can flip
                    else -> ReadingDirection.LTR
                },
            )
            mediaDao.touchLastRead(mediaId)
        } catch (t: Throwable) {
            _state.value = _state.value.copy(loading = false, error = t.message ?: "Failed to open")
        }
    }

    /** Expose the active provider so the pager UI can stream page bytes for Coil. */
    fun pageProvider(): PageProvider? = provider

    fun setPage(index: Int) {
        val s = _state.value
        if (s.pageCount == 0) return
        val clamped = index.coerceIn(0, s.pageCount - 1)
        if (clamped == s.currentPage) return
        _state.value = s.copy(currentPage = clamped)
        persist(clamped)
    }

    fun setDirection(d: ReadingDirection) {
        _state.value = _state.value.copy(direction = d)
    }

    fun toggleKeepScreenOn() {
        _state.value = _state.value.copy(keepScreenOn = !_state.value.keepScreenOn)
    }

    override fun onCleared() {
        provider?.close()
        provider = null
    }

    private fun persist(page: Int) = viewModelScope.launch {
        positionDao.save(
            com.omnireader.app.data.ReadingPosition(
                mediaId = mediaId,
                spineIndex = page,
                pageIndex = 0,
                scrollY = 0,
            )
        )
    }
}
