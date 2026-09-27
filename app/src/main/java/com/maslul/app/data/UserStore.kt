package com.maslul.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class WalkSpeed(val mps: Double, val label: String) {
    SLOW(1.0, "Relaxed"), NORMAL(1.3, "Normal"), FAST(1.6, "Brisk")
}

@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val walkSpeed: WalkSpeed = WalkSpeed.NORMAL,
    val maxWalkMinutes: Int = 15,
    val modes: Set<TransitMode> = setOf(
        TransitMode.BUS, TransitMode.TRAIN, TransitMode.LIGHT_RAIL,
        TransitMode.METRO, TransitMode.CABLE_CAR, TransitMode.FERRY,
    ),
    val wheelchair: Boolean = false,
    val reminderMinutes: Int = 5,
)

@Serializable
data class SavedTrip(val from: Place, val to: Place, val label: String? = null)

@Serializable
data class UserData(
    val home: Place? = null,
    val work: Place? = null,
    val favorites: List<Place> = emptyList(),
    val recents: List<Place> = emptyList(),
    val favoriteLines: List<LineRoute> = emptyList(),
    val savedTrips: List<SavedTrip> = emptyList(),
    val favoriteStops: List<Place> = emptyList(),
    val settings: Settings = Settings(),
)

/** Small persistent store for the user's places, favourites and settings. */
class UserStore(context: Context) {
    private val prefs = context.getSharedPreferences("maslul", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val data: StateFlow<UserData> = state.asStateFlow()

    private fun load(): UserData = prefs.getString(KEY, null)?.let {
        runCatching { AppJson.decodeFromString<UserData>(it) }.getOrNull()
    } ?: UserData()

    private fun edit(f: (UserData) -> UserData) {
        state.update(f)
        prefs.edit().putString(KEY, AppJson.encodeToString(state.value)).apply()
    }

    fun setHome(p: Place?) = edit { it.copy(home = p?.copy(kind = PlaceKind.HOME)) }
    fun setWork(p: Place?) = edit { it.copy(work = p?.copy(kind = PlaceKind.WORK)) }

    fun addRecent(p: Place) {
        if (p.kind == PlaceKind.CURRENT_LOCATION) return
        edit { d -> d.copy(recents = (listOf(p) + d.recents.filter { it.key != p.key }).take(12)) }
    }

    fun removeRecent(p: Place) = edit { d -> d.copy(recents = d.recents.filter { it.key != p.key }) }

    fun toggleFavorite(p: Place) = edit { d ->
        val exists = d.favorites.any { it.key == p.key }
        d.copy(favorites = if (exists) d.favorites.filter { it.key != p.key } else d.favorites + p)
    }

    fun isFavoriteLine(r: LineRoute) = data.value.favoriteLines.any { it.operatorRef == r.operatorRef && it.mkt == r.mkt }

    fun toggleFavoriteLine(r: LineRoute) = edit { d ->
        val exists = d.favoriteLines.any { it.operatorRef == r.operatorRef && it.mkt == r.mkt }
        d.copy(
            favoriteLines = if (exists) d.favoriteLines.filterNot { it.operatorRef == r.operatorRef && it.mkt == r.mkt }
            else d.favoriteLines + r,
        )
    }

    fun toggleFavoriteStop(p: Place) = edit { d ->
        val exists = d.favoriteStops.any { it.stopId == p.stopId }
        d.copy(favoriteStops = if (exists) d.favoriteStops.filter { it.stopId != p.stopId } else d.favoriteStops + p)
    }

    fun toggleSavedTrip(t: SavedTrip) = edit { d ->
        val exists = d.savedTrips.any { it.from.key == t.from.key && it.to.key == t.to.key }
        d.copy(
            savedTrips = if (exists) d.savedTrips.filterNot { it.from.key == t.from.key && it.to.key == t.to.key }
            else d.savedTrips + t,
        )
    }

    fun updateSettings(f: (Settings) -> Settings) = edit { it.copy(settings = f(it.settings)) }

    private companion object {
        const val KEY = "store_v1"
    }
}
