package com.maslul.app

import com.maslul.app.data.AppJson
import com.maslul.app.data.AppLanguage
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.ShuttleSchedule
import com.maslul.app.data.StopCall
import com.maslul.app.data.UserData
import com.maslul.app.i18n.L10n
import com.maslul.app.i18n.S
import com.maslul.app.i18n.Strings
import com.maslul.app.ui.components.Fmt
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class LocalizationTest {
    @After
    fun backToEnglish() = L10n.use(AppLanguage.EN)

    @Test
    fun languagePickedForSimpleMaslulCarriesOver() {
        // 1.2.0 saved Simple Maslul's language under "simpleLanguage"; it's now the whole app's.
        val old = """{"settings":{"simpleMode":true,"simpleLanguage":"RU"}}"""
        assertEquals(AppLanguage.RU, AppJson.decodeFromString<UserData>(old).settings.language)
        val d = UserData().let { it.copy(settings = it.settings.copy(language = AppLanguage.HE)) }
        assertTrue(AppJson.encodeToString(d).contains(""""simpleLanguage":"HE""""))
    }

    @Test
    fun switchingLanguageSwitchesEveryWord() {
        L10n.use(AppLanguage.HE)
        assertEquals("לאן?", S.whereTo)
        assertEquals("עכשיו", Fmt.relative(java.time.Instant.EPOCH, java.time.Instant.EPOCH))
        L10n.use(AppLanguage.RU)
        assertEquals("Куда?", S.whereTo)
        assertEquals("1 ч 05 мин", Fmt.duration(65 * 60))
        L10n.use(AppLanguage.EN)
        assertEquals("1 h 05 min", Fmt.duration(65 * 60))
        assertEquals("2 h", Fmt.duration(120 * 60))
    }

    @Test
    fun everyLanguageSaysSomethingForEverything() {
        // Each string, called through its getter or with sample arguments, is non-blank in every language.
        val samples = mapOf<Class<*>, Any?>(
            String::class.java to "X", Int::class.javaPrimitiveType!! to 3, Long::class.javaPrimitiveType!! to 3L,
            com.maslul.app.data.TransitMode::class.java to com.maslul.app.data.TransitMode.BUS,
            com.maslul.app.data.WalkSpeed::class.java to com.maslul.app.data.WalkSpeed.NORMAL,
            com.maslul.app.data.RailPreference::class.java to com.maslul.app.data.RailPreference.PREFER,
            com.maslul.app.data.ThemeMode::class.java to com.maslul.app.data.ThemeMode.DARK,
            DayOfWeek::class.java to DayOfWeek.FRIDAY, LocalDate::class.java to LocalDate.of(2026, 10, 9),
            List::class.java to listOf("a", "b"),
        )
        val methods = Strings::class.java.declaredMethods.filter { java.lang.reflect.Modifier.isAbstract(it.modifiers) }
        assertTrue(methods.size > 300)
        val english = L10n.of(AppLanguage.EN)
        AppLanguage.entries.forEach { lang ->
            val s = L10n.of(lang)
            methods.forEach { m ->
                val args = m.parameterTypes.map { samples.getValue(it) }.toTypedArray()
                val text = m.invoke(s, *args) as String
                assertTrue("${lang.name} ${m.name}", text.isNotBlank())
                // Untranslated English would read the same (names and numbers aside).
                if (lang != AppLanguage.EN && text.any { it in 'a'..'z' } && m.name !in sameInEveryLanguage) {
                    assertNotEquals("${lang.name} ${m.name} is still English", m.invoke(english, *args), text)
                }
            }
        }
    }

    /** Strings that are the same in every language: symbols and names. */
    private val sameInEveryLanguage = setOf("getArrow", "meters", "km")

    @Test
    fun hebrewAndRussianPlurals() {
        val he = L10n.of(AppLanguage.HE)
        val ru = L10n.of(AppLanguage.RU)
        assertEquals("ישיר", he.transfers(0))
        assertEquals("החלפה אחת", he.transfers(1))
        assertEquals("2 החלפות", he.transfers(2))
        assertEquals("1 пересадка", ru.transfers(1))
        assertEquals("3 пересадки", ru.transfers(3))
        assertEquals("5 пересадок", ru.transfers(5))
        assertEquals("21 остановка", ru.rideStops(21))
        assertEquals("в 1 остановке отсюда", ru.stopsAway(1))
        assertEquals("в 2 остановках отсюда", ru.stopsAway(2))
    }

    @Test
    fun dataWordsFollowTheLanguage() {
        val stop = StopCall("s", "S", null, 32.0, 34.8, null, null, track = "3", floor = "6")
        assertEquals("Platform 3, floor 6", stop.platformLabel)
        L10n.use(AppLanguage.HE)
        assertEquals("רציף 3, קומה 6", stop.platformLabel)
        assertEquals("א׳–ה׳", ShuttleSchedule.formatDays(com.maslul.app.data.Shuttle.WORK_WEEK))
        assertEquals("עוד חסרים: שם ושעות יציאה.", ShuttleSchedule.missingText(listOf(S.needName, S.needTimes)))
        L10n.use(AppLanguage.RU)
        assertEquals("Вс, Пт", ShuttleSchedule.formatDays(setOf(5, 7)))
    }

    @Test
    fun currentLocationIsTheSamePlaceInAnyLanguage() {
        val en = Place(L10n.of(AppLanguage.EN).currentLocation, 0.0, 0.0, kind = PlaceKind.CURRENT_LOCATION)
        val he = Place(L10n.of(AppLanguage.HE).currentLocation, 0.0, 0.0, kind = PlaceKind.CURRENT_LOCATION)
        assertEquals(en.key, he.key)
        // Saved trips and favourites still key by name and spot.
        assertNotEquals(Place("A", 32.0, 34.8).key, Place("B", 32.0, 34.8).key)
    }
}
