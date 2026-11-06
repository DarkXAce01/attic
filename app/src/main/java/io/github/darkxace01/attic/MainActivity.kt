package io.github.darkxace01.attic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.darkxace01.attic.ui.theme.AtticTheme
import io.github.darkxace01.attic.ui.theme.Palette
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    /** An album file opened from another app (a chat, Files), waiting to be added. */
    private val incoming = androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) take(intent)
        // The Look is known before the first frame, so Attic never opens in the wrong one.
        Palette.dark = isDark(library.settings.now.look)
        // Paper all the way up: dark status and navigation icons whatever the phone's own theme.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val prefs by library.settings.prefs.collectAsStateWithLifecycle()
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = when (prefs.look) { "paper" -> false; "midnight" -> true; else -> systemDark }
            androidx.compose.runtime.SideEffect { Palette.dark = dark }
            AtticTheme { AtticApp(incoming = incoming.value, onIncomingDone = { incoming.value = null }) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun isDark(look: String) = when (look) {
        "paper" -> false
        "midnight" -> true
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
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
