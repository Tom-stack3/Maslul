@file:UseSerializers(InstantSerializer::class)

package com.maslul.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant

/**
 * The options a trip planning showed, kept so a recent destination can show them again as they were
 * (from where you were then, rather than from where you are now).
 */
@Serializable
data class PastSearch(
    /** Where the trip was planned from; "where I am" is kept as the spot it was then. */
    val from: Place,
    val to: Place,
    /** When the options were found. */
    val at: Instant,
    /** The time asked for, or null for leaving right then. */
    val time: Instant? = null,
    val arriveBy: Boolean = false,
    val itineraries: List<Itinerary>,
    val walkOnly: Itinerary? = null,
) {
    val key get() = to.key
}

/** The latest [PastSearch] per destination, newest first, at most [max]. */
fun List<PastSearch>.with(s: PastSearch, max: Int = SearchHistory.MAX): List<PastSearch> =
    (listOf(s) + filter { it.key != s.key }).take(max)

/** Past searches, in a file of their own: with every option's stops and map line, they're far bigger than the rest. */
class SearchHistory(context: Context) {
    private val file = File(context.filesDir, "searches_v1.json")
    private val state = MutableStateFlow<List<PastSearch>>(emptyList())
    val searches: StateFlow<List<PastSearch>> = state.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Writes in order, the latest list only. */
    private val writes = Channel<List<PastSearch>>(Channel.CONFLATED)

    init {
        io.launch {
            val loaded = runCatching { AppJson.decodeFromString<List<PastSearch>>(file.readText()) }.getOrNull().orEmpty()
            // Anything saved while loading comes first; a write queued meanwhile is replaced by the merged list.
            state.update { now -> (now + loaded.filter { l -> now.none { it.key == l.key } }).take(MAX) }
            if (writes.tryReceive().isSuccess) writes.trySend(state.value)
            for (list in writes) runCatching { file.writeText(AppJson.encodeToString(list)) }
        }
    }

    /** The last options shown for trips to [to]. */
    fun forPlace(to: Place): PastSearch? = state.value.firstOrNull { it.key == to.key }

    fun save(s: PastSearch) = edit { it.with(s) }

    fun remove(to: Place) = edit { list -> list.filter { it.key != to.key } }

    private fun edit(f: (List<PastSearch>) -> List<PastSearch>) {
        state.update(f)
        writes.trySend(state.value)
    }

    companion object {
        /** As many as the recents kept. */
        const val MAX = 12
    }
}
