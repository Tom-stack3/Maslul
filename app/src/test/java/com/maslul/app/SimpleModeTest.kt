package com.maslul.app

import com.maslul.app.data.AppJson
import com.maslul.app.data.DefaultSimpleButtons
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.SimpleButton
import com.maslul.app.data.SimpleIcon
import com.maslul.app.data.SimpleLanguage
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import com.maslul.app.data.UserData
import com.maslul.app.data.simpleSlots
import com.maslul.app.data.withSimpleButton
import com.maslul.app.ui.simple.SimpleStep
import com.maslul.app.ui.simple.SimpleStrings
import com.maslul.app.ui.simple.simpleSteps
import com.maslul.app.ui.simple.title
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SimpleModeTest {
    private val home = Place("Herzl 10", 31.25, 34.79, kind = PlaceKind.HOME)
    private val kids = Place("Weizmann 3", 32.08, 34.78)

    @Test
    fun phoneLanguagePicksSimpleLanguage() {
        assertEquals(SimpleLanguage.HE, SimpleLanguage.forLocale("iw"))
        assertEquals(SimpleLanguage.HE, SimpleLanguage.forLocale("he-IL"))
        assertEquals(SimpleLanguage.RU, SimpleLanguage.forLocale("ru_RU"))
        assertEquals(SimpleLanguage.EN, SimpleLanguage.forLocale("fr"))
        assertTrue(SimpleLanguage.HE.rtl)
        assertFalse(SimpleLanguage.RU.rtl)
    }

    @Test
    fun russianPluralForms() {
        val p = { n: Int -> SimpleStrings.ruPlural(n, "остановка", "остановки", "остановок") }
        assertEquals("остановка", p(1))
        assertEquals("остановки", p(3))
        assertEquals("остановок", p(5))
        assertEquals("остановок", p(11))
        assertEquals("остановок", p(14))
        assertEquals("остановка", p(21))
        assertEquals("остановки", p(22))
    }

    @Test
    fun defaultButtonsHaveNamesInEveryLanguage() {
        assertEquals(5, DefaultSimpleButtons.size)
        SimpleLanguage.entries.forEach { l ->
            val s = SimpleStrings.of(l)
            DefaultSimpleButtons.forEach { assertTrue(it.title(s).isNotBlank()) }
        }
        assertEquals("הנכדים", DefaultSimpleButtons[1].title(SimpleStrings.of(SimpleLanguage.HE)))
        assertEquals("Mom", SimpleButton(SimpleIcon.HEART, label = " Mom ").title(SimpleStrings.of(SimpleLanguage.EN)))
    }

    @Test
    fun homeAndWorkButtonsFallBackToSavedPlaces() {
        val d = UserData(home = home)
        val slots = d.simpleSlots()
        assertEquals(home, slots[0].place)
        assertNull(slots[1].place)
        assertNull(slots[2].place)
        val set = d.withSimpleButton(1, d.simpleButtons[1].copy(place = kids)).simpleSlots()
        assertEquals(kids, set[1].place)
        // Out of range is ignored.
        assertEquals(d, d.withSimpleButton(9, SimpleButton(SimpleIcon.STAR)))
    }

    @Test
    fun oldSavedDataLoadsWithSimpleModeOff() {
        val old = """{"recents":[],"settings":{"reminderMinutes":10}}"""
        val d = AppJson.decodeFromString<UserData>(old)
        assertFalse(d.settings.simpleMode)
        assertNull(d.settings.simpleLanguage)
        assertEquals(DefaultSimpleButtons, d.simpleButtons)
        val changed = d.copy(settings = d.settings.copy(simpleMode = true, simpleLanguage = SimpleLanguage.RU))
            .withSimpleButton(1, SimpleButton(SimpleIcon.FAMILY, "Дети", kids))
        assertEquals(changed, AppJson.decodeFromString<UserData>(AppJson.encodeToString(changed)))
    }

    private val t0 = Instant.parse("2026-10-09T08:00:00Z")
    private fun at(min: Int) = t0.plusSeconds(min * 60L)
    private fun stop(name: String, track: String? = null) = StopCall("s$name", name, null, 32.0, 34.8, null, null, track = track)
    private fun leg(mode: TransitMode, from: StopCall, to: StopCall, start: Int, end: Int, line: String? = null,
                    stops: Int = 0, alternatives: List<Leg> = emptyList()) = Leg(
        mode, from, to, at(start), at(end), null, "Central Station", line, null, null, null, null,
        line?.let { "trip-$it-$start" }, null, List(stops) { stop("mid$it") }, emptyList(), alternatives = alternatives,
    )

    @Test
    fun stepsWalkRideAndArrive() {
        val other = leg(TransitMode.BUS, stop("A"), stop("B"), 8, 20, "22")
        val itin = Itinerary(
            "x", at(0), at(28), 1,
            listOf(
                leg(TransitMode.WALK, stop("here"), stop("A"), 0, 5),
                leg(TransitMode.BUS, stop("A", track = "3"), stop("B"), 5, 15, "18", stops = 6, alternatives = listOf(other)),
                // Crossing to the platform: too short to mention.
                leg(TransitMode.WALK, stop("B"), stop("C"), 15, 15),
                leg(TransitMode.TRAIN, stop("C"), stop("D"), 18, 25, null, stops = 0),
                leg(TransitMode.WALK, stop("D"), stop("there"), 25, 28),
            ),
        )
        val steps = simpleSteps(itin)
        assertEquals(5, steps.size)
        assertEquals(SimpleStep.WalkToStop("A", 5), steps[0])
        val bus = steps[1] as SimpleStep.Ride
        assertEquals("18", bus.line)
        assertEquals(listOf("22"), bus.others)
        assertEquals("3", bus.platform)
        assertEquals(7, bus.stops)
        assertEquals("B", bus.getOff)
        val train = steps[2] as SimpleStep.Ride
        assertEquals("", train.line)
        assertEquals(1, train.stops)
        assertEquals(SimpleStep.WalkToEnd(3), steps[3])
        assertEquals(SimpleStep.Arrive(at(28)), steps[4])
    }

    @Test
    fun rideWordingInEachLanguage() {
        assertEquals("Take bus 18", SimpleStrings.of(SimpleLanguage.EN).take(TransitMode.BUS, "18"))
        assertEquals("Take train", SimpleStrings.of(SimpleLanguage.EN).take(TransitMode.TRAIN, ""))
        assertEquals("לעלות על אוטובוס 18", SimpleStrings.of(SimpleLanguage.HE).take(TransitMode.BUS, "18"))
        assertEquals("Проедьте 3 остановки", SimpleStrings.of(SimpleLanguage.RU).rideStops(3))
        assertEquals("2 пересадки", SimpleStrings.of(SimpleLanguage.RU).changes(2))
    }

    @Test
    fun laterDaysAreSaidPlainly() {
        assertEquals("Leave tomorrow at 17:59", SimpleStrings.of(SimpleLanguage.EN).leaveOn(null, "17:59"))
        assertEquals("Leave on Sunday at 08:00", SimpleStrings.of(SimpleLanguage.EN).leaveOn(java.time.DayOfWeek.SUNDAY, "08:00"))
        assertEquals("צאו בשבת ב־20:10", SimpleStrings.of(SimpleLanguage.HE).leaveOn(java.time.DayOfWeek.SATURDAY, "20:10"))
        assertEquals("Выходите во вторник в 07:15", SimpleStrings.of(SimpleLanguage.RU).leaveOn(java.time.DayOfWeek.TUESDAY, "07:15"))
    }
}
