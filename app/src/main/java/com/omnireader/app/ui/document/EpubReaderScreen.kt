package com.omnireader.app.ui.document

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnireader.app.core.epub.EpubPaginator
import com.omnireader.app.data.Bookmark
import kotlin.math.abs

private const val MIN_SWIPE_PX = 90f

/**
 * Production EPUB reader: WebView + injected pagination engine.
 *
 * - Paginated mode: JS slices the chapter into viewport-height pages; horizontal
 *   swipe / edge taps turn pages, chapter edges roll over into neighbours.
 * - Scroll mode: continuous reading with restored raw scroll offset.
 * - Nested TOC sheet (nav.xhtml / toc.ncx), bookmark sheet and typography settings,
 *   all persisted through Room via [EpubReaderViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: EpubReaderViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()

    var showToc by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var isBookmarkedHere by remember { mutableStateOf(false) }

    LaunchedEffect(state.spineIndex, state.pageIndex) {
        isBookmarkedHere = vm.isBookmarked(state.spineIndex, state.pageIndex)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AnimatedVisibility(visible = chromeVisible) {
                TopAppBar(
                    title = {
                        Text(
                            text = state.book?.title ?: "Reading…",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showToc = true }) {
                            Icon(Icons.Default.List, contentDescription = "Table of contents")
                        }
                        IconButton(onClick = { showBookmarks = true }) {
                            Icon(Icons.Default.Bookmarks, contentDescription = "Bookmarks")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.FormatSize, contentDescription = "Reading settings")
                        }
                    },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(visible = chromeVisible && !state.loading && state.error == null) {
                Column {
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = vm::swipeBackward, modifier = Modifier.padding(start = 8.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous page")
                        }
                        Text(
                            text = if (state.paginated) {
                                "${state.pageIndex + 1} / ${state.chapterPageCount}"
                            } else {
                                "${state.progressPercent}% · ch ${state.spineIndex + 1}/${state.book?.spine?.size ?: 1}"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        IconButton(
                            onClick = { vm.toggleBookmarkAtCurrentPage(); isBookmarkedHere = !isBookmarkedHere },
                        ) {
                            Icon(
                                Icons.Default.BookmarkAdded,
                                contentDescription = "Bookmark this page",
                                tint = if (isBookmarkedHere) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = vm::swipeForward, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next page")
                        }
                    }
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when {
                state.loading -> CenteredText("Opening book…")
                state.error != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(state.error, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBack) { Text("Go back") }
                }
                else -> EpubWebView(
                    state = state,
                    onPageReady = vm::onChapterPaginated,
                    onScroll = vm::onScroll,
                    onSwipeForward = vm::swipeForward,
                    onSwipeBackward = vm::swipeBackward,
                    onTapCenter = { chromeVisible = !chromeVisible },
                )
            }
        }
    }

    // ---- TOC sheet ----------------------------------------------------------
    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Text(
                "Contents",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn {
                items(state.book?.toc.orEmpty()) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                entry.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = (entry.level * 16).dp),
                            )
                        },
                        supportingContent = entry.takeIf { it.spineIndex >= 0 }?.let {
                            { Text("Chapter ${it.spineIndex + 1}") }
                        },
                        trailingContent = entry.takeIf { it.spineIndex == state.spineIndex }?.let {
                            { Icon(Icons.Default.BookmarkAdded, null) }
                        },
                        modifier = if (entry.spineIndex >= 0) {
                            Modifier.clickable {
                                vm.goToSpine(entry.spineIndex)
                                showToc = false
                            }
                        } else Modifier,
                    )
                }
            }
        }
    }

    // ---- Bookmarks sheet ----------------------------------------------------
    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Bookmarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = isBookmarkedHere,
                    onClick = { vm.toggleBookmarkAtCurrentPage(); isBookmarkedHere = !isBookmarkedHere },
                    label = { Text(if (isBookmarkedHere) "Remove here" else "Bookmark here") },
                )
            }
            if (bookmarks.isEmpty()) {
                Text(
                    "No bookmarks yet. Drop one on the current page to come back later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn {
                    items(bookmarks, key = { it.id }) { bm ->
                        BookmarkRow(bm) {
                            vm.jumpTo(bm)
                            showBookmarks = false
                        }
                        onDelete = { vm.deleteBookmark(bm) }
                    }
                }
            }
        }
    }

    // ---- Typography sheet ---------------------------------------------------
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Reading settings", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Font size", modifier = Modifier.weight(1f))
                    Text("${state.fontSizePx}px")
                }
                Slider(
                    value = state.fontSizePx.toFloat(),
                    onValueChange = { vm.setFontSize(it.toInt()) },
                    valueRange = 12f..40f,
                    steps = 27,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Line height", modifier = Modifier.weight(1f))
                    Text(String.format("%.1f", state.lineHeight))
                }
                Slider(
                    value = state.lineHeight,
                    onValueChange = vm::setLineHeight,
                    valueRange = 1.1f..2.2f,
                    steps = 10,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadTheme.entries.forEach { t ->
                        FilterChip(
                            selected = state.theme == t,
                            onClick = { vm.setTheme(t) },
                            label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.paginated, onClick = { vm.setPaginated(true) },
                        label = { Text("Paginated") })
                    FilterChip(selected = !state.paginated, onClick = { vm.setPaginated(false) },
                        label = { Text("Continuous scroll") })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// WebView host + JS bridge
// ---------------------------------------------------------------------------

private class OmniBridge(
    onPageReady: (Int) -> Unit,
    onScroll: (Int) -> Unit,
) {
    private val ready = java.lang.ref.WeakReference(onPageReady)
    private val scrolled = java.lang.ref.WeakReference(onScroll)

    @JavascriptInterface
    fun onPageReady(total: Int) { ready.get()?.invoke(total) }

    @JavascriptInterface
    fun onScrollProgress(y: Int) { scrolled.get()?.invoke(y) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubWebView(
    state: EpubUiState,
    onPageReady: (Int) -> Unit,
    onScroll: (Int) -> Unit,
    onSwipeForward: () -> Unit,
    onSwipeBackward: () -> Unit,
    onTapCenter: () -> Unit,
) {
    val book = state.book ?: return
    val css = remember(state.fontSizePx, state.lineHeight, state.theme, state.paginated) {
        epubCss(state)
    }
    // Full document rebuilt only when the chapter changes; CSS-only tweaks go through JS.
    val html = remember(state.spineIndex, state.paginated) {
        book.chapterHtml(state.spineIndex, css) +
            EpubPaginator.inject(css = "", mode = if (state.paginated) "paginated" else "scroll")
    }

    val currentForward = rememberUpdatedState(onSwipeForward)
    val currentBackward = rememberUpdatedState(onSwipeBackward)
    val currentCenter = rememberUpdatedState(onTapCenter)

    val bridge = remember(html) { OmniBridge(onPageReady, onScroll) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val latestState = rememberUpdatedState(state)

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                loadUrl("about:blank")
                removeJavascriptInterface("OmniBridge")
                destroy()
            }
            webViewRef = null
        }
    }

    LaunchedEffect(css) {
        webViewRef?.evaluateJavascript(
            "if(window.OmniReader){window.OmniReader.setCss(${escapeForJs(css)});}",
            null,
        )
    }

    Box(Modifier.fillMaxSize().background(Color(readBackgroundColor(state.theme)))) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.paginated) {
                    var accumulated = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(accumulated) > MIN_SWIPE_PX) {
                                if (accumulated < 0) currentForward.value() else currentBackward.value()
                            }
                            accumulated = 0f
                        },
                        onHorizontalDrag = { _, dragAmount -> accumulated += dragAmount },
                    )
                },
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(readBackgroundColor(state.theme))
                    settings.javaScriptEnabled = true // required for the paginator; content is app-generated only
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.mediaPlaybackRequiresUserGesture = true
                    addJavascriptInterface(bridge, "OmniBridge")
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ): Boolean = true // block all navigation — the book is fully inlined already

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            // Restore the saved position once JS pagination has settled.
                            val s = latestState.value
                            view?.postDelayed({
                                if (s.paginated) {
                                    view.evaluateJavascript(
                                        "if(window.OmniReader){window.scrollTo(0, ${s.pageIndex}*window.OmniReader.pageHeight());}",
                                        null,
                                    )
                                } else {
                                    view.evaluateJavascript(
                                        "if(window.OmniReader){window.OmniReader.scrollToY(${s.scrollY});}",
                                        null,
                                    )
                                }
                            }, 250)
                        }
                    }
                }
            },
            update = { wv ->
                webViewRef = wv
                wv.setBackgroundColor(readBackgroundColor(state.theme))
                // loadDataWithBaseURL(null, …): sandboxed, no origin, no remote refs.
                wv.loadDataWithBaseURL(null, html, "text/html; charset=utf-8", "utf-8", null)
            },
        )

        // Invisible tap zones over the WebView: left third = prev, middle = chrome, right = next.
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).clickable { currentBackward.value() })
            Box(Modifier.weight(1.4f).clickable { currentCenter.value() })
            Box(Modifier.weight(1f).clickable { currentForward.value() })
        }
    }
}


private fun epubCss(state: EpubUiState): String {
    val (bg, fg) = when (state.theme) {
        ReadTheme.LIGHT -> "#FFFFFF" to "#151515"
        ReadTheme.DARK -> "#10131C" to "#D8DBE4"
        ReadTheme.SEPIA -> "#F4ECD8" to "#433422"
        ReadTheme.AUTO -> "#FFFFFF" to "#151515"
    }
    val paginatedPad = if (state.paginated) "padding: 12px 16px !important;" else ""
    return """
        html, body { margin: 0 !important; background: $bg !important; color: $fg !important; }
        body {
          font-size: ${state.fontSizePx}px;
          line-height: ${state.lineHeight};
          $paginatedPad
          hyphens: auto;
          word-wrap: break-word;
        }
        p, li, div, span { color: $fg !important; }
        img, svg, image { max-width: 100% !important; height: auto !important; }
        h1,h2,h3,h4 { line-height: 1.25; }
        a { color: inherit; }
        table { max-width: 100%; }
        .omni-page { box-sizing: border-box; padding: 0 2px; }
    """.trimIndent()
}

private fun readBackgroundColor(theme: ReadTheme): Int = when (theme) {
    ReadTheme.DARK -> AndroidColor.parseColor("#10131C")
    ReadTheme.SEPIA -> AndroidColor.parseColor("#F4ECD8")
    else -> AndroidColor.WHITE
}

/** Naive JSON string escaper, good enough for injecting our own generated CSS. */
private fun escapeForJs(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"" 

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BookmarkRow(bm: Bookmark, onJump: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(bm.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("Ch ${bm.spineIndex + 1} · Page ${bm.pageIndex + 1}") },
        trailingContent = {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Close, contentDescription = "Delete bookmark") }
        },
        modifier = Modifier.clickable(onClick = onJump),
    )
}
