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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monyx.R
import com.monyx.data.Dates
import com.monyx.data.Money

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
 * A tap on a day with spending on it opens that day in the ledger. A tap on an
 * empty one does nothing at all, deliberately: there is no list to show, and
 * landing on an empty screen reads as a bug rather than as an answer.
 */
@Composable
fun DailySpendChart(
    daily: DailySpend,
    onSelectDay: (String) -> Unit,
    modifier: Modifier = Modifier,
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
    // The one figure on the chart, over the one bar worth naming.
    val peakStyle = axisStyle.copy(
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
    )

    val density = LocalDensity.current
    val gutterPx = remember(ticks, measurer, density) {
        ticks.maxOf { measurer.measure(Money.formatWhole(it), axisStyle).size.width } +
            with(density) { 6.dp.toPx() }
    }
    val labelHeightPx = remember(measurer, density) {
        measurer.measure("0", axisStyle).size.height + with(density) { 6.dp.toPx() }
    }

    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val barColor = MaterialTheme.colorScheme.primary
    // A day nothing was spent on still gets a mark: a one-pixel stub on the
    // baseline, so the eye can count the quiet days instead of wondering whether
    // the chart is missing them.
    val emptyColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val count = daily.days.size

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
        val plotBottom = size.height - labelHeightPx
        // Room above the tallest bar for the figure that sits over it.
        val plotTop = 14.dp.toPx()
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
            drawRoundRect(
                color = if (day.expenseMinor > 0L) barColor else emptyColor,
                topLeft = Offset(left, plotBottom - maxOf(height, stub)),
                size = Size(barWidth, maxOf(height, stub)),
                cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f),
            )

            // Every fifth day of the month, and the 1st. Not every bar: thirty-one
            // numbers along the bottom of a phone overlap into a grey smear, and
            // not a fixed every-Nth-bar either, which would label whichever days
            // the window happened to start on.
            val number = day.date.dayOfMonth
            if (number == 1 || number % 5 == 0) {
                val label = measurer.measure(number.toString(), axisStyle)
                drawText(
                    textLayoutResult = label,
                    topLeft = Offset(
                        left + (barWidth - label.size.width) / 2f,
                        plotBottom + 4.dp.toPx(),
                    ),
                )
            }
        }

        // The costliest day, named. It is what the face is FOR — the eye finds
        // the tallest bar on its own, and then wants to know what it cost without
        // having to open it.
        daily.busiestIndex?.let { index ->
            val day = daily.days[index]
            val text = measurer.measure(Money.formatWhole(day.expenseMinor), peakStyle)
            val centre = plotLeft + slot * index + slot / 2f
            val height = plotHeight * (day.expenseMinor.toFloat() / ceiling.toFloat())
            drawText(
                textLayoutResult = text,
                // Kept inside the canvas at both ends: the busiest day is often
                // the 1st or the last, and a figure centred on that bar would
                // hang half off the chart.
                topLeft = Offset(
                    (centre - text.size.width / 2f).coerceIn(plotLeft, size.width - text.size.width),
                    (plotBottom - height - text.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                ),
            )
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
