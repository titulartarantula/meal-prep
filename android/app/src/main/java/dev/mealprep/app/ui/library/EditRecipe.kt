package dev.mealprep.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.ui.common.BackTopBar
import dev.mealprep.app.ui.common.BottomAction
import dev.mealprep.app.ui.common.announced
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val EDIT_RECIPE = "Edit recipe"
const val INGREDIENTS_HELP = "One per line. A line ending in “:” starts a group, like “For the sauce:”."
const val STEPS_HELP = "One step per line."
const val TITLE_NEEDED = "The recipe needs a title."
const val DISCARD_TITLE = "Discard your changes?"

/**
 * What Edit recipe shows, as text fields: the ingredient lines as written (an attached sub-recipe's name is a "Name:"
 * heading over its lines, the line it replaced isn't shown, as on the recipe screen) and one step per line. Numbers
 * are kept as typed until saved. [changes] is the PATCH body: only what differs from the recipe.
 */
data class RecipeDraft(
    val title: String, val servings: String, val ingredients: String, val steps: String, val notes: String,
    val description: String, val prepMinutes: String, val cookMinutes: String, val totalMinutes: String, val yieldText: String,
) {
    /** Field → what's wrong (the field's own message under it); empty = can be saved. */
    fun problems(): Map<String, String> = buildMap {
        if (title.isBlank()) put(TITLE, TITLE_NEEDED)
        if (!wholeNumber(servings, 1, 1000)) put(SERVINGS, "Servings: a whole number from 1 to 1000, or empty.")
        listOf(PREP to prepMinutes, COOK to cookMinutes, TOTAL to totalMinutes).forEach { (k, v) ->
            if (!wholeNumber(v, 0, 10_000)) put(k, "Minutes: a whole number, or empty.")
        }
    }

    /** The PATCH /recipes/{id} body for what was changed (null: nothing was), with the revision it began from. */
    fun changes(r: Recipe): JsonObject? {
        val was = from(r)
        val body = buildJsonObject {
            if (clean(title) != clean(was.title)) put("title", clean(title))
            if (servings.trim() != was.servings) putNullable("servings", servings.trim().toIntOrNull())
            if (lines(ingredients) != lines(was.ingredients)) put("ingredients", JsonArray(lines(ingredients).map(::JsonPrimitive)))
            if (lines(steps) != lines(was.steps)) put("steps", JsonArray(lines(steps).map(::JsonPrimitive)))
            if (notes.trim() != was.notes.trim()) putNullable("notes", notes.trim().ifEmpty { null })
            if (description.trim() != was.description.trim()) putNullable("description", description.trim().ifEmpty { null })
            if (prepMinutes.trim() != was.prepMinutes) putNullable("prep_minutes", prepMinutes.trim().toIntOrNull())
            if (cookMinutes.trim() != was.cookMinutes) putNullable("cook_minutes", cookMinutes.trim().toIntOrNull())
            if (totalMinutes.trim() != was.totalMinutes) putNullable("total_minutes", totalMinutes.trim().toIntOrNull())
            if (clean(yieldText) != clean(was.yieldText)) putNullable("yield_text", clean(yieldText).ifEmpty { null })
        }
        if (body.isEmpty()) return null
        return JsonObject(body + (r.revision?.let { mapOf("expected_revision" to JsonPrimitive(it)) } ?: emptyMap()))
    }

    companion object {
        const val TITLE = "title"; const val SERVINGS = "servings"; const val PREP = "prep"; const val COOK = "cook"
        const val TOTAL = "total"

        fun from(r: Recipe) = RecipeDraft(
            title = r.title, servings = r.servings?.toString().orEmpty(), ingredients = ingredientText(r),
            steps = r.steps.joinToString("\n") { clean(it) }, notes = r.notes.orEmpty(), description = r.description.orEmpty(),
            prepMinutes = r.prepMinutes?.toString().orEmpty(), cookMinutes = r.cookMinutes?.toString().orEmpty(),
            totalMinutes = r.totalMinutes?.toString().orEmpty(), yieldText = r.yieldText.orEmpty(),
        )

        /** The ingredient lines as the recipe screen groups them, but as written (no pretty fractions), so an unchanged
         *  line goes back exactly as it was and keeps what the server knows about it. */
        fun ingredientText(r: Recipe): String {
            val out = mutableListOf<String>()
            var group: String? = null
            r.ingredients.filter { !it.expanded }.forEach { ing ->
                if (ing.subRecipe != group) {
                    group = ing.subRecipe
                    group?.let { out += "$it:" }
                }
                out += clean(ing.raw.ifBlank { ing.name })
            }
            return out.joinToString("\n")
        }

        val saver: Saver<RecipeDraft?, List<String>> = Saver(
            save = { d -> d?.let { listOf(it.title, it.servings, it.ingredients, it.steps, it.notes, it.description,
                it.prepMinutes, it.cookMinutes, it.totalMinutes, it.yieldText) } },
            restore = { l -> RecipeDraft(l[0], l[1], l[2], l[3], l[4], l[5], l[6], l[7], l[8], l[9]) },
        )
    }
}

private fun clean(s: String) = s.trim().replace(Regex("""\s+"""), " ")
private fun lines(s: String) = s.lines().map(::clean).filter { it.isNotEmpty() }
private fun wholeNumber(s: String, min: Int, max: Int) = s.isBlank() || s.trim().toIntOrNull()?.let { it in min..max } == true
private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, v: Int?) = if (v == null) put(key, JsonNull) else put(key, v)
private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, v: String?) = if (v == null) put(key, JsonNull) else put(key, v)

/**
 * Edit recipe, over the recipe screen: Back (or the system back) asks before dropping changes; Save changes keeps the
 * editor open until the server took them, and says why when it didn't (off the home network, changed on another
 * phone). Each field's problem shows under it and is read out.
 */
@Composable
fun EditRecipeScreen(
    r: Recipe, draft: RecipeDraft, onDraft: (RecipeDraft) -> Unit, saving: Boolean, error: String?,
    onSave: () -> Unit, onClose: () -> Unit,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val changed = draft != RecipeDraft.from(r)
    val leave = { if (changed) asking = true else onClose() }
    BackHandler(onBack = leave)
    val problems = if (tried) draft.problems() else emptyMap()
    Column(Modifier.fillMaxSize()) {
        BackTopBar(EDIT_RECIPE, leave, subtitle = r.title)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, Modifier.announced(), color = MaterialTheme.colorScheme.error) }
            Field("Title", draft.title, { onDraft(draft.copy(title = it)) }, problems[RecipeDraft.TITLE], lines = 1)
            Field("Servings (optional)", draft.servings, { onDraft(draft.copy(servings = it)) }, problems[RecipeDraft.SERVINGS], number = true)
            Section("Ingredients")
            Field("Ingredients", draft.ingredients, { onDraft(draft.copy(ingredients = it)) }, null, help = INGREDIENTS_HELP, lines = 6)
            Section("Steps")
            Field("Steps", draft.steps, { onDraft(draft.copy(steps = it)) }, null, help = STEPS_HELP, lines = 6)
            Section("More")
            Field("Notes from the recipe (optional)", draft.notes, { onDraft(draft.copy(notes = it)) }, null, lines = 3)
            Field("Description (optional)", draft.description, { onDraft(draft.copy(description = it)) }, null, lines = 2)
            Field("Prep minutes (optional)", draft.prepMinutes, { onDraft(draft.copy(prepMinutes = it)) }, problems[RecipeDraft.PREP], number = true)
            Field("Cook minutes (optional)", draft.cookMinutes, { onDraft(draft.copy(cookMinutes = it)) }, problems[RecipeDraft.COOK], number = true)
            Field("Total minutes (optional)", draft.totalMinutes, { onDraft(draft.copy(totalMinutes = it)) }, problems[RecipeDraft.TOTAL], number = true)
            Field("Yield (optional), like “Makes 24 cookies”", draft.yieldText, { onDraft(draft.copy(yieldText = it)) }, null, lines = 1)
        }
        // Enabled with a problem too: tapping it then shows what's wrong, next to the field.
        BottomAction("Save changes", { tried = true; if (draft.problems().isEmpty()) onSave() }, enabled = !saving)
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(DISCARD_TITLE) },
        text = { Text("Your changes to this recipe haven't been saved.") },
        confirmButton = { TextButton({ asking = false; onClose() }) { Text("Discard") } },
        dismissButton = { TextButton({ asking = false }) { Text("Keep editing") } },
    )
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })

@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, problem: String?, help: String? = null, lines: Int = 1,
    number: Boolean = false,
) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, isError = problem != null,
        singleLine = number, minLines = if (number) 1 else lines,
        supportingText = (problem ?: help)?.let { t -> { Text(t, if (problem != null) Modifier.announced() else Modifier) } },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number)
            else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
}
