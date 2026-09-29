package com.maslul.app.ui.nav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maslul.app.data.FavoriteIcon
import com.maslul.app.data.FavoritePlace
import com.maslul.app.data.Place
import com.maslul.app.data.UserStore
import com.maslul.app.data.favoriteFor
import com.maslul.app.ui.theme.LocalExtra

val FavoriteGold = Color(0xFFF5A524)

fun FavoriteIcon.vector(): ImageVector = when (this) {
    FavoriteIcon.STAR -> Icons.Rounded.Star
    FavoriteIcon.HEART -> Icons.Rounded.Favorite
    FavoriteIcon.HOME -> Icons.Rounded.Home
    FavoriteIcon.SCHOOL -> Icons.Rounded.School
    FavoriteIcon.GYM -> Icons.Rounded.FitnessCenter
    FavoriteIcon.SHOPPING -> Icons.Rounded.ShoppingCart
    FavoriteIcon.RESTAURANT -> Icons.Rounded.Restaurant
    FavoriteIcon.CAFE -> Icons.Rounded.LocalCafe
    FavoriteIcon.HEALTH -> Icons.Rounded.LocalHospital
    FavoriteIcon.PARK -> Icons.Rounded.Park
    FavoriteIcon.SEA -> Icons.Rounded.Waves
    FavoriteIcon.SOCCER -> Icons.Rounded.SportsSoccer
}

/** A place being added to (or edited in) the favourites; drives [FavoriteDialog]. */
class FavoriteDraft(val place: Place, val existing: FavoritePlace?) {
    companion object {
        fun of(store: UserStore, p: Place) = FavoriteDraft(p, store.data.value.favoriteFor(p))
    }
}

/** Shows the label/icon editor for [draft] (if any) and writes the result to [store]. */
@Composable
fun FavoriteDialogHost(store: UserStore, draft: FavoriteDraft?, onClose: () -> Unit) {
    if (draft == null) return
    key(draft) {
        FavoriteDialog(
            draft = draft,
            onDismiss = onClose,
            onSave = { label, icon -> store.saveFavorite(draft.place, label, icon); onClose() },
            onDelete = draft.existing?.let { f -> { store.removeFavorite(f.key); onClose() } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavoriteDialog(
    draft: FavoriteDraft,
    onDismiss: () -> Unit,
    onSave: (String, FavoriteIcon) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val initial = draft.existing?.label ?: draft.place.name
    var label by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var icon by remember { mutableStateOf(draft.existing?.icon ?: FavoriteIcon.STAR) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.existing == null) "Save favorite" else "Edit favorite") },
        text = {
            Column {
                Text(
                    listOfNotNull(draft.place.name, draft.place.subtitle).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalExtra.current.subtle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("e.g. Gym, Mom") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(label.text, icon) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("favorite_label"),
                )
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FavoriteIcon.entries.forEach { i -> IconChoice(i, i == icon) { icon = i } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label.text, icon) }, modifier = Modifier.testTag("favorite_save")) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun IconChoice(icon: FavoriteIcon, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) scheme.primaryContainer else scheme.surfaceVariant,
        border = if (selected) BorderStroke(2.dp, scheme.primary) else null,
        modifier = Modifier.size(40.dp).semantics { this.selected = selected },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon.vector(),
                icon.name.lowercase(),
                tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
