package com.maslul.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("leave in", substring = true)).fetchSemanticsNodes().isNotEmpty() }
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
}

