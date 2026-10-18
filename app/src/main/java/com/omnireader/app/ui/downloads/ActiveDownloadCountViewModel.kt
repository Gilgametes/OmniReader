package com.omnireader.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnireader.app.data.DownloadDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Backs the live badge on the Downloads tab in the bottom navigation bar. */
@HiltViewModel
class ActiveDownloadCountViewModel @Inject constructor(
    downloadDao: DownloadDao,
) : ViewModel() {
    val count: StateFlow<Int> = downloadDao.observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
