package com.maslul.app.ui.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maslul.app.data.SimpleButton
import com.maslul.app.data.SimpleIcon
import com.maslul.app.data.AppLanguage
import com.maslul.app.data.languageOrDefault
import com.maslul.app.data.simpleSlots
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.Tab

class SimpleSettingsModel(nav: AppNav) : ScreenModel(nav) {
    /** The button being edited and its unsaved changes; kept here so picking an address doesn't lose them. */
    var editing by mutableStateOf<Int?>(null)
    var draft by mutableStateOf<SimpleButton?>(null)

    fun edit(index: Int) {
        editing = index
        draft = store.data.value.simpleSlots().getOrNull(index)
    }

    fun pickAddress(title: String) {
        nav.push(SimpleSearchModel(nav, title) { p ->
            draft = draft?.copy(place = p)
            nav.pop()
        })
    }

    fun save() {
        val i = editing ?: return
        draft?.let { store.setSimpleButton(i, it) }
        close()
    }

    fun close() {
        editing = null
        draft = null
    }

    fun setLanguage(l: AppLanguage) = store.updateSettings { it.copy(language = l) }

    fun fullApp() {
        store.updateSettings { it.copy(simpleMode = false) }
        nav.selectTab(Tab.NAVIGATE)
    }
}

@Composable
fun SimpleSettingsScreen(model: SimpleSettingsModel) = SimpleFrame {
    val s = LocalSimpleStrings.current
    val data by model.store.data.collectAsState()
    val lang = data.settings.languageOrDefault()
    var confirmFull by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        SimpleTopBar(s.settings, onBack = { model.nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
            Header(s.language)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { l ->
                    val on = l == lang
                    Surface(
                        onClick = { model.setLanguage(l) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).testTag("simple_lang_${l.code}"),
                    ) {
                        Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            if (on) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(l.label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Header(s.buttons)
            Text(s.editHint, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            data.simpleSlots().forEachIndexed { i, b ->
                Surface(
                    onClick = { model.edit(i) },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("simple_edit_$i"),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(slotColor(i)), contentAlignment = Alignment.Center) {
                            Icon(b.icon.vector(), null, Modifier.size(28.dp), tint = Color.White)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.title(s), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            Text(b.place?.withCity ?: s.notSet, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            OutlinedButton(onClick = { confirmFull = true }, modifier = Modifier.fillMaxWidth().height(60.dp).testTag("simple_full_app")) {
                Icon(Icons.Rounded.Apps, null)
                Spacer(Modifier.width(8.dp))
                Text(s.fullApp, fontSize = 19.sp)
            }
        }
    }

    if (confirmFull) {
        AlertDialog(
            onDismissRequest = { confirmFull = false },
            title = { Text(s.fullApp) },
            text = { Text(s.fullAppBody, fontSize = 18.sp) },
            confirmButton = { TextButton(onClick = { confirmFull = false; model.fullApp() }) { Text(s.fullAppConfirm, fontSize = 18.sp) } },
            dismissButton = { TextButton(onClick = { confirmFull = false }) { Text(s.cancel, fontSize = 18.sp) } },
        )
    }

    val index = model.editing
    val draft = model.draft
    if (index != null && draft != null) EditDialog(model, index, draft)
}

@Composable
private fun Header(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun EditDialog(model: SimpleSettingsModel, index: Int, draft: SimpleButton) {
    val s = LocalSimpleStrings.current
    AlertDialog(
        onDismissRequest = model::close,
        title = { Text(draft.title(s)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { model.draft = draft.copy(label = it) },
                    label = { Text(s.name) },
                    placeholder = { Text(s.iconName(draft.icon)) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 20.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Text(s.picture, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SimpleIcon.entries.forEach { ic -> IconChoice(ic, ic == draft.icon, s.iconName(ic)) { model.draft = draft.copy(icon = ic) } }
                }
                Spacer(Modifier.height(14.dp))
                Text(s.address, fontWeight = FontWeight.SemiBold)
                Text(draft.place?.withCity ?: s.notSet, fontSize = 18.sp)
                Row {
                    TextButton(onClick = { model.pickAddress(s.whereIs(draft.title(s))) }) { Text(s.changeAddress, fontSize = 17.sp) }
                    if (draft.place != null) {
                        TextButton(onClick = { model.draft = draft.copy(place = null) }) { Text(s.clear, fontSize = 17.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = model::save) { Text(s.save, fontSize = 18.sp) } },
        dismissButton = { TextButton(onClick = model::close) { Text(s.cancel, fontSize = 18.sp) } },
    )
}

/** One picture to choose, styled like the full app's favourite icons but a size larger. */
@Composable
private fun IconChoice(icon: SimpleIcon, selected: Boolean, name: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) scheme.primaryContainer else scheme.surfaceVariant,
        border = if (selected) BorderStroke(2.dp, scheme.primary) else null,
        modifier = Modifier.size(48.dp).semantics { this.selected = selected },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon.vector(), name, Modifier.size(26.dp),
                tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
        }
    }
}
