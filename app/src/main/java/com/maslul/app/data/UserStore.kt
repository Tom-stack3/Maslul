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

/** Longest walk to or from a stop that route planning allows by default. */
const val DEFAULT_MAX_WALK_MINUTES = 20

@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val walkSpeed: WalkSpeed = WalkSpeed.NORMAL,
    val maxWalkMinutes: Int = DEFAULT_MAX_WALK_MINUTES,
    val modes: Set<TransitMode> = setOf(
        TransitMode.BUS, TransitMode.TRAIN, TransitMode.LIGHT_RAIL,
        TransitMode.METRO, TransitMode.CABLE_CAR, TransitMode.FERRY,
    ),
    val wheelchair: Boolean = false,
    val reminderMinutes: Int = 5,
)

@Serializable
data class SavedTrip(val from: Place, val to: Place, val label: String? = null)

/** Icon a user can pick for a favourite place. */
@Serializable
enum class FavoriteIcon { STAR, HEART, HOME, SCHOOL, GYM, SHOPPING, RESTAURANT, CAFE, HEALTH, PARK, POOL, SPORTS }

/** A saved place with a user-chosen label, e.g. "Gym" or "Mom". */
@Serializable
data class FavoritePlace(
    val place: Place,
    val label: String,
    val icon: FavoriteIcon = FavoriteIcon.STAR,
) {
    val key get() = place.key
}

@Serializable
data class UserData(
    val home: Place? = null,
    val work: Place? = null,
    /** Legacy unlabeled favourites (before labels existed); migrated into [favoritePlaces] on load. */
    val favorites: List<Place> = emptyList(),
    val favoritePlaces: List<FavoritePlace> = emptyList(),
    val recents: List<Place> = emptyList(),
    val favoriteLines: List<LineRoute> = emptyList(),
    val savedTrips: List<SavedTrip> = emptyList(),
    val favoriteStops: List<Place> = emptyList(),
    val settings: Settings = Settings(),
)

/** Moves legacy unlabeled favourites into [UserData.favoritePlaces], labelled with the place name. */
fun UserData.migrated(): UserData {
    if (favorites.isEmpty()) return this
    val known = favoritePlaces.map { it.key }.toSet()
    val moved = favorites.filter { it.key !in known }.distinctBy { it.key }.map { FavoritePlace(it, it.name) }
    return copy(favorites = emptyList(), favoritePlaces = favoritePlaces + moved)
}

fun UserData.favoriteFor(p: Place): FavoritePlace? = favoritePlaces.firstOrNull { it.key == p.key }

/** Adds [p] as a favourite, or updates its label and icon if it is already one (keeping its position). */
fun UserData.withFavorite(p: Place, label: String, icon: FavoriteIcon): UserData {
    val clean = label.trim().ifEmpty { p.name }
    val fav = FavoritePlace(p, clean, icon)
    val i = favoritePlaces.indexOfFirst { it.key == p.key }
    return copy(
        favoritePlaces = if (i < 0) favoritePlaces + fav
        else favoritePlaces.toMutableList().also { it[i] = fav.copy(place = favoritePlaces[i].place) },
    )
}

fun UserData.withoutFavorite(key: String) = copy(favoritePlaces = favoritePlaces.filter { it.key != key })

/** Moves the favourite with [key] by [delta] positions, clamped to the list bounds. */
fun UserData.withFavoriteMoved(key: String, delta: Int): UserData {
    val i = favoritePlaces.indexOfFirst { it.key == key }
    if (i < 0) return this
    val j = (i + delta).coerceIn(0, favoritePlaces.lastIndex)
    if (i == j) return this
    val list = favoritePlaces.toMutableList()
    list.add(j, list.removeAt(i))
    return copy(favoritePlaces = list)
}

/** Small persistent store for the user's places, favourites and settings. */
class UserStore(context: Context) {
    private val prefs = context.getSharedPreferences("maslul", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val data: StateFlow<UserData> = state.asStateFlow()

    private fun load(): UserData = prefs.getString(KEY, null)?.let {
        runCatching { AppJson.decodeFromString<UserData>(it).migrated() }.getOrNull()
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

    fun saveFavorite(p: Place, label: String, icon: FavoriteIcon) = edit { it.withFavorite(p, label, icon) }
    fun removeFavorite(key: String) = edit { it.withoutFavorite(key) }
    fun moveFavorite(key: String, delta: Int) = edit { it.withFavoriteMoved(key, delta) }

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
