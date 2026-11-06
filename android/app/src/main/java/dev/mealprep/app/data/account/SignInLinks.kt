package dev.mealprep.app.data.account

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Sign-in with Google through the web's own round trip (server: GET /auth/login?client=android → Google → /auth/callback
 * → back to the app at `<application id>://auth?code=…`). The app keeps a PKCE verifier and sends only its S256
 * challenge, so a code that reached another app (one that claims the same scheme) is useless without the verifier.
 */
object Pkce {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** 64 random bytes → 86 base64url characters (RFC 7636 allows 43–128). */
    fun verifier(random: SecureRandom = SecureRandom()): String = b64.encodeToString(ByteArray(64).also(random::nextBytes))

    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/** What came back to the app from the browser. */
sealed interface SignInReturn {
    data class Code(val code: String) : SignInReturn
    /** kind: cancelled | not_invited | failed (the server's words in [message], when it sent some). */
    data class Failed(val kind: String, val message: String?) : SignInReturn
}

object SignInLinks {
    const val HOST = "auth"
    const val NOT_INVITED = "not_invited"
    const val CANCELLED = "cancelled"

    private fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8)

    /** The page the Custom Tab opens: [base] = the public API address (…/api). */
    fun loginUrl(base: String, appId: String, challenge: String, invite: String? = null): String =
        base.trimEnd('/') + "/auth/login?client=android&app=" + enc(appId) + "&app_challenge=" + enc(challenge) +
            (invite?.let { "&invite=" + enc(it) } ?: "")

    /** The return URL's code or error; null when it isn't a sign-in return for [appId]. */
    fun parseReturn(url: String, appId: String): SignInReturn? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        if (!u.scheme.equals(appId, ignoreCase = true) || u.host != HOST) return null
        val q = query(u.rawQuery)
        q["code"]?.takeIf { it.isNotBlank() }?.let { return SignInReturn.Code(it) }
        return SignInReturn.Failed(q["error"] ?: "failed", q["message"]?.takeIf { it.isNotBlank() })
    }

    /** An invite from a pasted invite link (…/auth/login?invite=…) or the bare token; null if neither. */
    fun inviteToken(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.contains("invite=")) {
            val raw = t.substringAfter("?", t).substringBefore("#")
            return query(raw)["invite"]?.takeIf(::looksLikeToken)
        }
        return t.takeIf(::looksLikeToken)
    }

    private fun looksLikeToken(t: String) = t.length in 16..200 && t.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** What the sign-in screen says when it didn't work. */
    fun failureText(f: SignInReturn.Failed): String = when (f.kind) {
        CANCELLED -> "Sign-in was cancelled. Try again when you're ready."
        NOT_INVITED -> "This Google account isn't in a household yet. Ask your household head for an invite, then sign in again."
        else -> f.message ?: "Sign-in didn't work. Try again."
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split("&").filter { it.isNotEmpty() }.associate { kv ->
        val k = kv.substringBefore("="); val v = kv.substringAfter("=", "")
        URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
    }
}
