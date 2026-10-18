package com.omnireader.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.omnireader.app.core.source.Tokens
import com.omnireader.app.ui.catalog.CatalogScreen
import com.omnireader.app.ui.document.EpubReaderScreen
import com.omnireader.app.ui.downloads.DownloadsScreen
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
    const val IMAGE_READER = "reader/image/{mediaId}"
    const val SOURCE_BROWSE = "source/{repoToken}/{sourceIdToken}"
    const val SOURCE_DETAILS = "source/details/{detailsToken}/{titleToken}"

    fun epub(mediaId: Long) = "reader/epub/$mediaId"
    fun image(mediaId: Long) = "reader/image/$mediaId"
    fun sourceBrowse(repoUrl: String, sourceId: String) =
        "source/${Tokens.encode(repoUrl)}/${Tokens.encode(sourceId)}"
    fun sourceDetails(detailsUrl: String, title: String) =
        "source/details/${Tokens.encode(detailsUrl)}/${Tokens.encode(title)}"
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** Root scaffold + NavHost wiring every screen together. */
@Composable
fun OmniNavigation(navController: NavHostController = rememberNavController()) {
    val tabs = listOf(
        Tab(Routes.LIBRARY, "Library", Icons.Default.LibraryBooks),
        Tab(Routes.CATALOG, "Catalog", Icons.Default.Download),
        Tab(Routes.EXTENSIONS, "Extensions", Icons.Default.Extension),
        Tab(Routes.SETTINGS, "Settings", Icons.Default.Settings),
    )

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBars = tabs.any { it.route == currentRoute }

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
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
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
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onOpenEpub = { id -> navController.navigate(Routes.epub(id)) },
                    onOpenImage = { id -> navController.navigate(Routes.image(id)) },
                )
            }
            composable(Routes.CATALOG) {
                CatalogScreen(
                    onOpenLocal = { id -> navController.navigate(Routes.epub(id)) },
                    onOpenRemote = { repo, src, urlToken ->
                        navController.navigate(Routes.sourceBrowse(repo, src))
                    },
                )
            }
            composable(Routes.EXTENSIONS) {
                ExtensionsScreen(onOpenSource = { repo, src ->
                    navController.navigate(Routes.sourceBrowse(repo, src))
                })
            }
            composable(Routes.DOWNLOADS) { DownloadsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }

            composable("downloads_from_tab") { DownloadsScreen() }

            composable(Routes.SOURCE_BROWSE) { entry ->
                SourceBrowseScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetails = { _, _, detailsToken, title ->
                        navController.navigate("source/details/$detailsToken/${Tokens.encode(title)}")
                    },
                )
            }
            composable(Routes.SOURCE_DETAILS) { entry ->
                SourceDetailsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenReaderFromDownloads = { /* handled via library SOURCE item */ },
                )
            }

            composable(Routes.EPUB_READER) { EpubReaderScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.IMAGE_READER) { ReaderScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
