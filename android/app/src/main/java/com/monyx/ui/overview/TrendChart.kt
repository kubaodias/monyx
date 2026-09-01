package com.monyx.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.DailyTotals
import com.monyx.data.Dates
import java.time.DayOfWeek
import java.time.LocalDate

/** How many days the balance card's trend covers. */
const val TREND_DAYS = 30

/** One day on the trend: what came in, what went out. */
data class TrendPoint(
    val date: LocalDate,
    val incomeMinor: Long,
    val expenseMinor: Long,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
    val isQuiet: Boolean get() = incomeMinor == 0L && expenseMinor == 0L
}

/**
 * A gap-filled window of days, plus the balance as it stood at the end of each
 * one.
 *
 * [runningMinor] shares indices with [points] and is what makes this a picture
 * of a budget rather than a pile of bars: the bars say what happened on a day,
 * the running total says where it left you.
 */
data class TrendSeries(
    val points: List<TrendPoint>,
    val runningMinor: List<Long>,
) {
    val incomeMinor: Long = points.sumOf { it.incomeMinor }
    val expenseMinor: Long = points.sumOf { it.expenseMinor }
    val netMinor: Long get() = incomeMinor - expenseMinor

    /** The largest single day on either side. */
    val peakMinor: Long = points.maxOfOrNull { maxOf(it.incomeMinor, it.expenseMinor) } ?: 0L

    /**
     * What half the chart's height is worth — deliberately NOT the peak.
     *
     * One 8 500 salary against thirty days of 40 zł groceries scales every
     * expense to less than a pixel, and a chart in which the ordinary month is
     * invisible answers no question worth asking. So the scale is six times a
     * typical day, capped at the peak: everyday amounts get most of the height,
     * and the two or three days that really are off the scale run into the edge
     * and say so by touching it.
     *
     * Both halves still share this one number. Scaling income and expense
     * separately would draw a 8 500 payday and a 200 zł shop as the same bar,
     * which is worse than a flat chart — it is a wrong one.
     */
    val scaleMinor: Long = run {
        val pool = points
            .flatMap { listOf(it.incomeMinor, it.expenseMinor) }
            .filter { it > 0 }
            .sorted()
        if (pool.isEmpty()) 0L else minOf(peakMinor, maxOf(pool[pool.size / 2] * 6, 1L))
    }

    val isEmpty: Boolean get() = peakMinor == 0L
    val from: LocalDate get() = points.first().date
    val to: LocalDate get() = points.last().date

    fun runningAt(index: Int): Long = runningMinor.getOrElse(index) { netMinor }
}

/**
 * Turn the rows the database returned into one point per day.
 *
 * Every day in the window gets a point whether or not anything happened on it.
 * A chart plotted only from the days that had transactions would space Tuesday
 * and Friday the same distance apart as Tuesday and Wednesday, which destroys
 * the one thing an over-time chart is for.
 *
 * Always returns at least one point, so the callers that ask for [from] and
 * [to] cannot be handed a series with no ends.
 */
fun trendSeries(rows: List<DailyTotals>, from: LocalDate, to: LocalDate): TrendSeries {
    val byDay = rows.associateBy { it.day }
    val last = if (to.isBefore(from)) from else to
    val points = ArrayList<TrendPoint>()
    var day = from
    while (!day.isAfter(last)) {
        val row = byDay[day.toString()]
        points += TrendPoint(day, row?.incomeMinor ?: 0L, row?.expenseMinor ?: 0L)
        day = day.plusDays(1)
    }
    var running = 0L
    val cumulative = points.map {
        running += it.netMinor
        running
    }
    return TrendSeries(points, cumulative)
}

/**
 * The window the chart covers for [period]: [TREND_DAYS] days ending on the
 * last day that month can honestly show.
 */
fun trendWindow(period: String, today: LocalDate = Dates.today()): ClosedRange<LocalDate> {
    val end = Dates.windowEnd(period, today)
    return end.minusDays((TREND_DAYS - 1).toLong())..end
}

/**
 * Which day a touch at [x] across a chart [width] wide belongs to.
 *
 * Columns are equal width, so the body is a division — the ends are what needs
 * deciding. A drag that runs off either edge stays on the first or last day
 * rather than returning a 31st that does not exist, because a finger sliding
 * past the end of the chart means "the end", not "nothing".
 */
internal fun dayIndexAt(x: Float, width: Float, count: Int): Int? {
    if (count <= 0 || width <= 0f) return null
    val index = (x / (width / count)).toInt()
    return index.coerceIn(0, count - 1)
}

/**
 * Income above the line, expenses below it, one column per day.
 *
 * A diverging pair rather than a single net bar: a day that took 4 000 in and
 * paid 4 000 out is not a quiet day, and netting it to zero would draw it as
 * one. Both sides share a scale set by the largest single amount in the window,
 * so the two halves are comparable by eye — which is the whole point of putting
 * them on one axis.
 */
@Composable
fun TrendChart(
    series: TrendSeries,
    focused: Int?,
    onFocus: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (series.isEmpty) {
        Box(
            modifier = modifier.fillMaxWidth().height(132.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.overview_trend_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // pointerInput keeps whichever lambda it was given when its keys last
    // changed, so a plain read of `focused` inside the gesture would still be
    // the value from the first composition — and tapping a bar a second time
    // would compare against a stale null and never let go of it.
    val focusedNow by rememberUpdatedState(focused)

    val incomeColor = MaterialTheme.colorScheme.primary
    val expenseColor = MaterialTheme.colorScheme.error
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val count = series.points.size

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .pointerInput(count) {
                    detectTapGestures { offset ->
                        val hit = dayIndexAt(offset.x, size.width.toFloat(), count)
                        // Tapping the focused day again lets go of it, so the
                        // card can get back to the window totals without
                        // needing a second control for it.
                        onFocus(if (hit == focusedNow) null else hit)
                    }
                }
                .pointerInput(count) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            onFocus(dayIndexAt(offset.x, size.width.toFloat(), count))
                        },
                    ) { change, _ ->
                        // Consumed so the pager-less LazyColumn around it does
                        // not steal the gesture halfway through a scrub.
                        change.consume()
                        onFocus(dayIndexAt(change.position.x, size.width.toFloat(), count))
                    }
                },
        ) {
            val column = size.width / count
            val barWidth = (column * 0.40f).coerceAtLeast(1.5.dp.toPx())
            val gap = column * 0.10f
            val centreY = size.height / 2f
            val half = centreY - 3.dp.toPx()
            // Everything within the scale is drawn inside this, so reaching the
            // full half-height means one thing only: the day is off the scale.
            val usable = half * 0.9f
            // A 3 zł coffee beside a 4 000 zł rent still rounds to nothing.
            // A day money moved on must look like one.
            val minBar = 2.dp.toPx()
            val scale = series.scaleMinor
            fun barHeight(amount: Long): Float = when {
                scale <= 0L -> 0f
                amount >= scale -> half
                else -> (amount.toFloat() / scale * usable).coerceAtLeast(minBar)
            }
            val radius = CornerRadius(barWidth / 2f, barWidth / 2f)

            series.points.forEachIndexed { index, point ->
                val left = index * column
                val isFocused = focused == index
                val dimmed = focused != null && !isFocused

                // Weekends as a faint band. Spending has a weekly rhythm and
                // without them thirty columns are an undated smear.
                if (point.date.dayOfWeek == DayOfWeek.SATURDAY ||
                    point.date.dayOfWeek == DayOfWeek.SUNDAY
                ) {
                    drawRect(
                        color = axisColor.copy(alpha = 0.04f),
                        topLeft = Offset(left, 0f),
                        size = Size(column, size.height),
                    )
                }
                if (isFocused) {
                    drawRect(
                        color = axisColor.copy(alpha = 0.14f),
                        topLeft = Offset(left, 0f),
                        size = Size(column, size.height),
                    )
                }

                val alpha = if (dimmed) 0.35f else 1f
                val centreX = left + column / 2f
                if (point.incomeMinor > 0) {
                    val h = barHeight(point.incomeMinor)
                    drawRoundRect(
                        color = incomeColor.copy(alpha = alpha),
                        topLeft = Offset(centreX - gap / 2f - barWidth, centreY - h),
                        size = Size(barWidth, h),
                        cornerRadius = radius,
                    )
                }
                if (point.expenseMinor > 0) {
                    val h = barHeight(point.expenseMinor)
                    drawRoundRect(
                        color = expenseColor.copy(alpha = alpha),
                        topLeft = Offset(centreX + gap / 2f, centreY),
                        size = Size(barWidth, h),
                        cornerRadius = radius,
                    )
                }
            }

            drawLine(
                color = axisColor.copy(alpha = 0.35f),
                start = Offset(0f, centreY),
                end = Offset(size.width, centreY),
                strokeWidth = 1.dp.toPx(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AxisLabel(Dates.shortDayLabel(series.from.toString()), axisColor)
            AxisLabel(Dates.shortDayLabel(series.to.toString()), axisColor)
        }
    }
}

@Composable
private fun AxisLabel(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
}
