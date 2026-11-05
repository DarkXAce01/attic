package io.github.darkxace01.attic.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SmokeShot {
    @get:Rule val compose = createComposeRule()

    @Test
    fun renders() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
                Box(Modifier.size(120.dp).background(Color(0xFFE5553A)))
                Text("Attic", color = Color.White)
            }
        }
        compose.onRoot().captureRoboImage("build/shots/smoke.png")
    }
}
