package com.maslul.app.ui.simple

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maslul.app.data.Place
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One big search box; picking a result calls [onPick]. [title] null asks "Where to?". */
class SimpleSearchModel(nav: AppNav, val title: String?, private val onPick: (Place) -> Unit) : ScreenModel(nav) {
    var text by mutableStateOf("")
        private set
    var results by mutableStateOf<List<Place>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun onText(v: String) {
        text = v
        val q = v.trim()
        job?.cancel()
        failed = false
        if (q.length < 2) { results = emptyList(); loading = false; return }
        job = scope.launch {
            delay(350)
            loading = true
            val near = location.last.value ?: runCatching { location.current() }.getOrNull()
            runCatching { repo.searchPlaces(q, near) }
                .onSuccess { results = it }
                .onFailure { failed = true }
            loading = false
        }
    }

    fun pick(p: Place) = onPick(p)
}

@Composable
fun SimpleSearchScreen(model: SimpleSearchModel) = SimpleFrame {
    val s = LocalSimpleStrings.current
    val data by model.store.data.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().imePadding()) {
        SimpleTopBar(model.title ?: s.searchTitle, onBack = { model.nav.pop() })
        OutlinedTextField(
            value = model.text,
            onValueChange = model::onText,
            singleLine = true,
            textStyle = TextStyle(fontSize = 22.sp),
            placeholder = { Text(s.searchHint, fontSize = 20.sp) },
            trailingIcon = { if (model.loading) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp) },
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus).testTag("simple_search_field"),
        )
        Spacer(Modifier.height(8.dp))
        val searching = model.text.trim().length >= 2
        LazyColumn(Modifier.fillMaxSize().testTag("simple_search_results")) {
            if (searching) {
                when {
                    model.failed -> item { Message(s.searchFailed) }
                    !model.loading && model.results.isEmpty() -> item { Message(s.noResults) }
                }
                items(model.results, key = { "r" + it.key + it.kind }) { p ->
                    ResultRow(p.name, p.subtitle, Icons.Rounded.LocationOn) { model.pick(p) }
                }
            } else if (data.recents.isNotEmpty()) {
                item {
                    Text(s.recent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                items(data.recents.take(6), key = { "h" + it.key }) { p ->
                    ResultRow(p.name, p.subtitle, Icons.Rounded.History) { model.pick(p) }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
}

@Composable
private fun ResultRow(title: String, subtitle: String?, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
}
