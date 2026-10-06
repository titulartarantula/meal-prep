package dev.mealprep.app.ui.card

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.AlarmClock
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.CardStep
import dev.mealprep.app.data.api.CookCard
import java.time.LocalDate

object CardActions {
    fun timerLabel(title: String, step: CardStep) = "$title: ${step.text.take(40)}"

    /** The Clock app keeps time reliably with the screen off — no exact-alarm permission needed here. */
    fun timerIntent(label: String, minutes: Int): Intent = Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
        .putExtra(AlarmClock.EXTRA_MESSAGE, label)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun list(title: String, items: List<String>) =
        if (items.isEmpty()) "" else "<h2>${esc(title)}</h2><ul>${items.joinToString("") { "<li>${esc(it)}</li>" }}</ul>"

    /** "Tue · about 30 min", with the date when the card has one ("Tue Oct 13 · about 30 min"). */
    fun subtitle(c: CookCard): String {
        val date = c.date?.let { runCatching { Weeks.shortDate(LocalDate.parse(it)) }.getOrNull() }
        return listOfNotNull(c.night, date).joinToString(" ") + if (c.totalMinutes > 0) " · about ${c.totalMinutes} min" else ""
    }

    /** One-page card for the printer (Android print dialog). */
    fun html(c: CookCard): String = buildString {
        append("<html><head><meta charset='utf-8'><style>body{font-family:sans-serif;font-size:14pt}h1{margin:0}")
        append("h2{font-size:13pt;margin:12px 0 4px}li{margin:2px 0}</style></head><body>")
        append("<h1>${esc(c.title)}</h1><p>${esc(subtitle(c))}</p>")
        append(list("Last time", c.ratingNotes))
        append(list("Thaw", c.thaw))
        append(list("From your kit", c.kit))
        append(list("On the night", c.dayOf))
        append("<h2>Steps</h2><ol>")
        c.steps.forEach { s -> append("<li>${esc(s.text)}${s.timerMinutes?.let { " <b>(timer $it min)</b>" } ?: ""}</li>") }
        append("</ol></body></html>")
    }

    /**
     * Opens the print dialog for the card. The page is laid out in an off-screen WebView; [keep] holds it until the
     * print job has its content (a WebView that is garbage-collected first prints nothing), then gets null.
     */
    fun print(activity: Context, card: CookCard, keep: (WebView?) -> Unit) {
        val wv = WebView(activity)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                activity.getSystemService(PrintManager::class.java)
                    .print("Cook card – ${card.title}", view.createPrintDocumentAdapter("cook-card-${card.entryId}"), PrintAttributes.Builder().build())
                keep(null)
            }
        }
        keep(wv)
        wv.loadDataWithBaseURL(null, html(card), "text/html", "UTF-8", null)
    }
}
