package dev.mealprep.app.ui.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.AppGraph
import dev.mealprep.app.data.account.SignInLinks
import dev.mealprep.app.data.account.SignInStatus
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.MeHousehold
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.data.settings.Account
import dev.mealprep.app.ui.common.announced
import kotlinx.coroutines.launch

const val SIGN_IN = "Sign in with Google"
const val USE_TOKEN = "Use the household token (home network)"
const val SIGN_OUT = "Sign out"

/** Opens Google sign-in in a Custom Tab (the browser's own tab, which Google allows; never a WebView). */
fun openSignIn(context: Context, url: String) {
    val uri = url.toUri()
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Starts a sign-in from anywhere ([invite]: from an invite link) and opens the browser. */
@Composable
fun rememberSignIn(graph: AppGraph): (String?) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return { invite -> scope.launch { openSignIn(ctx, graph.accounts.begin(invite)) } }
}

internal fun statusText(s: SignInStatus): String? = when (s) {
    SignInStatus.Idle -> null
    SignInStatus.InBrowser -> "Finish signing in in the browser…"
    SignInStatus.Working -> "Signing in…"
    SignInStatus.Done -> "Signed in."
    is SignInStatus.Failed -> s.message
}

/** The first-run screen (and Settings' "Sign in"): Google, an optional invite link, or the household token. */
@Composable
fun SignInContent(
    status: SignInStatus, onSignIn: (invite: String?) -> Unit,
    /** null = not offered (Settings has the token form further down). */
    onUseToken: (() -> Unit)?,
    showTitle: Boolean = true,
) {
    var inviteOpen by rememberSaveable { mutableStateOf(false) }
    var invite by rememberSaveable { mutableStateOf("") }
    val inviteToken = SignInLinks.inviteToken(invite)
    val busy = status == SignInStatus.Working
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) Text("Sign in to Meal Prep", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text("Use your Google account to see your household's week, recipes and shopping list, at home or away. " +
            "Your household head adds you, or sends you an invite link.", style = MaterialTheme.typography.bodyMedium)
        Button({ onSignIn(inviteToken) }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = !busy && (!inviteOpen || invite.isBlank() || inviteToken != null)) { Text(SIGN_IN) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        statusText(status)?.let {
            Text(it, Modifier.announced(), color = if (status is SignInStatus.Failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface)
        }
        if (!inviteOpen) TextButton({ inviteOpen = true }, Modifier.heightIn(min = 48.dp)) { Text("I have an invite link") }
        else {
            OutlinedTextField(invite, { invite = it }, label = { Text("Invite link") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = invite.isNotBlank() && inviteToken == null,
                supportingText = { Text(if (invite.isNotBlank() && inviteToken == null) "That doesn't look like an invite link."
                    else "Paste the link, then sign in. It's used once.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done))
        }
        onUseToken?.let { TextButton(it, Modifier.heightIn(min = 48.dp)) { Text(USE_TOKEN) } }
    }
}

@Composable
fun SignInScreen(graph: AppGraph, onSignedIn: () -> Unit, onUseToken: () -> Unit) {
    val status by graph.accounts.status.collectAsStateWithLifecycle()
    val signIn = rememberSignIn(graph)
    LaunchedEffect(status) { if (status == SignInStatus.Done) { graph.accounts.resetStatus(); onSignedIn() } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        SignInContent(status, signIn, onUseToken)
    }
}

/** Settings → Account: who is signed in, the household (and the switcher), Sign out; or Sign in. */
@Composable
fun AccountSection(graph: AppGraph, onSignedOut: () -> Unit) {
    val s by graph.settings.collectAsStateWithLifecycle()
    val status by graph.accounts.status.collectAsStateWithLifecycle()
    val signIn = rememberSignIn(graph)
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    // The households to switch between come from the server each time Settings opens (and after a switch).
    val me by produceState<List<MeHousehold>?>(null, s.signedIn, s.householdId) {
        value = if (s.signedIn) (graph.accounts.refresh() as? ApiResult.Ok)?.value?.households else null
    }
    LaunchedEffect(status) { if (status == SignInStatus.Done) graph.accounts.resetStatus() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (!s.signedIn) {
            Text(if (s.configured) "Not signed in. This phone uses the household token, so it works on the home network only."
                else "Not signed in.", style = MaterialTheme.typography.bodyMedium)
            SignInContent(status, signIn, onUseToken = null, showTitle = false)
            return@Column
        }
        AccountSummary(s.account)
        val households = me.orEmpty()
        if (households.size > 1) Households(households, s.householdId, enabled = !busy) { id ->
            scope.launch {
                busy = true; message = null
                (graph.accounts.switchHousehold(id) as? ApiResult.Err)?.let { message = it.error.userMessage() }
                busy = false
            }
        }
        message?.let { Text(it, Modifier.announced(), color = MaterialTheme.colorScheme.error) }
        OutlinedButton({
            scope.launch { busy = true; graph.accounts.signOut(); busy = false; onSignedOut() }
        }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text(SIGN_OUT) }
    }
}

/** "Signed in as Name", the address, the household and the roles in it. */
@Composable
fun AccountSummary(a: Account?) {
    val who = a?.name ?: a?.email
    Text(if (who != null) "Signed in as $who" else "Signed in", style = MaterialTheme.typography.bodyLarge)
    if (a?.name != null && a.email != null) Text(a.email, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    a?.householdName?.let { h ->
        Text("Household: $h" + if (a.roles.isNotEmpty()) " · " + roleNames(a.roles) else "", style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun roleNames(roles: List<String>): String = roles.joinToString(", ") { r ->
    when (r) {
        "owner" -> "Owner"; "approver" -> "Approver"; "planner" -> "Planner"; "contributor" -> "Contributor"
        "child" -> "Child"; else -> r.replaceFirstChar(Char::uppercase)
    }
}

@Composable
private fun Households(list: List<MeHousehold>, current: Int?, enabled: Boolean, onPick: (Int) -> Unit) {
    Text("Household", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    Column(Modifier.selectableGroup()) {
        list.forEach { h ->
            val selected = h.id == current
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected, enabled = enabled, role = Role.RadioButton) { if (!selected) onPick(h.id) },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected, onClick = null, enabled = enabled)
                Text(h.name, Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** A signed-in call was refused: the sign-in ended (signed out on another device, or expired). */
@Composable
fun SignInAgainDialog(onSignIn: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Sign in again") },
        text = { Text("Your sign-in on this phone has ended. Sign in again to keep the week, recipes and shopping list up to date.") },
        confirmButton = { TextButton(onSignIn) { Text("Sign in") } },
        dismissButton = { TextButton(onLater) { Text("Not now") } },
    )
}
