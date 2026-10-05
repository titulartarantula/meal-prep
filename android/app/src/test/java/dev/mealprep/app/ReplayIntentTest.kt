package dev.mealprep.app

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplayIntentTest {
    @Test fun `intent relaunched from Recents is a replay`() {
        assertTrue(isReplayFromHistory(Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        assertFalse(isReplayFromHistory(Intent(Intent.ACTION_SEND)))
    }
}
