package dev.mealprep.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.launch

/**
 * Where Google sign-in comes back from the browser (`<application id>://auth?code=…`). The code is traded for a
 * session in the background (Accounts.complete; the sign-in screen shows how it goes) and the app comes back to the
 * front, closing the browser tab that sat on top of it.
 */
class AuthReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.dataString
        if (url != null && !isReplayFromHistory(intent)) graph.scope.launch { graph.accounts.complete(url) }
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
