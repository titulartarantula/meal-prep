package dev.mealprep.app.ui.common

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import dev.mealprep.app.core.Sources
import dev.mealprep.app.ui.library.LibraryContent
import dev.mealprep.app.ui.library.LibraryState
import dev.mealprep.app.ui.rating.RatingContent
import dev.mealprep.app.ui.rating.RatingState
import dev.mealprep.app.ui.theme.MealPrepTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Accessibility checks kept in the suite (UX/WCAG review 0.7, next steps 3). */
@RunWith(RobolectricTestRunner::class)
class A11yTest {
    @get:Rule val compose = createComposeRule()

    private fun role(r: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, r)
    private val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test fun `messages and the offline banner are read out when they appear`() {
        compose.setContent {
            MealPrepTheme(dark = false) {
                androidx.compose.foundation.layout.Column {
                    MessageText("Pick 1 to 5 first.")
                    OfflineBanner(Instant.parse("2026-10-06T13:00:00Z"))
                }
            }
        }
        compose.onNodeWithText("Pick 1 to 5 first.").assert(polite)
        compose.onNode(hasText("Offline", substring = true)).assert(polite)
    }

    @Test fun `rating chips are two radio groups, the chosen one selected`() {
        compose.setContent {
            MealPrepTheme(dark = false) {
                RatingContent(RatingState(title = "Chili", night = "Tuesday", family = 4, company = "yes", loading = false),
                    {}, {}, {}, {}, {})
            }
        }
        compose.onAllNodes(role(Role.RadioButton)).assertCountEquals(5 + 3)
        compose.onAllNodes(role(Role.Checkbox)).assertCountEquals(0)
        compose.onNodeWithContentDescription("4 out of 5").assert(role(Role.RadioButton)).assertIsSelected()
        compose.onNodeWithContentDescription("4 out of 5").onParent().assert(group)
        compose.onNodeWithText("Yes").assert(role(Role.RadioButton)).assertIsSelected().onParent().assert(group)
    }

    @Test fun `Book or Other is one choice`() {
        var kind = Sources.BOOK
        compose.setContent { MealPrepTheme(dark = false) { SourceKindChips(kind, { kind = it }) } }
        compose.onAllNodes(role(Role.RadioButton)).assertCountEquals(2)
        compose.onNodeWithText("Book").assertIsSelected().onParent().assert(group)
        compose.onNodeWithText("Other").performClick()
        assertEquals(Sources.OTHER, kind)
    }

    @Test fun `the Recipes sort is a radio group, Good for company stays a checkbox`() {
        compose.setContent { MealPrepTheme(dark = false) { LibraryContent(LibraryState(loading = false), {}, {}, {}, {}) } }
        compose.onAllNodes(role(Role.RadioButton)).assertCountEquals(3)
        compose.onNodeWithText("Newest").assert(role(Role.RadioButton)).assertIsSelected().onParent().assert(group)
        compose.onNodeWithText("Good for company").assert(role(Role.Checkbox))
    }
}
