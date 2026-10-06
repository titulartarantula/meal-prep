package dev.mealprep.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import dev.mealprep.app.ui.nav.MainTabs
import dev.mealprep.app.ui.nav.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var scheme: ColorScheme
    private lateinit var shapes: Shapes
    private lateinit var accent: Accent

    private fun capture(dark: Boolean? = null) = compose.setContent {
        val body: @Composable () -> Unit = {
            scheme = MaterialTheme.colorScheme; shapes = MaterialTheme.shapes; accent = GardenAccent.current
        }
        if (dark == null) MealPrepTheme(content = body) else MealPrepTheme(dark, body)
    }

    @Test fun `light is Garden, not the wallpaper colours`() {
        capture()
        assertSame(GardenLight, scheme)
        assertEquals(Color(0xFF3A6B35), scheme.primary)
        assertEquals(Color(0xFFFAF7F2), scheme.background)
        assertEquals(LightAccent, accent)
    }

    @Test @Config(qualifiers = "night")
    fun `dark follows the system and is warm charcoal`() {
        capture()
        assertSame(GardenDark, scheme)
        assertEquals(Color(0xFF1A1815), scheme.background)
        assertEquals(DarkAccent, accent)
    }

    @Test fun `gentle corners`() {
        capture(dark = false)
        assertEquals(GardenShapes, shapes)
    }

    @Test fun `terracotta is kept out of the scheme's secondary roles`() {
        listOf(GardenLight to LightAccent, GardenDark to DarkAccent).forEach { (s, a) ->
            listOf(s.secondary, s.secondaryContainer, s.onSecondaryContainer, s.tertiaryContainer).forEach {
                assertNotEquals(a.container, it); assertNotEquals(a.text, it)
            }
        }
    }

    @Test fun `the bottom bar and chips take the terracotta accent`() {
        var nav: NavigationBarItemColors? = null
        var chip: SelectableChipColors? = null
        var expectedChip: SelectableChipColors? = null
        compose.setContent {
            MealPrepTheme(dark = false) {
                nav = GardenAccent.navItemColors(); chip = GardenAccent.chipColors()
                expectedChip = LightAccent.let {
                    FilterChipDefaults.filterChipColors(selectedContainerColor = it.container, selectedLabelColor = it.onContainer,
                        selectedLeadingIconColor = it.onContainer, selectedTrailingIconColor = it.onContainer)
                }
                MainTabs(Tab.WEEK) {}
            }
        }
        compose.waitForIdle()
        assertEquals(LightAccent.container, nav!!.selectedIndicatorColor)
        assertEquals(LightAccent.onContainer, nav!!.selectedIconColor)
        assertEquals(LightAccent.text, nav!!.selectedTextColor)
        assertEquals(expectedChip, chip)   // SelectableChipColors keeps its colours private; equals compares them
    }
}
