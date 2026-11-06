package io.github.darkxace01.attic.settings

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * This phone as a lightbox for film: the whole screen plain white at full brightness, nothing else.
 * Lay negatives or slides on it and scan them with another phone. Tap twice, or go back, to leave.
 */
@Composable
fun LightboxScreen(onDone: () -> Unit) {
    val activity = LocalContext.current as? Activity
    val view = LocalView.current
    val hint = remember { Animatable(1f) }
    DisposableEffect(Unit) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness
        window?.let { w ->
            w.attributes = w.attributes.apply { screenBrightness = 1f }
            WindowCompat.getInsetsController(w, view).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = before ?: -1f }
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    // The hint fades away so nothing shows through the film.
    LaunchedEffect(Unit) { delay(2500); hint.animateTo(0f, tween(900)) }
    BackHandler(onBack = onDone)
    val lastTap = remember { longArrayOf(0L) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                val now = System.currentTimeMillis()
                if (now - lastTap[0] < 400) onDone() else lastTap[0] = now
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Lay the film here and scan it with another phone. Tap twice to leave.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF6B6B6B),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(40.dp).graphicsLayer { alpha = hint.value },
        )
    }
}
