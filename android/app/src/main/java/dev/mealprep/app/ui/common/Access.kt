package dev.mealprep.app.ui.common

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the person using the app may do, from GET /auth/me's permissions (the server's roles; it refuses the rest with
 * 403 anyway). Actions they can't take are hidden or disabled, as on the web. null = not known yet (first start,
 * offline before the first answer): nothing is hidden, and the server still decides.
 */
data class Access(val permissions: Set<String>? = null, /** Your member id (your own ratings say "you"). */ val me: Int? = null) {
    fun can(permission: String): Boolean = permissions == null || permission in permissions
    val canEditPlan get() = can(Perm.PLAN_EDIT)
    val canEditRecipes get() = can(Perm.RECIPE_EDIT)
    val canBuildCart get() = can(Perm.CART_BUILD)
    val canSendCart get() = can(Perm.CART_SEND)
}

/** The server's permission names (server/mealprep/rbac.py). */
object Perm {
    const val PLAN_EDIT = "plan.edit"
    const val RECIPE_EDIT = "recipe.edit"
    const val CART_BUILD = "cart.build"
    const val CART_SEND = "cart.send"
    const val FRIDGE_EDIT = "fridge.edit"
    const val RATE_SELF = "rate.self"
    const val RATE_FOR_CHILDREN = "rate.for_children"
    const val RECIPE_COMPANY = "recipe.company"
}

/** Provided once at the top (MainActivity) from the saved account; screens read it to hide what isn't theirs to do. */
val LocalAccess = staticCompositionLocalOf { Access() }

/** Why an action is missing, in one line under where it would be. */
object AccessText {
    const val CART = "Building and sending the cart is for the household's shoppers."
    const val SEND = "Sending the cart to Loblaws is for the household's approver."
    const val PLAN = "Planning the week is for the household's planners."
}
