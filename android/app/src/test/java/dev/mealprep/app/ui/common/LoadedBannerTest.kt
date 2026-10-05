package dev.mealprep.app.ui.common

import dev.mealprep.app.data.Loaded
import dev.mealprep.app.data.api.ApiError
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadedBannerTest {
    private val at = Instant.parse("2026-10-04T12:00:00Z")

    @Test fun `unreachable with saved copy shows banner only`() {
        val l = Loaded("x", ApiError.Unreachable, at)
        assertEquals(at, l.offlineSince); assertNull(l.errorMessage)
    }

    @Test fun `timed out with saved copy shows banner only`() {
        val l = Loaded("x", ApiError.TimedOut, at)
        assertEquals(at, l.offlineSince); assertNull(l.errorMessage)
    }

    @Test fun `unauthorized with saved copy shows the message not the banner`() {
        val l = Loaded("x", ApiError.Unauthorized, at)
        assertNull(l.offlineSince); assertEquals("The server rejected the token. Check it in Settings.", l.errorMessage)
    }

    @Test fun `error without saved copy shows the message`() {
        val l = Loaded<String>(null, ApiError.Unreachable, null)
        assertNull(l.offlineSince); assertEquals(ApiError.Unreachable.let { "Can't reach the meal-prep server. Are you on home Wi-Fi (or WireGuard)?" }, l.errorMessage)
    }

    @Test fun `success shows neither`() {
        val l = Loaded("x", null, at)
        assertNull(l.offlineSince); assertNull(l.errorMessage)
    }
}
