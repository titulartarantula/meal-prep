package dev.mealprep.app.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.mealprep.app.data.account.SignInStatus
import dev.mealprep.app.data.api.ListItem
import dev.mealprep.app.data.settings.Account
import dev.mealprep.app.ui.cart.ListContent
import dev.mealprep.app.ui.cart.ListState
import dev.mealprep.app.ui.common.Access
import dev.mealprep.app.ui.common.AccessText
import dev.mealprep.app.ui.common.LocalAccess
import dev.mealprep.app.ui.common.Perm
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignInTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `sign in, with an invite link, and the household token`() {
        val started = mutableListOf<String?>(); var token = 0
        compose.setContent { SignInContent(SignInStatus.Idle, onSignIn = { started += it }, onUseToken = { token++ }) }
        compose.onNodeWithText(SIGN_IN).performClick()
        compose.onNodeWithText("I have an invite link").performClick()
        compose.onNodeWithText("Invite link").performTextInput("nonsense")
        compose.onNodeWithText("That doesn't look like an invite link.").assertExists()
        compose.onNodeWithText(SIGN_IN).assertIsNotEnabled()
        compose.onNodeWithText("Invite link").performTextInput("&invite=Abc_def-0123456789xyz")
        compose.onNodeWithText(SIGN_IN).assertIsEnabled().performClick()
        compose.onNodeWithText(USE_TOKEN).performClick()
        assertEquals(listOf(null, "Abc_def-0123456789xyz"), started); assertEquals(1, token)
    }

    @Test fun `not invited is said plainly, and nothing to click while signing in`() {
        compose.setContent {
            Column {
                SignInContent(SignInStatus.Failed("This Google account isn't in a household yet. Ask your household head for an invite, then sign in again.",
                    notInvited = true), onSignIn = {}, onUseToken = null)
            }
        }
        compose.onNodeWithText("Ask your household head for an invite", substring = true).assertExists()
        compose.onNodeWithText(USE_TOKEN).assertDoesNotExist()
    }

    @Test fun `working disables the button`() {
        compose.setContent { SignInContent(SignInStatus.Working, onSignIn = {}, onUseToken = {}) }
        compose.onNodeWithText(SIGN_IN).assertIsNotEnabled()
        compose.onNodeWithText("Signing in…").assertExists()
    }

    @Test fun `who is signed in and the roles in words`() {
        compose.setContent { Column { AccountSummary(Account("Sam Example", "sam@example.com", "Testers", listOf("owner", "planner"))) } }
        compose.onNodeWithText("Signed in as Sam Example").assertExists()
        compose.onNodeWithText("sam@example.com").assertExists()
        compose.onNodeWithText("Household: Testers · Owner, Planner").assertExists()
        assertEquals("Contributor, Child", roleNames(listOf("contributor", "child")))
    }

    @Test fun `access - unknown permissions hide nothing, known ones decide`() {
        val unknown = Access(null)
        assertTrue(unknown.canBuildCart && unknown.canSendCart && unknown.canEditPlan && unknown.canEditRecipes)
        val contributor = Access(setOf("recipe.read", "plan.read", Perm.RATE_SELF))
        assertFalse(contributor.canBuildCart || contributor.canSendCart || contributor.canEditPlan || contributor.canEditRecipes)
        assertTrue(contributor.can(Perm.RATE_SELF))
        assertNull(statusText(SignInStatus.Idle))
    }

    @Test fun `the shopping list says who builds the cart instead of the button`() {
        val list = ListState(weeks = setOf(LocalDate.parse("2026-10-11")), loading = false,
            items = listOf(ListItem(key = "egg|n", name = "egg", qty = 6.0)))
        compose.setContent {
            CompositionLocalProvider(LocalAccess provides Access(setOf("plan.read"))) {
                ListContent(list, onWeek = {}, onToggle = {}, onBuild = {}, today = LocalDate.parse("2026-10-07"))
            }
        }
        compose.onNodeWithText(AccessText.CART).assertExists()
        compose.onNodeWithText("Build cart", substring = true).assertDoesNotExist()
    }
}
