package com.maslul.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maslul.app.data.Place
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end flows on a device, against the live services. */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun searchLineAndOpenIt() {
        rule.onNodeWithTag("tab_lines").performClick()
        rule.onNodeWithTag("line_search").performTextInput("480")
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("480")).fetchSemanticsNodes().size >= 2 }
        rule.onAllNodes(hasText("480"))[1].performClick()
        rule.waitUntil(30_000) { rule.onAllNodes(hasTestTag("line_stops")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Timetable").performClick()
    }

    @Test
    fun planTripToAPlace() {
        rule.onNodeWithText("Where to?").performClick()
        rule.onNodeWithTag("field_to").performTextInput("dizengoff center")
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Dizengoff Center")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Dizengoff Center"))[0].performClick()
        rule.waitUntil(40_000) { rule.onAllNodes(hasTestTag("routes_list")).fetchSemanticsNodes().isNotEmpty() }
        // A route card: "06:10 – 06:42".
        val span = Regex("""(\d\d):(\d\d) – \d\d:\d\d""")
        fun spans() = rule.onAllNodes(hasText(" – ", substring = true)).fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString("")?.let { span.find(it) } }
        rule.waitUntil(20_000) { spans().isNotEmpty() }
        // The countdown shows for rides within the next 90 minutes (overnight, the first may be later).
        val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Jerusalem"))
        val soonest = spans().minOf { m ->
            val t = java.time.LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            java.time.Duration.between(now, t).toMinutes().let { if (it < -720) it + 1440 else it }
        }
        if (soonest in 2..85) {
            rule.waitUntil(20_000) { rule.onAllNodes(hasText("leave in", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun searchShowsANewTopResultAsYouKeepTyping() {
        // Result rows, not the search field (whose text matches too).
        fun row(text: String) = hasText(text, substring = true) and !hasSetTextAction()
        fun onTop() = rule.onNodeWithTag("search_results").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() == 0f
        rule.onNodeWithText("Where to?").performClick()
        // Near central Tel Aviv, "ויצמן 5" puts Tel Aviv's first; typing on, Kfar Saba's comes in just above it.
        rule.onNodeWithTag("field_to").performTextInput("ויצמן 5")
        rule.waitUntil(20_000) { rule.onAllNodes(row("ויצמן 5")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithTag("field_to").performTextInput(" כ")
        rule.waitUntil(20_000) { rule.onAllNodes(row("כפר סבא")).fetchSemanticsNodes().isNotEmpty() || !onTop() }
        rule.waitForIdle()
        assertTrue("results list scrolled to the top", onTop())
        rule.onAllNodes(row("כפר סבא"))[0].assertIsDisplayed()
    }

    @Test
    fun closingOptionsUntouchedKeepsTheRoutes() {
        rule.onNodeWithText("Where to?").performClick()
        rule.onNodeWithTag("field_to").performTextInput("dizengoff center")
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Dizengoff Center")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Dizengoff Center"))[0].performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("min walk", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Options").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Show routes")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Show routes").performClick()
        rule.waitForIdle()
        // No new search: the routes are still there.
        assertTrue(rule.onAllNodes(hasText("Finding routes", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasText("min walk", substring = true)).fetchSemanticsNodes().isNotEmpty())
        // Changing an option does search again (then put it back).
        for (speed in listOf("Brisk", "Normal")) {
            rule.onNodeWithText("Options").performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(hasText("Show routes")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText(speed).performClick()
            rule.onNodeWithText("Show routes").performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(hasText("Finding routes", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            rule.waitUntil(60_000) { rule.onAllNodes(hasText("min walk", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun advanced(rides: Boolean, onBoard: Boolean) =
        MaslulApp.instance.store.updateSettings { it.copy(rides = rides, onBoard = onBoard) }

    private fun planTo(query: String, pick: String) {
        rule.onNodeWithText("Where to?").performClick()
        rule.onNodeWithTag("field_to").performTextInput(query)
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(pick)).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText(pick))[0].performClick()
    }

    @Test
    fun advancedToolsStayOutOfTheWayUntilSwitchedOn() {
        advanced(rides = false, onBoard = false)
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasTestTag("on_board")).fetchSemanticsNodes().isEmpty())
        planTo("dizengoff center", "Dizengoff Center")
        rule.waitUntil(40_000) { rule.onAllNodes(hasTestTag("routes_list")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(hasTestTag("ride_chip")).fetchSemanticsNodes().isEmpty())
        // Each switch brings back only its own tool.
        advanced(rides = true, onBoard = false)
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("ride_chip")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ride_chip").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Getting a ride (טרמפ)?")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aRideWithinMinutesSaysWhereToGetOut() {
        advanced(rides = true, onBoard = false)
        planTo("haifa center", "Haifa Center HaShemona")
        rule.waitUntil(40_000) { rule.onAllNodes(hasTestTag("ride_chip")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ride_chip").performClick()
        rule.onNodeWithText("15 min").performClick()
        rule.onNodeWithTag("ride_within").performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Get dropped off", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("15 min ride").assertIsDisplayed()
    }

    @Test
    fun onABusListsTheRidesAround() {
        advanced(rides = false, onBoard = true)
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("on_board")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("on_board").performClick()
        rule.onNodeWithText("Which ride are you on?").assertIsDisplayed()
        // Rides around, or (late at night) the message saying there are none.
        rule.waitUntil(60_000) {
            rule.onAllNodes(hasTestTag("onboard_list")).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("No rides found around you")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun switchingLanguageRedrawsTheApp() {
        try {
            rule.onNodeWithText("Where to?").assertIsDisplayed()
            rule.onNodeWithContentDescription("Settings").performClick()
            rule.onNodeWithTag("lang_he").performClick()
            // Settings speaks Hebrew at once, and back home too.
            rule.onNodeWithText("הגדרות").assertIsDisplayed()
            rule.onNodeWithContentDescription("חזרה").performClick()
            rule.onNodeWithText("לאן?").assertIsDisplayed()
            rule.onNodeWithText("קווים").assertIsDisplayed()
            rule.onNodeWithContentDescription("הגדרות").performClick()
            rule.onNodeWithTag("lang_ru").performClick()
            rule.onNodeWithText("Настройки").assertIsDisplayed()
        } finally {
            rule.runOnUiThread { MaslulApp.instance.store.updateSettings { it.copy(language = null) } }
        }
    }

    private fun openNewShuttle() {
        rule.onNodeWithText("Where to?").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("My shuttles").performScrollTo().performClick()
        rule.onNodeWithTag("add_shuttle").performClick()
        rule.onNodeWithText("New shuttle").assertIsDisplayed()
    }

    /** As TalkBack would; dragging it is checked by hand (an injected drag races the keyboard on a slow emulator). */
    private fun setRide(minutes: Float) {
        rule.onNodeWithTag("shuttle_ride_slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(minutes) }
    }

    private fun missingText() = rule.onNodeWithTag("shuttle_missing").fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString("")

    @Test
    fun aShuttleSaysWhatsMissingIncludingTheRideTime() {
        openNewShuttle()
        // Nothing chosen yet, the ride time included: it has no default.
        rule.onNodeWithTag("shuttle_ride").assertTextEquals("Not set")
        rule.onNodeWithTag("shuttle_save").assertIsEnabled().performScrollTo().performClick()
        // Still here, and saying what's missing.
        rule.onNodeWithText("New shuttle").assertIsDisplayed()
        assertEquals(
            "Still needed: a name, where it leaves from, where it goes, departure times and how long the ride takes.",
            missingText(),
        )
        rule.onNodeWithText("Add at least one departure time").assertExists()
        rule.onNodeWithTag("shuttle_name").performTextInput("Office")
        rule.onNodeWithTag("shuttle_times").performTextInput("07:30, soon")
        rule.onNodeWithText("Use 24-hour times", substring = true).assertExists()
        assertTrue(missingText().contains("departure times like 07:30"))
        rule.onNodeWithTag("shuttle_times").performTextClearance()
        rule.onNodeWithTag("shuttle_times").performTextInput("07:30, 17:00")
        rule.onNodeWithText("2 departures a day").assertExists()
        // Setting the ride time takes it off the list.
        setRide(60f)
        rule.onNodeWithTag("shuttle_ride").assertTextEquals("60 min")
        assertEquals("Still needed: where it leaves from and where it goes.", missingText())
        // Saving with the way back is held back the same way.
        rule.onNodeWithText("Save and add the way back").performScrollTo().performClick()
        rule.onNodeWithText("New shuttle").assertIsDisplayed()
    }

    private fun pickStop(row: String, query: String, pick: String) {
        rule.onNodeWithText(row).performScrollTo().performClick()
        rule.onNodeWithTag("field_to").performTextInput(query)
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(pick) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText(pick) and !hasSetTextAction())[0].performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("New shuttle")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aCompleteShuttleIsSavedEditedAndDeleted() {
        val name = "Test ${System.currentTimeMillis() % 10000}"
        openNewShuttle()
        rule.onNodeWithTag("shuttle_name").performTextInput(name)
        pickStop("From", "dizengoff center", "Dizengoff Center")
        pickStop("To", "azrieli center", "Azrieli Center")
        rule.onNodeWithTag("shuttle_times").performTextInput("8:15 07:30")
        setRide(120f)
        rule.onNodeWithTag("shuttle_ride").assertTextEquals("120 min")
        assertTrue(rule.onAllNodes(hasTestTag("shuttle_missing")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("shuttle_save").performScrollTo().performClick()
        // Listed with its sorted times and days.
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("07:30, 08:15 · Sun–Thu").assertIsDisplayed()
        val saved = MaslulApp.instance.store.data.value.shuttles.single { it.name == name }
        assertEquals(listOf(450, 495), saved.times)
        assertEquals(120, saved.rideMinutes)
        // Editing keeps the ride time it was saved with.
        rule.onNodeWithText(name).performClick()
        rule.onNodeWithText("Edit shuttle").assertIsDisplayed()
        rule.onNodeWithTag("shuttle_ride").assertTextEquals("120 min")
        rule.onNodeWithText("Delete").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(name)).fetchSemanticsNodes().isEmpty() }
        assertTrue(MaslulApp.instance.store.data.value.shuttles.none { it.name == name })
    }

    /** The places sheet is entirely below the home screen's bottom edge (the tab bar is beneath it). */
    private fun assertSheetGone(why: String) {
        val bottom = rule.onNodeWithTag("home_screen").fetchSemanticsNode().boundsInRoot.bottom
        val tops = rule.onAllNodes(hasTestTag("home_sheet")).fetchSemanticsNodes().map { it.positionInRoot.y }
        assertTrue("$why: sheet tops $tops, screen bottom $bottom", tops.all { it >= bottom - 1 })
    }

    @Test
    fun theHomeMapCanBeEnlargedAndThePlacesBroughtBack() {
        advanced(rides = false, onBoard = true)
        // Enough recents for the sheet to be taller than the screen allows.
        val store = MaslulApp.instance.store
        val recents = (1..6).map { Place("Sheet test $it", 32.07 + it * 0.003, 34.78) }
        recents.forEach(store::addRecent)
        try {
            homeSheet()
        } finally {
            recents.forEach(store::removeRecent)
        }
    }

    private fun homeSheet() {
        rule.onNodeWithText("Where to?").assertIsDisplayed()
        rule.onNodeWithText("RECENT").assertIsDisplayed()
        // The button hides the places; the map has the screen, and its buttons stay in reach.
        rule.onNodeWithTag("home_map_expand").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("home_show_places")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithTag("on_board").assertIsDisplayed()
        rule.onNodeWithContentDescription("My location").assertIsDisplayed()
        rule.onNodeWithText("Where to?").assertIsDisplayed()
        assertSheetGone("button")
        rule.onNodeWithTag("home_show_places").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("home_show_places")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("RECENT").assertIsDisplayed()
        // Dragging it down does the same, and the corner button brings it back.
        rule.onNodeWithTag("home_sheet").performTouchInput { swipeDown(startY = top + 10f, endY = top + 3000f) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("home_show_places")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        assertSheetGone("drag")
        rule.onNodeWithContentDescription("Show places").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("home_show_places")).fetchSemanticsNodes().isEmpty() }
        // Pulled all the way up, it stops under the search card, which stays usable; the map buttons step aside.
        rule.onNodeWithTag("home_sheet").performTouchInput { swipeUp(startY = top + 10f, endY = -2000f) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("on_board")).fetchSemanticsNodes().isEmpty() }
        rule.waitForIdle()
        val card = rule.onNodeWithText("Where to?").fetchSemanticsNode().boundsInRoot
        val sheet = rule.onNodeWithTag("home_sheet").fetchSemanticsNode().boundsInRoot
        assertTrue("sheet top ${sheet.top} below search card ${card.bottom}", sheet.top > card.bottom)
        rule.onNodeWithText("Where to?").performClick()
        rule.onNodeWithTag("field_to").assertIsDisplayed()
    }
}
