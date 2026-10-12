package com.maslul.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maslul.app.ui.components.Node
import com.maslul.app.ui.components.Rail
import com.maslul.app.ui.components.RailSpec
import com.maslul.app.ui.components.TimelineRow
import com.maslul.app.ui.theme.MaslulTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A route's timeline rows are as tall as what's in them. */
@RunWith(AndroidJUnit4::class)
class TimelineTest {
    @get:Rule
    val rule = createComposeRule()

    @OptIn(ExperimentalLayoutApi::class)
    @Test
    fun aRowWithWrappingRidesKeepsWhatComesAfterThem() {
        val rail = RailSpec(Rail.SOLID, Color.Red)
        rule.setContent {
            MaslulTheme {
                Column(Modifier.width(320.dp)) {
                    TimelineRow(rail, rail, Node.NONE, Color.Transparent) {
                        // "If you miss it": rides that wrap onto several lines.
                        FlowRow {
                            repeat(4) { Text("Train 01:19 · +54 min  ") }
                        }
                        Text("Ride 12 stops · 1 h 10 min", Modifier.testTag("after"))
                    }
                    TimelineRow(rail, null, Node.STOP, Color.Red) {
                        Text("Next stop", Modifier.testTag("next"))
                    }
                }
            }
        }
        val after = rule.onNodeWithTag("after").getBoundsInRoot()
        val next = rule.onNodeWithTag("next").getBoundsInRoot()
        assertTrue("the line after the rides has no height: $after", after.bottom > after.top)
        assertTrue("the next row ($next) overlaps the line after the rides ($after)", next.top >= after.bottom)
    }
}
