package com.maslul.app

import com.maslul.app.data.AppJson
import com.maslul.app.data.FavoriteIcon
import com.maslul.app.data.Place
import com.maslul.app.data.UserData
import com.maslul.app.data.favoriteFor
import com.maslul.app.data.migrated
import com.maslul.app.data.withFavorite
import com.maslul.app.data.withFavoriteMoved
import com.maslul.app.data.withoutFavorite
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesTest {
    private val gym = Place("Holmes Place", 32.08, 34.78, subtitle = "Tel Aviv")
    private val mom = Place("Herzl 10", 31.25, 34.79)
    private val uni = Place("Tel Aviv University", 32.11, 34.80)

    @Test
    fun legacyJsonWithUnlabeledFavoritesMigratesWithPlaceNameAsLabel() {
        val legacy = """{"home":{"name":"Home st","lat":32.0,"lon":34.8,"kind":"HOME"},
            "favorites":[{"name":"Holmes Place","lat":32.08,"lon":34.78,"subtitle":"Tel Aviv"},
                         {"name":"Herzl 10","lat":31.25,"lon":34.79}],
            "recents":[],"settings":{"reminderMinutes":10}}"""
        val d = AppJson.decodeFromString<UserData>(legacy).migrated()
        assertEquals("Home st", d.home?.name)
        assertEquals(10, d.settings.reminderMinutes)
        assertTrue(d.favorites.isEmpty())
        assertEquals(listOf("Holmes Place", "Herzl 10"), d.favoritePlaces.map { it.label })
        assertEquals(FavoriteIcon.STAR, d.favoritePlaces[0].icon)
        assertEquals("Tel Aviv", d.favoritePlaces[0].place.subtitle)
    }

    @Test
    fun migrationIsIdempotentAndSkipsDuplicates() {
        val d = UserData(favorites = listOf(gym, gym)).withFavorite(gym, "Gym", FavoriteIcon.GYM).migrated()
        assertEquals(1, d.favoritePlaces.size)
        assertEquals("Gym", d.favoritePlaces[0].label)
        assertEquals(d, d.migrated())
    }

    @Test
    fun labelledFavoritesRoundTripThroughJson() {
        val d = UserData().withFavorite(gym, "Gym", FavoriteIcon.GYM).withFavorite(mom, "Mom", FavoriteIcon.HEART)
        val back = AppJson.decodeFromString<UserData>(AppJson.encodeToString(d)).migrated()
        assertEquals(d.favoritePlaces, back.favoritePlaces)
    }

    @Test
    fun savingAgainUpdatesLabelInPlaceAndBlankLabelFallsBackToName() {
        var d = UserData().withFavorite(gym, "Gym", FavoriteIcon.GYM).withFavorite(mom, "Mom", FavoriteIcon.HEART)
        d = d.withFavorite(gym, "  ", FavoriteIcon.STAR)
        assertEquals(listOf("Holmes Place", "Mom"), d.favoritePlaces.map { it.label })
        assertEquals(FavoriteIcon.STAR, d.favoritePlaces[0].icon)
        assertNotNull(d.favoriteFor(gym.copy()))
        d = d.withoutFavorite(gym.key)
        assertNull(d.favoriteFor(gym))
        assertEquals(1, d.favoritePlaces.size)
    }

    @Test
    fun reorderClampsToBounds() {
        var d = UserData().withFavorite(gym, "Gym", FavoriteIcon.GYM)
            .withFavorite(mom, "Mom", FavoriteIcon.HEART).withFavorite(uni, "Uni", FavoriteIcon.SCHOOL)
        d = d.withFavoriteMoved(uni.key, -1)
        assertEquals(listOf("Gym", "Uni", "Mom"), d.favoritePlaces.map { it.label })
        d = d.withFavoriteMoved(gym.key, -1)
        assertEquals(listOf("Gym", "Uni", "Mom"), d.favoritePlaces.map { it.label })
        d = d.withFavoriteMoved(gym.key, 5)
        assertEquals(listOf("Uni", "Mom", "Gym"), d.favoritePlaces.map { it.label })
    }
}
