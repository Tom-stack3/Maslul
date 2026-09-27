package com.maslul.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
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
}
