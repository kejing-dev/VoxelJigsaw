package io.embodit.voxeljigsaw.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/** Design palette (palette.colorScheme / brandTokens). Voxel colours come from the puzzle files, not from here. */
object JigColors {
    val SeamGold = Color(0xFFF2BE4A) // interaction: hints, seam flash, selection
    val Jade = Color(0xFF5FD3A4) // passable: done badge
    val Alert = Color(0xFFFF9A3D) // wrong way
    val Error = Color(0xFFFF6B5E) // load failure
    val OnGold = Color(0xFF1F2430) // label on a gold button
    val Limestone = Color(0xFFEDE6D8)
    val TurntableRing = Color(0xFFB9AE98)
}

/**
 * SpatialUI Vibrant with the four semantic roles of the design; the twelve adaptive grey roles are kept from
 * the system scheme (palette.colorScheme: inherit). Type is PicoTheme's own roles with the system font
 * (typography.font: no bundled font).
 */
@Composable
fun JigsawTheme(content: @Composable () -> Unit) {
    val system = systemColorScheme(LocalContext.current)
    val colors =
        remember(system) {
            system.copy(
                fillPrimary = system.fillPrimary,
                fillSecondary = system.fillSecondary,
                fillTertiary = system.fillTertiary,
                fillLight = system.fillLight,
                labelPrimaryLight = system.labelPrimaryLight,
                labelPrimary = system.labelPrimary,
                labelSecondary = system.labelSecondary,
                labelTertiary = system.labelTertiary,
                labelQuaternary = system.labelQuaternary,
                lightenHover = system.lightenHover,
                lightenPressed = system.lightenPressed,
                dividerLine = system.dividerLine,
                interaction = JigColors.SeamGold,
                passable = JigColors.Jade,
                alert = JigColors.Alert,
                error = JigColors.Error,
            )
        }
    PicoTheme(colorScheme = colors, content = content)
}
