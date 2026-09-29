package com.monyx.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monyx.R
import com.monyx.data.Dates
import com.monyx.data.Money
import com.monyx.ui.theme.Palette

/**
 * A month of days as one bar each: what went out, day by day.
 *
 * The question this face exists for is "which day did the money go?", which
 * neither of the other two can answer — the pie knows categories and nothing
 * about time, and the twelve-month chart's smallest unit is a month, so a single
 * expensive Saturday is invisible on it.
 *
 * Bars, not a line. A line implies the days in between, and there is nothing in
 * between: spending on Tuesday tells you nothing about Wednesday, and joining
 * them up would draw slopes that are not a rate of anything. Every day gets a
 * column whether or not money moved on it, so the shape of a week is readable.
 *
 * Each bar is stacked in its categories' colours, so the chart answers "what
 * was that Saturday" as well as "which Saturday" — and the colours are the ones
 * the legend under it and the pie on the front of the card already use.
 *
 * A tap on a day with spending on it picks it out and opens the day, one step
 * short of leaving the screen: the question is usually "what was that", not
 * "take me away from here". A tap on an empty one does nothing at all,
 * deliberately: there is no list to show, and landing on an empty screen reads
 * as a bug rather than as an answer.
 */
@Composable
fun DailySpendChart(
    daily: DailySpend,
    onSelectDay: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Picked out with a ring, and named in the sheet the caller opens. */
    selected: String? = null,
) {
    if (daily.isEmpty) {
        Box(
            modifier = modifier.fillMaxWidth().height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.overview_days_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val ticks = remember(daily) { axisTicks(daily.maxMinor) }
    val ceiling = ticks.last().coerceAtLeast(1L)

    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(
        fontSize = 9.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val density = LocalDensity.current
    val gutterPx = remember(ticks, measurer, density) {
        ticks.maxOf { measurer.measure(Money.formatWhole(it), axisStyle).size.width } +
            with(density) { 6.dp.toPx() }
    }
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val fallbackColor = MaterialTheme.colorScheme.primary
    val selectedRing = MaterialTheme.colorScheme.onSurface
    // A day nothing was spent on still gets a mark: a one-pixel stub on the
    // baseline, so the eye can count the quiet days instead of wondering whether
    // the chart is missing them.
    val emptyColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val count = daily.days.size
    // Resolved once for the window, not per bar: Palette hashes when a category
    // has no colour of its own, and doing that inside the draw loop would hash
    // the same ids thirty-one times a frame.
    val colors = remember(daily) {
        daily.categories.associate { it.categoryId to Palette.colorFor(it.color, it.categoryId) }
    }
    val colorOf = { id: String -> colors[id] ?: fallbackColor }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(count, gutterPx, daily) {
                detectTapGestures { offset ->
                    val index = monthIndexAt(offset.x, gutterPx, size.width.toFloat(), count)
                        ?: return@detectTapGestures
                    val day = daily.days[index]
                    if (day.expenseMinor > 0L) onSelectDay(day.date.toString())
                }
            },
    ) {
        val plotLeft = gutterPx
        val plotWidth = size.width - plotLeft
        // No day numbers along the bottom any more — the day that matters is
        // the one that was tapped, and it is named in full in the sheet. A row
        // of 1 5 10 15 under thirty-one bars was a scale for a chart nobody
        // reads by counting.
        // Half a line of type below the baseline, so the "0" beside it is not
        // sliced in half by the bottom of the canvas.
        val plotBottom = size.height - measurer.measure("0", axisStyle).size.height / 2f
        val plotTop = 6.dp.toPx()
        val plotHeight = plotBottom - plotTop
        if (plotWidth <= 0f || plotHeight <= 0f || count == 0) return@Canvas

        val slot = plotWidth / count
        // Thirty-one bars across a phone leaves each slot about 9dp wide, so the
        // gap is a fraction of the slot rather than a fixed dp: at this density a
        // 2dp gap either side would leave nothing to draw.
        val barWidth = (slot * 0.66f).coerceAtLeast(1.5.dp.toPx())
        val stub = 1.5.dp.toPx()

        ticks.forEach { tick ->
            val y = plotBottom - plotHeight * (tick.toFloat() / ceiling.toFloat())
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
            val text = measurer.measure(Money.formatWhole(tick), axisStyle)
            drawText(
                textLayoutResult = text,
                topLeft = Offset(plotLeft - 6.dp.toPx() - text.size.width, y - text.size.height / 2f),
            )
        }

        daily.days.forEachIndexed { index, day ->
            val left = plotLeft + slot * index + (slot - barWidth) / 2f
            val height = plotHeight * (day.expenseMinor.toFloat() / ceiling.toFloat())

            if (day.expenseMinor <= 0L) {
                drawRoundRect(
                    color = emptyColor,
                    topLeft = Offset(left, plotBottom - stub),
                    size = Size(barWidth, stub),
                    cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f),
                )
            } else {
                // Stacked from the baseline up, biggest block first. Square
                // corners: rounding every segment would leave white nicks
                // through the middle of a bar 9dp wide, so only the top of the
                // whole stack is rounded, by the cap drawn after it.
                var y = plotBottom
                day.parts.forEach { part ->
                    val share = plotHeight * (part.minorAmount.toFloat() / ceiling.toFloat())
                    drawRect(
                        color = colorOf(part.categoryId),
                        topLeft = Offset(left, y - share),
                        size = Size(barWidth, share),
                    )
                    y -= share
                }
                // The rounded cap, in the topmost block's colour — and never
                // taller than that block, or the smallest category on a busy
                // day would be painted over by its own corner.
                val top = day.parts.last()
                val topShare = plotHeight * (top.minorAmount.toFloat() / ceiling.toFloat())
                val cap = minOf(barWidth / 2f, maxOf(topShare, stub), maxOf(height, stub))
                drawRoundRect(
                    color = colorOf(top.categoryId),
                    topLeft = Offset(left, plotBottom - maxOf(height, stub)),
                    size = Size(barWidth, cap),
                    cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f),
                )
            }

            // The tapped day, ringed. Whatever the sheet says, the chart has to
            // show WHICH bar it is talking about.
            if (day.date.toString() == selected) {
                drawRoundRect(
                    color = selectedRing,
                    topLeft = Offset(left - 2.dp.toPx(), plotBottom - maxOf(height, stub) - 3.dp.toPx()),
                    size = Size(barWidth + 4.dp.toPx(), maxOf(height, stub) + 3.dp.toPx()),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

/**
 * The window's dates, under the chart, exactly as the trend labels its own.
 */
@Composable
fun DailySpendAxis(daily: DailySpend, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = Dates.shortDayLabel(daily.from.toString()),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        Text(
            text = Dates.shortDayLabel(daily.to.toString()),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
