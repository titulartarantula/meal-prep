package dev.mealprep.app

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real launch path: MealPrepApp (DataStore, Room, notification channels) + MainActivity's start-screen choice.
 *  Robolectric gives each test a fresh app data directory, so the DataStore written here is this test's alone. */
@RunWith(RobolectricTestRunner::class)
@Config(application = MealPrepApp::class)
class AppLaunchTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: MealPrepApp = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        // WorkManager is a process-wide singleton: give each test its own (in-memory, synchronous) instance.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    /** Write settings the way Setup does, and wait until the app's settings snapshot has them (MainActivity reads it). */
    private fun configure() = runBlocking {
        // Port 1: nothing listens, so Home's reads fail fast as "can't reach" instead of touching the real network.
        app.graph.settingsStore.update { it.copy(serverUrl = "http://127.0.0.1:1", token = "tok") }
        withContext(Dispatchers.Default) { withTimeout(5_000) { app.graph.settings.first { it.configured } } }
    }

    private fun shareIntent() = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "Chili https://cooking.nytimes.com/recipes/1015819-chili?smid=share")

    @Test fun `first run lands on Setup`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Meal-prep server").assertExists()
        }
    }

    @Test fun `configured app lands on Home and asks for notifications once`() {
        configure()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            compose.onNodeWithContentDescription("More options").assertExists()
            compose.waitUntil(5_000) { runBlocking { app.graph.settingsStore.settings.first().askedNotificationPermission } }
            compose.waitForIdle()
            s.onActivity { a ->
                assertEquals(Manifest.permission.POST_NOTIFICATIONS, shadowOf(a).lastRequestedPermission?.requestedPermissions?.single())
            }
        }
    }

    @Test fun `the bottom bar opens the shopping list and recipes, and back to the week`() {
        configure()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNode(hasText("This week") and isSelectable()).assertIsSelected()
            compose.onNode(hasText("Shopping") and isSelectable()).performClick()
            compose.onNode(hasText("Shopping") and isSelectable()).assertIsSelected()
            compose.onNodeWithText("Shopping list").assertExists()                        // the screen title keeps the long name
            compose.onNodeWithText("Shopping for:").assertExists()
            compose.onNode(hasText("Recipes") and isSelectable()).performClick()
            compose.onNode(hasText("Recipes") and isSelectable()).assertIsSelected()
            // TalkBack: Add recipe right after the header (index 1 between the header's 0 and the list's 2).
            compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f) and hasAnyDescendant(hasText("Add recipe")))
                .assertExists()
            compose.onNodeWithText("Add recipe").performClick()
            compose.onNodeWithText("Scan with camera").assertExists()
            compose.onNodeWithText("Choose photos").assertExists()
            compose.onNodeWithText("Paste an NYT link").performClick()
            compose.onNodeWithText("NYT Cooking link").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNode(hasText("This week") and isSelectable()).performClick()
            compose.onNodeWithText("Shopping for:").assertDoesNotExist()
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Staples").assertExists()
            compose.onNodeWithText("Snap a cookbook recipe").assertDoesNotExist()      // adding lives on Recipes
            compose.onNodeWithText("Shopping", useUnmergedTree = true).assertExists()   // the tab, not a menu item
        }
    }

    @Test fun `the staples reminder link opens the shopping list tab`() {
        configure()
        val link = Intent(app, MainActivity::class.java).putExtra(dev.mealprep.app.ui.nav.Nav.EXTRA, dev.mealprep.app.ui.nav.Nav.list())
        ActivityScenario.launch<MainActivity>(link).use {
            compose.onNode(hasText("Shopping") and isSelectable()).assertIsSelected()
            compose.onNodeWithText("Shopping for:").assertExists()
        }
    }

    @Test fun `a shared NYT link reaches the Share screen`() {
        configure()
        ActivityScenario.launch<MainActivity>(shareIntent()).use {
            compose.onNodeWithText("Save a recipe").assertExists()
            compose.onNodeWithText("NYT Cooking · 1015819-chili").assertExists()
            compose.onNodeWithText("Save to Recipes").assertExists()
            compose.onNodeWithText("Which week is it for?").assertDoesNotExist()
        }
    }

    @Test fun `a recipe file shared from another app is copied in and opens the import screen under Recipes`() {
        configure()
        val uri = android.net.Uri.parse("content://com.example.files/doc/backup.json")
        shadowOf(app.contentResolver).registerInputStream(uri, """{"@type":"Recipe","name":"Test Soup"}""".byteInputStream())
        val send = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
        ActivityScenario.launch<MainActivity>(send).use {
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Import recipes")).fetchSemanticsNodes().isNotEmpty() }
            // Offline in the test (port 1): the preview says what to do.
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText(dev.mealprep.app.ui.exchange.ExchangeText.OFFLINE_IMPORT)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, app.graph.importsDir.list()!!.size)            // the copy, not the shared URI, is read
            compose.onNodeWithContentDescription("Navigate up").performClick()
            compose.onNode(hasText("Recipes") and isSelectable()).assertIsSelected()
        }
    }

    @Test fun `the Recipes tab offers import and export`() {
        configure()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNode(hasText("Recipes") and isSelectable()).performClick()
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Import recipes from a file").assertExists()
            compose.onNodeWithText("Export all recipes (a backup file)").performClick()
            compose.onNodeWithText("Export all recipes").assertExists()
            compose.onNodeWithText("Send…").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText(dev.mealprep.app.ui.exchange.ExchangeText.OFFLINE_EXPORT)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Add recipe").performClick()
            compose.onNodeWithText("Import from a file").assertExists()
        }
    }
}
