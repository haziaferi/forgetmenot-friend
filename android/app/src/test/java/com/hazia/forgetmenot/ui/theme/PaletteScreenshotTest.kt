package com.hazia.forgetmenot.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// One PNG per palette (5 skins x Light/OLED), in app/src/test/screenshots/theme/. Each draws
// every token role on the stock M3 component that carries it, at the three size tiers, so a
// retuned palette or a component reading the wrong role shows up as a diff. CI runs
// verifyRoborazziDebug; the 1% threshold absorbs font antialiasing between machines.
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xhdpi")
class PaletteScreenshotTest(private val skin: Skin, private val mode: Mode) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun palettes(): List<Array<Any>> = Skin.entries.flatMap { s -> Mode.entries.map { m -> arrayOf<Any>(s, m) } }
    }

    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    @Test fun palette() {
        compose.setContent {
            ForgetmenotTheme(skin.key, mode.key) { AppFrame { Specimen("${skin.displayName} ${mode.displayName}") } }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/theme/${skin.key}_${mode.key}.png", options)
    }
}

@Composable
private fun Specimen(title: String) {
    val sizes = AppTheme.sizes
    val colors = AppTheme.colors
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("forgetmenot", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)

        Box(Modifier.fillMaxWidth().background(colors.warnBg, MaterialTheme.shapes.small).padding(12.dp)) {
            Text("Two copies of Sam's file. Pick one.", color = colors.warnText, style = MaterialTheme.typography.bodySmall)
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(sizes.primary).background(colors.decorative, CircleShape), contentAlignment = Alignment.Center) {
                    Text("SM", color = colors.onDecorative, style = MaterialTheme.typography.labelLarge)
                }
                Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                    Text("Sam Moreau", style = MaterialTheme.typography.titleSmall)
                    Text("3 weeks since you talked", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
                Button(onClick = {}, Modifier.height(sizes.primary)) { Text("Log") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(sizes.chipGap)) {
            FilterChip(selected = true, onClick = {}, label = { Text("Family") }, modifier = Modifier.height(sizes.choice))
            FilterChip(selected = false, onClick = {}, label = { Text("Work") }, modifier = Modifier.height(sizes.choice))
            Switch(checked = true, onCheckedChange = {})
        }

        OutlinedTextField(value = "Sam", onValueChange = {}, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {}, Modifier.height(sizes.primary)) { Text("Message") }
            // T3 text buttons carry labelSmall with 4dp vertical padding; M3's default (8dp, 16sp) clips.
            TextButton(onClick = {}, Modifier.height(sizes.support), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("Clear", color = colors.textMuted, style = MaterialTheme.typography.labelSmall)
            }
            Box(Modifier.size(sizes.support).background(colors.press, MaterialTheme.shapes.small).border(1.dp, colors.border, MaterialTheme.shapes.small))
            Box(Modifier.weight(1f))
            FloatingActionButton(onClick = {}, Modifier.size(sizes.primary)) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    }
}
