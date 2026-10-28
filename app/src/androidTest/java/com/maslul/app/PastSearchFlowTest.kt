package com.maslul.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.PastSearch
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import com.maslul.app.ui.components.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** A recent destination shows the options seen last time, from where they were found. */
@RunWith(AndroidJUnit4::class)
class PastSearchFlowTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = MaslulApp.instance

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** The route cards' "06:10 – 06:42" lines, top first. */
    private fun spans(): List<String> {
        val span = Regex("""\d\d:\d\d – \d\d:\d\d""")
        return rule.onAllNodes(hasText(" – ", substring = true)).fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString("")?.let { span.find(it)?.value } }
    }

    private fun leg(mode: TransitMode, from: StopCall, to: StopCall, start: Instant, min: Long, line: String? = null) = Leg(
        mode, from, to, start, start.plusSeconds(min * 60), null, "Testville", line, null, null, null, null,
        line?.let { "past-trip-$it" }, null, emptyList(), listOf(GeoPoint(from.lat, from.lon), GeoPoint(to.lat, to.lon)),
    )

    @Test
    fun aKeptSearchShowsAsItWasThenSearchesAgainFromHere() {
        // Options found yesterday, from a spot that isn't where the phone is now.
        val at = Instant.now().minusSeconds(26 * 3600)
        val a = StopCall("pastA", "Past stop A", null, 32.08, 34.78, at.plusSeconds(300), at.plusSeconds(300))
        val b = StopCall("pastB", "Past stop B", null, 32.11, 34.80, null, null)
        val from = Place("Past start corner", 32.079, 34.779, kind = PlaceKind.PIN)
        val to = Place("Past test destination", 32.111, 34.801)
        val itin = Itinerary("past-1", at, at.plusSeconds(1800), 0, listOf(
            leg(TransitMode.WALK, a.copy(name = "start"), a, at, 5),
            leg(TransitMode.BUS, a, b, at.plusSeconds(300), 25, line = "777"),
        ))
        app.store.addRecent(to)
        app.searches.save(PastSearch(from, to, at, itineraries = listOf(itin)))
        try {
            rule.onNodeWithText("Past test destination").performScrollTo().performClick()
            rule.waitUntil(5_000) { exists("past_note") }
            rule.onNodeWithText("Options shown at ${Fmt.dayTime(at)}").assertExists()
            rule.onNodeWithText("Past start corner").assertExists()
            assertEquals(listOf("${Fmt.time(at)} – ${Fmt.time(at.plusSeconds(1800))}"), spans())
            rule.onNodeWithText("777").assertExists()
            // From where the phone is now: a new search, and the note goes.
            rule.onNodeWithTag("past_from_here").performClick()
            rule.waitUntil(5_000) { !exists("past_note") }
            rule.onNodeWithText("Current location").assertExists()
        } finally {
            app.store.removeRecent(to)
            app.searches.remove(to)
        }
    }

    @Test
    fun aRealSearchIsKeptAndShownAgain() {
        rule.onNodeWithText("Where to?").performClick()
        rule.onNodeWithTag("field_to").performTextInput("dizengoff center")
        val names = listOf("Dizengoff", "דיזנגוף").map { hasText(it, substring = true, ignoreCase = true) }.reduce { x, y -> x or y } and
            !hasSetTextAction()
        rule.waitUntil(20_000) { rule.onAllNodes(names).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(names)[0].performClick()
        rule.waitUntil(60_000) { exists("routes_list") && spans().isNotEmpty() }
        try {
            rule.waitUntil(10_000) { app.searches.searches.value.isNotEmpty() }
            val kept = app.searches.searches.value.first()
            assertEquals("Where you were", kept.from.name)
            assertTrue(kept.fromHere)
            assertTrue(kept.itineraries.isNotEmpty())
            rule.waitForIdle()
            // Back home, the recent says where from and when; tapping it shows those options, not a new search.
            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitUntil(5_000) { exists("home_screen") }
            rule.onAllNodesWithText("Searched at ${Fmt.time(kept.at)}")[0].performScrollTo().performClick()
            rule.waitUntil(5_000) { exists("past_note") }
            assertTrue(!exists("refining"))
            val shown = spans()
            assertTrue("kept options shown: $shown", shown.isNotEmpty())
            // Searching again now drops the note.
            rule.onNodeWithTag("past_search_now").performClick()
            rule.waitUntil(5_000) { !exists("past_note") }
            // Searched again from the same spot, it's still kept as from where you were.
            rule.waitUntil(60_000) { app.searches.searches.value.first().at.isAfter(kept.at) }
            assertTrue(app.searches.searches.value.first().fromHere)
        } finally {
            app.searches.searches.value.firstOrNull()?.to?.let { app.searches.remove(it); app.store.removeRecent(it) }
        }
    }
}
