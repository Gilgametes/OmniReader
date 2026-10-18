package com.omnireader.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIntoContainer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnireader.app.core.source.Tokens
import com.omnireader.app.data.MediaType
import com.omnireader.app.ui.catalog.CatalogScreen
import com.omnireader.app.ui.document.EpubReaderScreen
import com.omnireader.app.ui.downloads.DownloadsScreen
import com.omnireader.app.ui.downloads.ActiveDownloadCountViewModel
import com.omnireader.app.ui.extensions.ExtensionsScreen
import com.omnireader.app.ui.library.LibraryScreen
import com.omnireader.app.ui.reader.ReaderScreen
import com.omnireader.app.ui.settings.SettingsScreen
import com.omnireader.app.ui.source.SourceBrowseScreen
import com.omnireader.app.ui.source.SourceDetailsScreen

object Routes {
    const val LIBRARY = "library"
    const val CATALOG = "catalog"
    const val EXTENSIONS = "extensions"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val EPUB_READER = "reader/epub/{mediaId}"
    const val IMAGE_READER = "reader/image/{mediaId}?chapter={chapter}"
    const val SOURCE_BROWSE = "source/{repoToken}/{sourceIdToken}"
    const val SOURCE_DETAILS = "source/details/{detailsToken}/{titleToken}"

    fun epub(mediaId: Long) = "reader/epub/$mediaId"
    fun image(mediaId: Long, chapter: String? = null) =
        if (chapter == null) "reader/image/$mediaId"
        else "reader/image/$mediaId?chapter=${Tokens.encode(chapter)}"
    fun sourceBrowse(repoUrl: String, sourceId: String) =
        "source/${Tokens.encode(repoUrl)}/${Tokens.encode(sourceId)}"
    fun sourceDetails(detailsUrl: String, title: String) =
        "source/details/${Tokens.encode(detailsUrl)}/${Tokens.encode(title)}"
}

private data class Tab(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/** Root scaffold + NavHost wiring every screen together. */
@Composable
fun OmniNavigation(navController: NavHostController = rememberNavController()) {
    val tabs = listOf(
        Tab(Routes.LIBRARY, "Library", Icons.AutoMirrored.Filled.MenuBook),
        Tab(Routes.CATALOG, "Catalog", Icons.Default.Explore),
        Tab(Routes.EXTENSIONS, "Extensions", Icons.Default.Extension),
        Tab(Routes.DOWNLOADS, "Downloads", Icons.Default.DownloadForOffline),
        Tab(Routes.SETTINGS, "Settings", Icons.Default.Settings),
    )

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBars = tabs.any { it.route == currentRoute }

    // Live badge on the Downloads tab while anything is queued/downloading.
    val badgeVm: ActiveDownloadCountViewModel = hiltViewModel()
    val activeCount by badgeVm.count.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            if (showBars) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.route == Routes.DOWNLOADS && activeCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$activeCount") } }) {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { insets ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            modifier = Modifier.padding(insets),
            enterTransition = { fadeIn(tween(180)) + slideIntoContainer(androidx.compose.animation.SlideInHorizontally(tween(220)) { it / 6 }) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(150)) },
            popExitTransition = { androidx.compose.animation.slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(150)) },
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onOpen = { item ->
                        when (item.type) {
                            MediaType.EPUB -> navController.navigate(Routes.epub(item.id))
                            MediaType.ARCHIVE, MediaType.IMAGE, MediaType.FOLDER, MediaType.PDF ->
                                navController.navigate(Routes.image(item.id))
                            MediaType.TEXT -> navController.navigate(Routes.image(item.id))
                            MediaType.SOURCE -> {
                                val url = item.sourceUrl ?: return@LibraryScreen
                                navController.navigate(Routes.sourceDetails(url, item.title))
                            }
                        }.let { }
                    },
                )
            }
            composable(Routes.CATALOG) {
                CatalogScreen(
                    onOpenLocal = { id, type ->
                        when (type) {
                            MediaType.EPUB -> navController.navigate(Routes.epub(id))
                            else -> navController.navigate(Routes.image(id))
                        }
                    },
                    onOpenRemote = { detailsUrl, title ->
                        navController.navigate(Routes.sourceDetails(detailsUrl, title))
                    },
                )
            }
            composable(Routes.EXTENSIONS) {
                ExtensionsScreen(onOpenSource = { repo, src ->
                    navController.navigate(Routes.sourceBrowse(repo, src))
                })
            }
            composable(Routes.DOWNLOADS) {
                DownloadsScreen(
                    onOpenChapter = { mediaId, chapterDir ->
                        navController.navigate(Routes.image(mediaId, chapterDir))
                    },
                    onOpenMedia = { mediaId, type ->
                        when (type) {
                            MediaType.EPUB -> navController.navigate(Routes.epub(mediaId))
                            else -> navController.navigate(Routes.image(mediaId))
                        }
                    },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen() }

            composable(Routes.SOURCE_BROWSE) {
                SourceBrowseScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetails = { _, _, detailsToken, title ->
                        navController.navigate("source/details/$detailsToken/${Tokens.encode(title)}")
                    },
                )
            }
            composable(Routes.SOURCE_DETAILS) {
                SourceDetailsScreen(
                    onBack = { navController.popBackStack() },
                    onReadOffline = { mediaId, chapterDir ->
                        navController.navigate(Routes.image(mediaId, chapterDir))
                    },
                )
            }

            composable(
                Routes.EPUB_READER,
                arguments = listOf(navArgument("mediaId") { defaultValue = -1L }),
            ) { EpubReaderScreen(onBack = { navController.popBackStack() }) }

            composable(
                Routes.IMAGE_READER,
                arguments = listOf(
                    navArgument("mediaId") { defaultValue = -1L },
                    navArgument("chapter") {
                        defaultValue = ""
                        nullable = false
                    },
                ),
            ) { ReaderScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
