package com.omnireader.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.omnireader.app.ui.navigation.OmniNavigation
import com.omnireader.app.ui.theme.OmniReaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Files opened from other apps are imported through the Library screen's
        // SAF flow; we never hold raw paths (strict SAF rule).
        setContent {
            OmniReaderTheme {
                OmniNavigation()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                // Persist read access so SAF-granted content URIs keep working after restart.
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                } catch (_: Exception) {
                }
                pendingViewUri = uri.toString()
            }
        }
    }
}
