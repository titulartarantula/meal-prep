package dev.mealprep.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Garden: the brand green softened toward olive, on linen (light) or warm charcoal (dark). Fixed palette: the
// wallpaper's dynamic colour no longer overrides it. Terracotta is the one warm accent, and only the selected
// tab and selected chips wear it (GardenAccent below), so the scheme's secondary roles stay neutral linen: M3
// also paints progress tracks, tonal buttons and slider tracks with secondaryContainer.

/** The terracotta accent: [text] for the selected tab's label, [container]/[onContainer] for its pill and chips. */
@Immutable
data class Accent(val text: Color, val container: Color, val onContainer: Color)

internal val LightAccent = Accent(text = Color(0xFF9C4A30), container = Color(0xFFF8E1D6), onContainer = Color(0xFF3B1608))
internal val DarkAccent = Accent(text = Color(0xFFF1B8A0), container = Color(0xFF4A3127), onContainer = Color(0xFFF9DCCF))

internal val GardenLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF3A6B35),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7EACB),
    onPrimaryContainer = Color(0xFF10300F),
    inversePrimary = Color(0xFFA4D39A),
    secondary = Color(0xFF3A6B35),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E1D7),
    onSecondaryContainer = Color(0xFF1F1C18),
    tertiary = Color(0xFF80600F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6E3B5),
    onTertiaryContainer = Color(0xFF291C00),
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF1F1C18),
    surface = Color(0xFFFAF7F2),
    onSurface = Color(0xFF1F1C18),
    surfaceVariant = Color(0xFFE8E1D7),
    onSurfaceVariant = Color(0xFF57524A),
    surfaceTint = Color(0xFF3A6B35),
    inverseSurface = Color(0xFF34302B),
    inverseOnSurface = Color(0xFFF7F1E9),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF857E74),
    outlineVariant = Color(0xFFDDD6CB),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAF7F2),
    surfaceDim = Color(0xFFDFD9CF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F1EA),
    surfaceContainer = Color(0xFFF1ECE4),
    surfaceContainerHigh = Color(0xFFEDE7DE),
    surfaceContainerHighest = Color(0xFFE8E1D7),
)

internal val GardenDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFA4D39A),
    onPrimary = Color(0xFF0E3810),
    primaryContainer = Color(0xFF2A512A),
    onPrimaryContainer = Color(0xFFC8EFBC),
    inversePrimary = Color(0xFF3A6B35),
    secondary = Color(0xFFA4D39A),
    onSecondary = Color(0xFF0E3810),
    secondaryContainer = Color(0xFF393530),
    onSecondaryContainer = Color(0xFFECE6DD),
    tertiary = Color(0xFFE7C46F),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = Color(0xFFFBE2A6),
    background = Color(0xFF1A1815),
    onBackground = Color(0xFFECE6DD),
    surface = Color(0xFF1A1815),
    onSurface = Color(0xFFECE6DD),
    surfaceVariant = Color(0xFF4A453E),
    onSurfaceVariant = Color(0xFFCFC6BA),
    surfaceTint = Color(0xFFA4D39A),
    inverseSurface = Color(0xFFECE6DD),
    inverseOnSurface = Color(0xFF34302B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF9A9187),
    outlineVariant = Color(0xFF4A453E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF393530),
    surfaceDim = Color(0xFF1A1815),
    surfaceContainerLowest = Color(0xFF141210),
    surfaceContainerLow = Color(0xFF1F1D19),
    surfaceContainer = Color(0xFF24211D),
    surfaceContainerHigh = Color(0xFF2E2B26),
    surfaceContainerHighest = Color(0xFF393530),
)

/** Gentle, moderately rounded corners. */
internal val GardenShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** One family (Roboto on the Pixel); slightly larger body text and calmer, semibold titles. */
internal val GardenType = Typography().let { base ->
    val family = FontFamily.Default
    fun t(s: TextStyle, size: Int, line: Int, weight: FontWeight, spacing: Double) =
        s.copy(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)
    base.copy(
        titleLarge = t(base.titleLarge, 24, 32, FontWeight.SemiBold, 0.0),
        titleMedium = t(base.titleMedium, 18, 26, FontWeight.Medium, 0.0),
        titleSmall = t(base.titleSmall, 15, 22, FontWeight.SemiBold, 0.1),
        bodyLarge = t(base.bodyLarge, 16, 24, FontWeight.Normal, 0.15),
        bodyMedium = t(base.bodyMedium, 15, 22, FontWeight.Normal, 0.2),
        bodySmall = t(base.bodySmall, 13, 18, FontWeight.Normal, 0.25),
        labelLarge = t(base.labelLarge, 14, 20, FontWeight.Medium, 0.1),
        labelMedium = t(base.labelMedium, 12, 16, FontWeight.Medium, 0.4),
        labelSmall = t(base.labelSmall, 11, 16, FontWeight.Medium, 0.4),
    )
}

private val LocalAccent = staticCompositionLocalOf { LightAccent }

/** The Garden theme, light or dark with the system. */
@Composable
fun MealPrepTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAccent provides if (dark) DarkAccent else LightAccent) {
        MaterialTheme(colorScheme = if (dark) GardenDark else GardenLight, shapes = GardenShapes, typography = GardenType,
            content = content)
    }
}

object GardenAccent {
    val current: Accent @Composable @ReadOnlyComposable get() = LocalAccent.current

    /** Bottom bar: terracotta pill, icon and label on the selected tab; the rest stay neutral. */
    @Composable
    fun navItemColors() = current.let { a ->
        NavigationBarItemDefaults.colors(indicatorColor = a.container, selectedIconColor = a.onContainer, selectedTextColor = a.text)
    }

    /** Selected FilterChips in terracotta. */
    @Composable
    fun chipColors(): SelectableChipColors = current.let { a ->
        FilterChipDefaults.filterChipColors(selectedContainerColor = a.container, selectedLabelColor = a.onContainer,
            selectedLeadingIconColor = a.onContainer, selectedTrailingIconColor = a.onContainer)
    }

    /** Chip edges that hold 3:1 on the page and in dialogs: `outline` (Material's default `outlineVariant` is 1.4:1),
     *  and a thicker terracotta edge on the selected chip, which also gets a check mark (GardenChip). */
    @Composable
    fun chipBorder(selected: Boolean): BorderStroke = MaterialTheme.colorScheme.let { s ->
        BorderStroke(if (selected) CHIP_EDGE_SELECTED else CHIP_EDGE, chipEdge(s, current, selected))
    }
}

internal val CHIP_EDGE = 1.dp
internal val CHIP_EDGE_SELECTED = 2.dp

/** The edge colour of a chip (GardenContrastTest holds both to 3:1 on the page and in dialogs). */
internal fun chipEdge(s: ColorScheme, a: Accent, selected: Boolean): Color = if (selected) a.text else s.outline
