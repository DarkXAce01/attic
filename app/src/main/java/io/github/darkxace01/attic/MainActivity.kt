package io.github.darkxace01.attic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.darkxace01.attic.ui.theme.AtticTheme

class MainActivity : ComponentActivity() {
    /** An album file opened from another app (a chat, Files), waiting to be added. */
    private val incoming = androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) take(intent)
        // Paper all the way up: dark status and navigation icons whatever the phone's own theme.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            AtticTheme { AtticApp(incoming = incoming.value, onIncomingDone = { incoming.value = null }) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: android.content.Intent?) {
        val uri = when (intent?.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else -> null
        }
        if (uri != null) incoming.value = uri
    }
}
