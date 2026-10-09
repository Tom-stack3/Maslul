package com.maslul.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maslul.app.data.Leg
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import com.maslul.app.ui.nav.CombinedBadges
import com.maslul.app.ui.nav.RideOptions
import com.maslul.app.ui.theme.MaslulTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** A route card keeps a stop's many lines to a few, the rest a tap away. */
@RunWith(AndroidJUnit4::class)
class RideOptionsTest {
    @get:Rule
    val rule = createComposeRule()

    private val t0 = Instant.parse("2026-10-09T10:00:00Z")
    private val a = StopCall("sA", "Cinema City", null, 32.0, 34.8, null, null)
    private val b = StopCall("sB", "Yehudit", null, 32.05, 34.8, null, null)

    private fun bus(line: String, start: Int) = Leg(
        TransitMode.BUS, a, b, t0.plusSeconds(start * 60L), t0.plusSeconds((start + 20) * 60L), null, null, line, null, null, null,
        null, "trip-$line-$start", null, emptyList(), emptyList(),
    )

    // Seven lines, as at a busy stop.
    private val rides = listOf("650", "347", "852", "623", "501", "60", "18").mapIndexed { i, l -> bus(l, 5 + i * 2) }

    @Test
    fun badgesShowThreeLinesAndCountTheRest() {
        rule.setContent { MaslulTheme { CombinedBadges(rides) } }
        rule.onNodeWithText("650").assertExists()
        rule.onNodeWithText("852").assertExists()
        rule.onNodeWithText("623").assertDoesNotExist()
        rule.onNodeWithText("+4", substring = true).assertExists()
    }

    @Test
    fun optionsShowThreeAndExpandOnTap() {
        rule.setContent { MaslulTheme { RideOptions(rides.first(), rides, t0, relative = false, liveOf = { null }) } }
        rule.onAllNodes(hasText("623")).assertCountEquals(0)
        rule.onNodeWithTag("more_rides").assertTextEquals("+4 more").performClick()
        rule.onAllNodes(hasText("623")).assertCountEquals(1)
        rule.onAllNodes(hasText("18")).assertCountEquals(1)
        rule.onNodeWithTag("more_rides").assertTextEquals("Show fewer").performClick()
        rule.onAllNodes(hasText("623")).assertCountEquals(0)
    }
}
