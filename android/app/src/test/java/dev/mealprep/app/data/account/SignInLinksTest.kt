package dev.mealprep.app.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInLinksTest {
    private val app = "dev.mealprep.app"

    @Test fun `pkce challenge matches the RFC 7636 example and verifiers are long and url-safe`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Pkce.verifier()
        assertEquals(86, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(v, Pkce.verifier())
        assertEquals(43, Pkce.challenge(v).length)
    }

    @Test fun `login url carries the client, the app, the challenge and an invite`() {
        assertEquals("https://x.example/api/auth/login?client=android&app=dev.mealprep.app&app_challenge=abc-_",
            SignInLinks.loginUrl("https://x.example/api/", app, "abc-_"))
        assertEquals("https://x.example/api/auth/login?client=android&app=dev.mealprep.app.debug&app_challenge=c&invite=t%2Bk",
            SignInLinks.loginUrl("https://x.example/api", "$app.debug", "c", "t+k"))
    }

    @Test fun `return urls give the code or the error, only for this app`() {
        assertEquals(SignInReturn.Code("abc_123"), SignInLinks.parseReturn("$app://auth?code=abc_123", app))
        assertEquals(SignInReturn.Code("x"), SignInLinks.parseReturn("$app.debug://auth?code=x", "$app.debug"))
        val f = SignInLinks.parseReturn("$app://auth?error=not_invited&message=Ask+your+household+head+for+an+invite.", app)
        assertEquals(SignInReturn.Failed("not_invited", "Ask your household head for an invite."), f)
        assertEquals(SignInReturn.Failed("cancelled", null), SignInLinks.parseReturn("$app://auth?error=cancelled", app))
        assertEquals(SignInReturn.Failed("failed", null), SignInLinks.parseReturn("$app://auth", app))
        assertNull(SignInLinks.parseReturn("$app.debug://auth?code=x", app))      // the other build's
        assertNull(SignInLinks.parseReturn("$app://other?code=x", app))
        assertNull(SignInLinks.parseReturn("https://evil.example/auth?code=x", app))
        assertNull(SignInLinks.parseReturn("not a url", app))
    }

    @Test fun `invite from a pasted link or the bare token`() {
        val t = "Abc_def-0123456789xyz"
        assertEquals(t, SignInLinks.inviteToken("https://mealprep.example/api/auth/login?invite=$t"))
        assertEquals(t, SignInLinks.inviteToken("  $t "))
        assertEquals(t, SignInLinks.inviteToken("https://m.example/api/auth/login?next=/&invite=$t#x"))
        assertNull(SignInLinks.inviteToken(""))
        assertNull(SignInLinks.inviteToken("hello there"))
        assertNull(SignInLinks.inviteToken("https://m.example/?invite=short"))
    }

    @Test fun `failure wording`() {
        assertTrue(SignInLinks.failureText(SignInReturn.Failed("not_invited", "x")).contains("Ask your household head for an invite"))
        assertTrue(SignInLinks.failureText(SignInReturn.Failed("cancelled", null)).startsWith("Sign-in was cancelled"))
        assertEquals("That invite has been used.", SignInLinks.failureText(SignInReturn.Failed("failed", "That invite has been used.")))
    }
}
