package com.omnireader.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Brand palette: deep indigo + amber accent, tuned for reading apps.
private val LightColors = lightColorScheme(
    primary = Color(0xFF4A55A2),
    secondary = Color(0xFFE8A13C),
    tertiary = Color(0xFF3F7A5B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C1FF),
    secondary = Color(0xFFF2B95C),
    tertiary = Color(0xFF8CD6AF),
    background = Color(0xFF10131C),
    surface = Color(0xFF171B26),
)

@Composable
fun OmniReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (LocalContext.current as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
