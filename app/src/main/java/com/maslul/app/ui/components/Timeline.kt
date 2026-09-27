package com.maslul.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Rail { NONE, SOLID, DOTTED, FADED }

enum class Node { NONE, STOP, SMALL, ENDPOINT, TERMINUS, VEHICLE }

data class RailSpec(val style: Rail, val color: Color)

/**
 * One row of a vertical journey timeline: optional time column, a rail with a node, and
 * content. Rows stack seamlessly, so the rail looks continuous.
 */
@Composable
fun TimelineRow(
    above: RailSpec?,
    below: RailSpec?,
    node: Node,
    nodeColor: Color,
    modifier: Modifier = Modifier,
    nodeY: Dp = 22.dp,
    timeWidth: Dp = 58.dp,
    time: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (timeWidth > 0.dp) {
            Box(Modifier.width(timeWidth).padding(start = 14.dp, top = nodeY - 10.dp), contentAlignment = Alignment.TopStart) {
                time?.invoke(this)
            }
        }
        Box(
            Modifier.width(30.dp).fillMaxHeight().drawBehind {
                val cx = size.width / 2
                val ny = nodeY.toPx()
                above?.let { drawRail(it, cx, 0f, if (node == Node.NONE) size.height else ny) }
                below?.let { drawRail(it, cx, if (node == Node.NONE) 0f else ny, size.height) }
                when (node) {
                    Node.NONE -> Unit
                    Node.SMALL -> {
                        drawCircle(surface, 4.dp.toPx(), Offset(cx, ny))
                        drawCircle(nodeColor, 4.dp.toPx(), Offset(cx, ny), style = Stroke(2.dp.toPx()))
                    }
                    Node.STOP -> {
                        drawCircle(surface, 7.dp.toPx(), Offset(cx, ny))
                        drawCircle(nodeColor, 6.dp.toPx(), Offset(cx, ny), style = Stroke(3.dp.toPx()))
                    }
                    Node.TERMINUS -> {
                        drawCircle(nodeColor, 8.dp.toPx(), Offset(cx, ny))
                        drawCircle(surface, 3.dp.toPx(), Offset(cx, ny))
                    }
                    Node.ENDPOINT -> {
                        drawCircle(nodeColor, 8.dp.toPx(), Offset(cx, ny))
                        drawCircle(surface, 3.5.dp.toPx(), Offset(cx, ny))
                    }
                    Node.VEHICLE -> {
                        drawCircle(surface, 9.dp.toPx(), Offset(cx, ny))
                        drawCircle(nodeColor, 7.dp.toPx(), Offset(cx, ny))
                    }
                }
            },
        )
        Column(Modifier.weight(1f).padding(start = 6.dp, end = 16.dp), content = content)
    }
}

private fun DrawScope.drawRail(spec: RailSpec, cx: Float, y0: Float, y1: Float) {
    if (y1 <= y0) return
    when (spec.style) {
        Rail.NONE -> Unit
        Rail.SOLID -> drawLine(spec.color, Offset(cx, y0), Offset(cx, y1), 5.dp.toPx(), StrokeCap.Butt)
        Rail.FADED -> drawLine(spec.color.copy(alpha = 0.3f), Offset(cx, y0), Offset(cx, y1), 5.dp.toPx(), StrokeCap.Butt)
        Rail.DOTTED -> {
            val step = 8.dp.toPx()
            var y = y0 + step / 2
            while (y < y1) {
                drawCircle(spec.color, 2.dp.toPx(), Offset(cx, y))
                y += step
            }
        }
    }
}

@Composable
fun TimelineSpacer(rail: RailSpec, height: Dp = 8.dp, timeWidth: Dp = 58.dp) {
    TimelineRow(rail, rail, Node.NONE, Color.Transparent, timeWidth = timeWidth) {
        Box(Modifier.height(height))
    }
}
