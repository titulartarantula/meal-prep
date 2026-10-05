package dev.mealprep.app.ui.nav

import java.time.LocalDate
import kotlinx.serialization.Serializable

@Serializable object SetupRoute
@Serializable object SettingsRoute
@Serializable data class HomeRoute(val week: String? = null)
@Serializable object ShareRoute
/** purpose "recipe" = new cookbook recipe; "ref" = the page a line refers to ("…, page 191"). */
@Serializable data class CameraRoute(val purpose: String = "recipe", val recipeId: Int = 0, val forLine: Int = -1)
@Serializable data class ListRoute(val weeks: String)
@Serializable data class DraftRoute(val id: Int)
@Serializable data class LoblawsRoute(val cartId: String)
@Serializable data class PrepRoute(val week: String)
@Serializable data class CardRoute(val entryId: Int)
@Serializable data class RatingRoute(val entryId: Int, val week: String)
@Serializable object LibraryRoute
@Serializable data class RecipeRoute(val id: Int)

/** Deep links carried in notification intents (extra "nav"). */
object Nav {
    const val EXTRA = "nav"
    fun home(week: LocalDate?) = "home/${week ?: ""}"
    fun ref(recipeId: Int, line: Int) = "ref/$recipeId/$line"
    fun rating(entryId: Int, week: LocalDate) = "rating/$entryId/$week"
    fun card(entryId: Int) = "card/$entryId"
    fun draft(id: Int) = "draft/$id"
    fun prep(week: LocalDate) = "prep/$week"

    fun parse(s: String?): Any? {
        val p = s?.split("/") ?: return null
        return runCatching {
            when (p[0]) {
                "home" -> HomeRoute(p.getOrNull(1)?.ifBlank { null }?.also(LocalDate::parse))
                "ref" -> CameraRoute("ref", p[1].toInt(), p[2].toInt())
                "rating" -> RatingRoute(p[1].toInt(), LocalDate.parse(p[2]).toString())
                "card" -> CardRoute(p[1].toInt())
                "draft" -> DraftRoute(p[1].toInt())
                "prep" -> PrepRoute(LocalDate.parse(p[1]).toString())
                else -> null
            }
        }.getOrNull()
    }
}
