package dev.mealprep.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dev.mealprep.app.MainActivity
import dev.mealprep.app.R
import dev.mealprep.app.core.RatingText
import dev.mealprep.app.core.firstMissingRef
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.data.api.ShareResult
import dev.mealprep.app.ui.camera.refPrompt
import dev.mealprep.app.ui.nav.Nav
import dev.mealprep.app.work.ImportWorker
import dev.mealprep.app.work.JobWatchWorker
import java.time.LocalDate
import java.util.UUID

class Notifier(private val context: Context) {
    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_JOBS = "jobs"
        const val CH_REMINDERS = "reminders"
    }

    internal fun failedText(message: String, retrySafe: Boolean, kind: String? = ImportWorker.LINK): String = sentences(
        message, when {
            retrySafe -> "Open Meal Prep to try again."
            kind == ImportWorker.PAGES -> "Check the shopping list for its ingredients before adding it again."
            else -> "It may have been saved already — check Recipes."
        })

    private val nm = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        nm.createNotificationChannelsCompat(listOf(
            NotificationChannelCompat.Builder(CH_PROGRESS, NotificationManagerCompat.IMPORTANCE_LOW).setName("Working in the background").build(),
            NotificationChannelCompat.Builder(CH_JOBS, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName("Recipes, cart and prep plan").build(),
            NotificationChannelCompat.Builder(CH_REMINDERS, NotificationManagerCompat.IMPORTANCE_HIGH).setName("Reminders").build(),
        ))
    }

    fun importProgress(workId: UUID, text: String): ForegroundInfo = ForegroundInfo(
        workId.hashCode(),
        builder(CH_PROGRESS, "Meal Prep", text).setOngoing(true).setProgress(0, 0, true).build(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    /** Saved to Recipes: tap opens the recipe (or the camera for a missing page); "Add to a week…" opens the week
     *  picker. A job from 0.4.1 and earlier that also went into a week opens that week instead. */
    fun imported(r: ShareResult) {
        val week = r.entry?.let { LocalDate.parse(it.week) }
        val missing = r.recipe.firstMissingRef()
        val text = listOfNotNull(
            week?.let { "Week of ${Weeks.shortDate(it)}" },
            RatingText.summary(r.recipe.ratings),
            missing?.let { (_, page) -> "Uses page $page — tap to add a photo of it" },
        ).joinToString(" · ").ifEmpty { "It's in Recipes. Add it to a week when you plan." }
        val open = when {
            missing != null -> Nav.ref(r.recipe.id, missing.first, missing.second)
            week != null -> Nav.home(week)
            else -> Nav.recipe(r.recipe.id)
        }
        post("import-${r.recipe.id}", CH_JOBS, dev.mealprep.app.ui.home.doneTitle(r.recipe.title, r.existing), text, open,
            actions = if (week == null) listOf("Add to a week…" to Nav.recipe(r.recipe.id, addToWeek = true)) else emptyList())
    }

    /** A referenced page was read and its ingredients added; offers the next missing page, if any. */
    fun pagesAttached(recipe: Recipe) {
        val next = refPrompt(recipe)
        val text = next?.let { "It also uses page ${it.page} (“${it.raw}”) — tap to add that one too." }
            ?: "Its ingredients are on the shopping list now."
        post("pages-${recipe.id}", CH_JOBS, "Added the page to ${recipe.title}", text,
            next?.let { Nav.ref(recipe.id, it.line, it.page) } ?: Nav.home(null))
    }

    /** [retrySafe] false: the request may have reached the server, so warn instead of inviting a retry. */
    fun importFailed(workId: UUID, message: String, retrySafe: Boolean, kind: String? = ImportWorker.LINK) =
        post("import-failed-$workId", CH_JOBS, if (kind == ImportWorker.PAGES) "Couldn't add the page" else "Couldn't add the recipe",
            failedText(message, retrySafe, kind), Nav.home(null))

    /** A cart draft or prep plan left "building" (JobWatchWorker). [error] is the server's reason when it failed. */
    fun jobDone(kind: String, id: Int, week: LocalDate, ok: Boolean, error: String?) {
        val draft = kind == JobWatchWorker.DRAFT
        val title = when {
            draft && ok -> "Cart ready to review"
            draft -> "Cart couldn't be built"
            ok -> "Prep plan ready"
            else -> "Prep plan failed"
        }
        val text = when {
            !ok -> error?.takeIf(String::isNotBlank) ?: "Open Meal Prep to try again."
            draft -> "Week of ${Weeks.shortDate(week)}: check the picks, then Send to Loblaws."
            else -> "Week of ${Weeks.shortDate(week)}: your Sunday checklist and cook cards are in."
        }
        post("$kind-$id", CH_JOBS, title, text, if (draft) Nav.draft(id) else Nav.prep(week))
    }

    /** The weekly staples reminder (StaplesReminderWorker): opens the shopping list. */
    fun staplesReminder() = post("staples-reminder", CH_REMINDERS, "Time to check the staples",
        "Untick what you don't need this week, then build the cart.", Nav.list())

    /** [actions]: extra buttons, label → deep link. */
    @SuppressLint("MissingPermission")
    fun post(tag: String, channel: String, title: String, text: String, nav: String?, actions: List<Pair<String, String>> = emptyList()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = tag.hashCode()
        val b = builder(channel, title, text).setContentIntent(openIntent(nav, id)).setAutoCancel(true)
        actions.forEach { (label, link) -> b.addAction(0, label, openIntent(link, "$tag/$label".hashCode())) }
        nm.notify(id, b.build())
    }

    private fun builder(channel: String, title: String, text: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))

    private fun openIntent(nav: String?, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).putExtra(Nav.EXTRA, nav)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Joins sentences with a space, ending each one that lacks it with a period ("no NYT Cooking link found" + …). */
internal fun sentences(vararg parts: String): String = parts.map(String::trim).filter(String::isNotEmpty)
    .joinToString(" ") { if (it.last() in ".?!…") it else "$it." }
