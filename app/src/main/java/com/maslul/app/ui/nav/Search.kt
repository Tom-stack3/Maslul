package com.maslul.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.favoriteFor
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.MessageBox
import com.maslul.app.ui.components.PlaceRow
import com.maslul.app.ui.components.SectionHeader
import com.maslul.app.ui.theme.LocalExtra
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SearchField { FROM, TO }

/**
 * Place search. In trip mode it fills From and To and calls [onDone] once both are set;
 * with [single] it returns the first chosen place.
 */
class SearchModel(
    nav: AppNav,
    from: Place?,
    to: Place?,
    editing: SearchField,
    val single: Boolean = false,
    val title: String? = null,
    private val onDone: (Place, Place?) -> Unit,
) : ScreenModel(nav) {
    var from by mutableStateOf(from)
    var to by mutableStateOf(to)
    var editing by mutableStateOf(editing)
    var fromText by mutableStateOf(TextFieldValue(from?.name ?: ""))
    var toText by mutableStateOf(TextFieldValue(to?.name ?: ""))
    var results by mutableStateOf<List<Place>>(emptyList())
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    private var job: Job? = null

    val query: String
        get() {
            val v = if (editing == SearchField.FROM) fromText.text else toText.text
            val chosen = if (editing == SearchField.FROM) from else to
            return if (chosen != null && chosen.name == v) "" else v.trim()
        }

    fun onText(field: SearchField, v: TextFieldValue) {
        if (field == SearchField.FROM) fromText = v else toText = v
        editing = field
        val q = query
        job?.cancel()
        if (q.length < 2) { results = emptyList(); loading = false; return }
        job = scope.launch {
            delay(280)
            loading = true
            failed = false
            val near = location.last.value ?: runCatching { location.current() }.getOrNull()
            runCatching { repo.searchPlaces(q, near) }
                .onSuccess { results = it }
                .onFailure { failed = true }
            loading = false
        }
    }

    fun choose(p: Place) {
        if (single) { onDone(p, null); return }
        if (editing == SearchField.FROM) {
            from = p; fromText = TextFieldValue(p.name, TextRange(p.name.length))
        } else {
            to = p; toText = TextFieldValue(p.name, TextRange(p.name.length))
        }
        results = emptyList()
        val f = from
        val t = to
        if (f != null && t != null) {
            if (t.kind != PlaceKind.CURRENT_LOCATION) store.addRecent(t)
            onDone(f, t)
        } else {
            editing = if (f == null) SearchField.FROM else SearchField.TO
        }
    }

    fun swap() {
        val f = from; from = to; to = f
        val ft = fromText; fromText = toText; toText = ft
    }

    fun pickOnMap() {
        nav.push(PickOnMapModel(nav) { p -> nav.pop(); choose(p) })
    }
}

@Composable
fun SearchScreen(model: SearchModel) {
    val data by model.store.data.collectAsState()
    val fromFocus = remember { FocusRequester() }
    val toFocus = remember { FocusRequester() }
    var draft by remember { mutableStateOf<FavoriteDraft?>(null) }
    LaunchedEffect(Unit) {
        runCatching { if (model.editing == SearchField.FROM) fromFocus.requestFocus() else toFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Column(Modifier.statusBarsPadding().padding(bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    IconButton(onClick = { model.nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    if (model.single || model.title != null) {
                        Text(model.title ?: "Search", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        if (!model.single) {
                            Field(
                                value = model.fromText,
                                placeholder = "Starting point",
                                dotColor = MaterialTheme.colorScheme.onSurface,
                                hollow = true,
                                active = model.editing == SearchField.FROM,
                                focus = fromFocus,
                                tag = "from",
                                onFocus = { model.editing = SearchField.FROM },
                                onChange = { model.onText(SearchField.FROM, it) },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Field(
                            value = model.toText,
                            placeholder = if (model.single) "Search address or place" else "Where to?",
                            dotColor = MaterialTheme.colorScheme.primary,
                            hollow = false,
                            active = model.editing == SearchField.TO,
                            focus = toFocus,
                            tag = "to",
                            onFocus = { model.editing = SearchField.TO },
                            onChange = { model.onText(SearchField.TO, it) },
                        )
                    }
                    if (!model.single) {
                        IconButton(onClick = model::swap) { Icon(Icons.Rounded.SwapVert, "Swap") }
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                }
            }
        }
        if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            val q = model.query
            if (q.length >= 2) {
                if (model.failed) item { MessageBox("Couldn't search", body = "Check your connection and try again.") }
                else if (!model.loading && model.results.isEmpty()) {
                    item { MessageBox("No results", body = "Try a street, city or place name — Hebrew or English.", icon = Icons.Rounded.Place) }
                }
                items(model.results, key = { "r" + it.key + it.kind }) { p ->
                    PlaceRow(p.name, p.subtitle, icon = kindIcon(p.kind), iconTint = kindTint(p.kind),
                        trailing = if (model.single) null else ({ FavoriteStar(data.favoriteFor(p) != null) { draft = FavoriteDraft.of(model.store, p) } }),
                        onLongClick = { draft = FavoriteDraft.of(model.store, p) },
                    ) { model.choose(p) }
                }
            } else {
                if (!model.single) {
                    item {
                        PlaceRow("Current location", null, icon = Icons.Rounded.MyLocation, iconTint = MaterialTheme.colorScheme.primary) {
                            model.choose(CurrentLocation)
                        }
                    }
                }
                item { PlaceRow("Choose on map", null, icon = Icons.Rounded.Map) { model.pickOnMap() } }
                if (!model.single) {
                    data.home?.let { h -> item { PlaceRow("Home", h.name, icon = Icons.Rounded.Home) { model.choose(h) } } }
                    data.work?.let { w -> item { PlaceRow("Work", w.name, icon = Icons.Rounded.Work) { model.choose(w) } } }
                }
                if (data.favoritePlaces.isNotEmpty()) item { SectionHeader("Favorites") }
                items(data.favoritePlaces, key = { "f" + it.key }) { f ->
                    PlaceRow(f.label, if (f.label == f.place.name) f.place.subtitle else f.place.name,
                        icon = f.icon.vector(), iconTint = MaterialTheme.colorScheme.primary,
                        onLongClick = { draft = FavoriteDraft.of(model.store, f.place) },
                    ) { model.choose(f.place) }
                }
                if (data.recents.isNotEmpty()) item { SectionHeader("Recent") }
                items(data.recents, key = { "h" + it.key }) { p ->
                    PlaceRow(p.name, p.subtitle, icon = Icons.Rounded.History,
                        trailing = if (model.single) null else ({ FavoriteStar(data.favoriteFor(p) != null) { draft = FavoriteDraft.of(model.store, p) } }),
                        onLongClick = { draft = FavoriteDraft.of(model.store, p) },
                    ) { model.choose(p) }
                }
            }
        }
    }
    FavoriteDialogHost(model.store, draft) { draft = null }
}

@Composable
private fun FavoriteStar(saved: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            if (saved) "Edit favorite" else "Save as favorite",
            tint = if (saved) FavoriteGold else LocalExtra.current.subtle,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun Field(
    value: TextFieldValue,
    placeholder: String,
    dotColor: androidx.compose.ui.graphics.Color,
    hollow: Boolean,
    active: Boolean,
    focus: FocusRequester,
    tag: String,
    onFocus: () -> Unit,
    onChange: (TextFieldValue) -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceVariant
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 14.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor), contentAlignment = Alignment.Center) {
            if (hollow) Box(Modifier.size(5.dp).clip(CircleShape).background(bg))
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.text.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = LocalExtra.current.subtle)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("field_$tag")
                    .onFocusChanged { if (it.isFocused) onFocus() },
            )
        }
        if (active && value.text.isNotEmpty()) {
            IconButton(onClick = { onChange(TextFieldValue("")) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Close, "Clear", modifier = Modifier.size(18.dp), tint = LocalExtra.current.subtle)
            }
        }
    }
}

fun kindIcon(k: PlaceKind): ImageVector = when (k) {
    PlaceKind.STOP -> Icons.Rounded.DirectionsBus
    PlaceKind.ADDRESS -> Icons.Rounded.LocationOn
    PlaceKind.HOME -> Icons.Rounded.Home
    PlaceKind.WORK -> Icons.Rounded.Work
    PlaceKind.CURRENT_LOCATION -> Icons.Rounded.MyLocation
    PlaceKind.POI -> Icons.Rounded.Business
    PlaceKind.PIN -> Icons.Rounded.Place
}

@Composable
fun kindTint(k: PlaceKind) = when (k) {
    PlaceKind.STOP -> com.maslul.app.ui.theme.ModeColors.Bus
    PlaceKind.CURRENT_LOCATION -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
