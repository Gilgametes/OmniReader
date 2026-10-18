package com.omnireader.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsState(
    val wifiOnly: Boolean = false,
    val autoResume: Boolean = true,
    val keepScreenOnByDefault: Boolean = true,
    val cacheBytes: Long = 0,
    val downloadsBytes: Long = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init { recomputeStorage() }

    private fun read(): SettingsState = SettingsState(
        wifiOnly = prefs.getBoolean("wifi_only", false),
        autoResume = prefs.getBoolean("auto_resume", true),
        keepScreenOnByDefault = prefs.getBoolean("keep_screen_on", true),
    )

    fun setWifiOnly(v: Boolean) = update { it.copy(wifiOnly = v); prefs.edit().putBoolean("wifi_only", v).apply() }
    fun setAutoResume(v: Boolean) = update { it.copy(autoResume = v); prefs.edit().putBoolean("auto_resume", v).apply() }
    fun setKeepScreenOn(v: Boolean) = update { it.copy(keepScreenOnByDefault = v); prefs.edit().putBoolean("keep_screen_on", v).apply() }

    fun clearCache() = viewModelScope.launch {
        withContext(Dispatchers.IO) { context.cacheDir?.deleteRecursively() }
        recomputeStorage()
    }

    fun recomputeStorage() = viewModelScope.launch {
        val (cache, dl) = withContext(Dispatchers.IO) {
            dirSize(context.cacheDir) to dirSize(java.io.File(context.filesDir, "downloads"))
        }
        _state.value = _state.value.copy(cacheBytes = cache, downloadsBytes = dl)
    }

    private inline fun update(block: (SettingsState) -> SettingsState) {
        _state.value = block(_state.value)
    }

    companion object {
        fun dirSize(dir: java.io.File?): Long =
            dir?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

        fun human(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1e9)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1e6)
            else -> String.format("%.0f KB", bytes / 1e3)
        }
    }
}

/** App settings + storage management (cache & download sizes, clear actions). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { insets ->
        Column(
            Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Downloads")
            ToggleRow("Wi-Fi only", state.wifiOnly, vm::setWifiOnly)
            Spacer(Modifier.height(4.dp))

            SectionTitle("Reading")
            ToggleRow("Auto-resume last position", state.autoResume, vm::setAutoResume)
            ToggleRow("Keep screen on while reading", state.keepScreenOnByDefault, vm::setKeepScreenOn)

            SectionTitle("Storage")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cover/page cache")
                Text(human(state.cacheBytes))
            }
            FilterChip(selected = false, onClick = vm::clearCache, label = { Text("Clear cache") })
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Downloaded chapters")
                Text(human(state.downloadsBytes))
            }
            Text(
                "Delete individual chapters from the Downloads screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun human(bytes: Long) = SettingsViewModel.human(bytes)

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
