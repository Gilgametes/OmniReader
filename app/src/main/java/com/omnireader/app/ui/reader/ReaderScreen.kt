package com.omnireader.app.ui.reader

import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.omnireader.app.core.reader.PageKey
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Image pager supporting LTR/RTL page swipes and a continuous Webtoon strip,
 * pinch-free zoom slider + brightness overlay, keep-screen-on, immersive chrome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ReaderViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Keep screen on while reading.
    DisposableEffect(state.keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (state.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var chromeVisible by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var brightness by remember { mutableFloatStateOf(-1f) } // -1 = system

    val providerId = remember(state.title, state.pageCount) {
        vm.pageProvider()?.let { PageProviders.register(it) }
    }
    DisposableEffect(providerId) {
        onDispose { providerId?.let { PageProviders.release(it) } }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (chromeVisible) {
                TopAppBar(
                    title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(Icons.Default.FormatSize, contentDescription = "Reader settings")
                        }
                    },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                )
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).background(Color.Black)) {
            if (showSettings) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReadingDirection.entries.forEach { d ->
                        FilterChip(
                            selected = state.direction == d,
                            onClick = { vm.setDirection(d) },
                            label = { Text(d.name) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Zoom", style = MaterialTheme.typography.labelMedium)
                    Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..3f, modifier = Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Brightness", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = if (brightness < 0f) 1f else brightness,
                        onValueChange = { b ->
                            brightness = b
                            val w = (context as? android.app.Activity)?.window
                            if (w != null) {
                                val lp = w.attributes
                                lp.alpha = if (b >= 0.99f) 1f else 0.2f + 0.8f * b
                                w.attributes = lp
                            }
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Loading…", color = Color.White)
                }
                state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(state.error ?: "", color = Color.White)
                }
                state.direction == ReadingDirection.WEBTOON -> WebtoonStrip(
                    providerId = providerId,
                    pageCount = state.pageCount,
                    zoom = zoom,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                )
                else -> PagedReader(
                    providerId = providerId,
                    pageCount = state.pageCount,
                    startPage = state.currentPage,
                    rtl = state.direction == ReadingDirection.RTL,
                    zoom = zoom,
                    onPage = vm::setPage,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                )
            }
        }
    }
}

@Composable
private fun PagedReader(
    providerId: String?,
    pageCount: Int,
    startPage: Int,
    rtl: Boolean,
    zoom: Float,
    onPage: (Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    if (providerId == null || pageCount == 0) return
    val pagerState = rememberPagerState(
        initialPage = if (rtl) pageCount - 1 - startPage.coerceIn(0, pageCount - 1) else startPage.coerceIn(0, pageCount - 1),
        pageCount = { pageCount },
    )
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { page ->
            onPage(if (rtl) pageCount - 1 - page else page)
        }
    }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            reverseLayout = rtl,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleChrome() })
            },
        ) { index ->
            val logicalIndex = if (rtl) pageCount - 1 - index else index
            PageImage(providerId = providerId, index = logicalIndex, zoom = zoom)
        }
        Text(
            text = "${pagerState.currentPage + 1} / $pageCount",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        )
    }
}

@Composable
private fun WebtoonStrip(
    providerId: String?,
    pageCount: Int,
    zoom: Float,
    onToggleChrome: () -> Unit,
) {
    if (providerId == null) return
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .pointerInput(Unit) { detectTapGestures(onTap = { onToggleChrome() }) },
    ) {
        repeat(pageCount) { i ->
            PageImage(providerId = providerId, index = i, zoom = zoom, fillWidth = true)
        }
    }
}

@Composable
private fun PageImage(providerId: String, index: Int, zoom: Float, fillWidth: Boolean = false) {
    AsyncImage(
        model = PageKey(providerId, index),
        contentDescription = "Page ${index + 1}",
        contentScale = if (fillWidth) ContentScale.FillWidth else ContentScale.Fit,
        modifier = Modifier
            .let { if (fillWidth) it.fillMaxWidth() else it.fillMaxSize() }
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                transformationMatrix.translate(-size.width * (zoom - 1f) / 2f, 0f)
            },
    )
}
