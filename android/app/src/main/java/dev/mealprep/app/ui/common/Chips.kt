package dev.mealprep.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.mealprep.app.R
import dev.mealprep.app.ui.theme.GardenAccent

/**
 * The app's chip for a choice: selected = check mark + terracotta fill + a thicker edge, so it shows without colour
 * (grayscale, low vision). One choice of several (the default) is a radio button for TalkBack, so put the group in
 * `Modifier.selectableGroup()`; [toggle] = an on/off chip on its own (a checkbox, Material's FilterChip role).
 * [description] replaces the label for TalkBack ("4 out of 5").
 */
@Composable
fun GardenChip(
    selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier,
    toggle: Boolean = false, description: String? = null,
) {
    // Outer semantics win over FilterChip's own Role.Checkbox (same layout node, applied last).
    FilterChip(selected, onClick, { Text(label) },
        modifier = modifier.semantics {
            role = if (toggle) Role.Checkbox else Role.RadioButton
            description?.let { contentDescription = it }
        },
        leadingIcon = if (selected) {
            { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
        colors = GardenAccent.chipColors(),
        border = GardenAccent.chipBorder(selected))
}
