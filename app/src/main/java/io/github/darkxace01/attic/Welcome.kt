package io.github.darkxace01.attic

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.darkxace01.attic.ui.PrimaryButton
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.SidePicker
import io.github.darkxace01.attic.ui.ThemePicker
import io.github.darkxace01.attic.ui.samplePhoto
import io.github.darkxace01.attic.ui.signature.GreasePencilLoop
import io.github.darkxace01.attic.ui.theme.Ink
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.ui.theme.ground
import io.github.darkxace01.attic.world.riso.AlbumTitle
import io.github.darkxace01.attic.world.riso.Extra
import io.github.darkxace01.attic.world.riso.IslandKind
import io.github.darkxace01.attic.world.riso.IslandLook
import io.github.darkxace01.attic.world.riso.TitleStyle
import io.github.darkxace01.attic.world.riso.drawIsland

/**
 * The first time Attic opens, in two steps. First a look: the themes, with a little live preview (an
 * island, an album's title, the shutter) that reprints itself in whichever is chosen, and the whole
 * screen with it. Then the offer of a short tour round the app, or straight to scanning.
 */
@Composable
internal fun Welcome(onTour: () -> Unit, onSkip: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "welcome") { s ->
        if (s == 0) ChooseLook(onContinue = { step = 1 }) else OfferTour(onTour, onSkip)
    }
}

@Composable
private fun ChooseLook(onContinue: () -> Unit) {
    val library = LocalContext.current.library
    val prefs by library.settings.prefs.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .ground()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Preview(Modifier.fillMaxWidth().height(250.dp))
        Text("Bring the old photos home", style = MaterialTheme.typography.displaySmall, color = Palette.Ink)
        Spacer(Modifier.height(10.dp))
        Text(
            "Point your phone at a print or a whole album page. Attic finds each photo, straightens it and keeps a clean copy, with who, when and where. Nothing leaves your phone: no internet, no account, no ads.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.Muted,
        )
        Spacer(Modifier.height(24.dp))
        Text("Choose a look", style = MaterialTheme.typography.headlineSmall, color = Palette.Ink)
        Spacer(Modifier.height(4.dp))
        Text("Tap one to see it. You can change it any time in Settings.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        Spacer(Modifier.height(12.dp))
        ThemePicker(Palette.style) { s -> library.settings.update { it.copy(theme = s.name) } }
        Spacer(Modifier.height(12.dp))
        SidePicker(prefs.look) { l -> library.settings.update { it.copy(look = l) } }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * What the chosen theme looks like with an album in it: islands taking turns (a kite, a balloon, a
 * paper boat, a campsite), a title beside them, and the shutter.
 */
@Composable
private fun Preview(modifier: Modifier) {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { clock.floatValue = (it - start) / 1e9f }
    }
    val photo = remember { listOf(samplePhoto()) }
    val kinds = listOf(IslandKind.Kite, IslandKind.Balloon, IslandKind.Boat, IslandKind.Tent, IslandKind.Lighthouse)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).fillMaxSize()) {
            val t = clock.floatValue
            val kind = kinds[(t / 3.2f).toInt() % kinds.size]
            drawIsland(IslandLook(kind, Ink.Teal, Ink.Pink, extra = Extra.Birds), photo, Offset(size.width / 2, size.height * 0.5f), size.minDimension * 0.36f, t, 1)
        }
        Column(Modifier.width(132.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AlbumTitle("Shimla, summer 87", "1987 · 36 photos", TitleStyle.Printed, alignEnd = true)
            io.github.darkxace01.attic.scan.Shutter(true, false, 0f, {}, Modifier)
        }
    }
}

@Composable
private fun OfferTour(onTour: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .ground()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("A quick tour?", style = MaterialTheme.typography.displaySmall, color = Palette.Ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp))
            GreasePencilLoop(Modifier.matchParentSize(), color = Palette.Accent, strokeWidth = 0.04f)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Seven stops, about a minute. Attic takes you round the app and circles each thing in grease pencil with a line about what it does. Skip it whenever you like; it's in Settings if you want it later.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.Muted,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Show me round", onClick = onTour, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        QuietButton("No thanks, start scanning", onClick = onSkip, modifier = Modifier.fillMaxWidth())
    }
}

