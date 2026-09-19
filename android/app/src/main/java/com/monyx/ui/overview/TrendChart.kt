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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.DailyDelta
import com.monyx.data.DailyTotals
import com.monyx.data.Dates
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * The MINIMUM span of the balance card's trend. The window is stretched past it
 * when it has to be — see [trendWindow].
 */
const val TREND_DAYS = 30

/** One day on the trend: what came in, what went out. */
data class TrendPoint(
    val date: LocalDate,
    val incomeMinor: Long,
    val expenseMinor: Long,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
}

/**
 * A gap-filled window of days, and what the accounts held at the end of each
 * one.
 *
 * [runningMinor] is the series the card actually draws, and it is a POSITION —
 * money in the accounts — not a rate. The daily figures in [points] are what a
 * single day is worth once you point at it: they are the readout, not the
 * shape.
 *
 * [monthIncomeMinor] and [monthExpenseMinor] are the same readout widened to
 * the month: what had come in and gone out between the 1st and that day. A
 * single day's own two figures are almost always "0,00 and 0,00", which is a
 * true answer to a question nobody asked — and on the one day of the month the
 * salary lands, a headline balance of 9 000 zł sat over an income of 9 000 zł
 * as though the two were the same statement.
 */
data class TrendSeries(
    val points: List<TrendPoint>,
    val runningMinor: List<Long>,
    val monthIncomeMinor: List<Long> = emptyList(),
    val monthExpenseMinor: List<Long> = emptyList(),
) {
    /** Whether anything happened in the window at all. */
    val isEmpty: Boolean = points.all { it.incomeMinor == 0L && it.expenseMinor == 0L }

    val from: LocalDate get() = points.first().date
    val to: LocalDate get() = points.last().date

    /**
     * Where the line ends. This is the figure the face prints above the chart,
     * and it is that figure by construction rather than by agreement — see
     * [trendSeries], which counts BACKWARDS from it.
     */
    val closingMinor: Long = runningMinor.lastOrNull() ?: 0L

    /**
     * The turn of the month, when the window reaches back into the one before.
     * Marked because the line crossing it is the only place a reader can see
     * where "this month" began — the line itself no longer breaks there, since
     * a balance does not start again on the 1st.
     */
    val monthStartIndex: Int? =
        points.drop(1).indexOfFirst { it.date.dayOfMonth == 1 }.takeIf { it >= 0 }?.plus(1)

    /**
     * The vertical extent of the line — the data's own, not zero to the top.
     *
     * Zero used to be forced in from both sides, which was right when this was
     * a chart of a month's net and is wrong now that it is a chart of a
     * balance: a household holding 8 000 zł would get a line pinned along the
     * top of the box with every move it made flattened out of sight. Zero is
     * still on the chart whenever the line actually crosses it, which is the
     * only time an account balance makes it mean anything.
     */
    val lowRunningMinor: Long = runningMinor.minOrNull() ?: 0L
    val highRunningMinor: Long = runningMinor.maxOrNull() ?: 0L

    /** Whether break-even is inside the picture at all. */
    val crossesZero: Boolean = lowRunningMinor <= 0L && highRunningMinor >= 0L

    fun runningAt(index: Int): Long = runningMinor.getOrElse(index) { closingMinor }

    fun monthIncomeAt(index: Int): Long =
        monthIncomeMinor.getOrElse(index) { monthIncomeMinor.lastOrNull() ?: 0L }

    fun monthExpenseAt(index: Int): Long =
        monthExpenseMinor.getOrElse(index) { monthExpenseMinor.lastOrNull() ?: 0L }
}

/**
 * Turn the rows the database returned into one point per day, then into the
 * balance across them.
 *
 * Every day in the window gets a point whether or not anything happened on it.
 * A line drawn only through the days that had transactions would space Tuesday
 * and Friday the same distance apart as Tuesday and Wednesday, and the slope —
 * which is the whole message — would be a lie about how fast money went.
 *
 * The run is anchored at [endBalanceMinor] and counted BACKWARDS. That is the
 * fix for the card's oldest lie: the line used to be a cumulative net that
 * restarted at zero on the 1st, drawn under a heading that said "in the
 * accounts" — so a household with 7 975 zł tapped the last day of the line and
 * was told 2 820,63, which was September's net and not the balance of anything.
 * Anchoring means the two CANNOT disagree, whatever the window does.
 *
 * [deltas] is how far the balance moved each day, which is not the same as what
 * was earned and spent: a transfer between two accounts is neither, and it
 * still moves each of them. Empty means the unfiltered case, where the two
 * halves of every transfer cancel and the day's net is the day's move.
 *
 * [rows] may reach back before [from] — the caller extends it to the 1st of the
 * window's first month — so that a day in the window's leading tail can still
 * be told what its own month had done by then. Only [from]..[to] is plotted.
 *
 * Always returns at least one point, so callers cannot be handed a series with
 * no ends for the axis to label.
 */
fun trendSeries(
    rows: List<DailyTotals>,
    from: LocalDate,
    to: LocalDate,
    endBalanceMinor: Long = 0L,
    deltas: List<DailyDelta> = emptyList(),
): TrendSeries {
    val byDay = rows.associateBy { it.day }
    val last = if (to.isBefore(from)) from else to

    val points = ArrayList<TrendPoint>()
    val monthIncome = ArrayList<Long>()
    val monthExpense = ArrayList<Long>()
    var inSoFar = 0L
    var outSoFar = 0L
    // From the 1st, not from the window's start: the two figures under the
    // chart say "this month so far", and a month-to-date that began on the 21st
    // of the previous month would be neither the month nor the window.
    var day = from.withDayOfMonth(1)
    while (!day.isAfter(last)) {
        if (day.dayOfMonth == 1) {
            inSoFar = 0L
            outSoFar = 0L
        }
        val row = byDay[day.toString()]
        inSoFar += row?.incomeMinor ?: 0L
        outSoFar += row?.expenseMinor ?: 0L
        if (!day.isBefore(from)) {
            points += TrendPoint(day, row?.incomeMinor ?: 0L, row?.expenseMinor ?: 0L)
            monthIncome += inSoFar
            monthExpense += outSoFar
        }
        day = day.plusDays(1)
    }

    val deltaByDay = deltas.associate { it.day to it.deltaMinor }
    val running = arrayOfNulls<Long>(points.size)
    var balance = endBalanceMinor
    for (index in points.indices.reversed()) {
        running[index] = balance
        val point = points[index]
        balance -= if (deltas.isEmpty()) point.netMinor else deltaByDay[point.date.toString()] ?: 0L
    }

    return TrendSeries(
        points = points,
        runningMinor = running.map { it ?: 0L },
        monthIncomeMinor = monthIncome,
        monthExpenseMinor = monthExpense,
    )
}

/**
 * The window the chart covers for [period]: [TREND_DAYS] days ending on the last
 * day that month can honestly show — stretched back further when thirty days is
 * not enough to reach the first of the month.
 *
 * That stretch is what makes the chart agree with the card's front face on the
 * 31st of a 31-day month, where a plain thirty days would start on the 2nd and
 * the run would never see the 1st to restart on it. So the window is 30 days
 * most of the time and 31 on the last day of a long month.
 */
fun trendWindow(period: String, today: LocalDate = Dates.today()): ClosedRange<LocalDate> {
    val end = Dates.windowEnd(period, today)
    val thirty = end.minusDays((TREND_DAYS - 1).toLong())
    return minOf(thirty, Dates.firstDayOf(period))..end
}

/**
 * How far up the chart a balance of [value] sits, 0 at the bottom and 1 at the
 * top of the range [low]..[high].
 *
 * A flat month — every day the same balance — has no range to divide by, and is
 * drawn down the middle rather than pinned to an edge, where it would read as
 * the best or worst the month ever got.
 */
internal fun yFraction(value: Long, low: Long, high: Long): Float {
    if (high <= low) return 0.5f
    return ((value - low).toFloat() / (high - low).toFloat()).coerceIn(0f, 1f)
}

/**
 * Which day a touch at [x] across a chart [width] wide belongs to.
 *
 * Nearest point, not nearest column: the days are vertices on a line, the first
 * sitting on the left edge and the last on the right, so a touch belongs to
 * whichever vertex it is closest to. A drag that runs off either end stays on
 * the first or last day rather than returning a 31st that does not exist,
 * because a finger sliding past the end of the chart means "the end".
 */
internal fun dayIndexAt(x: Float, width: Float, count: Int): Int? {
    if (count <= 0 || width <= 0f) return null
    if (count == 1) return 0
    val step = width / (count - 1)
    return (x / step).roundToInt().coerceIn(0, count - 1)
}

/**
 * The balance over the window, drawn as one connected line.
 *
 * This is a picture of a budget rather than a ledger: payday is the step up,
 * and the long grind down between one and the next is the month being lived.
 * Both are read from the SLOPE, which is why every day gets a vertex even when
 * nothing happened on it, and why the line is never smoothed — a curve fitted
 * between two points invents balances the household never had.
 *
 * Above break-even the line and its fill are green, below it they are red, cut
 * at the zero line rather than coloured by where the month happens to end. A
 * month that dipped under and recovered says so.
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
    // the value from the first composition — and tapping a day a second time
    // would compare against a stale null and never let go of it.
    val focusedNow by rememberUpdatedState(focused)

    val aboveColor = MaterialTheme.colorScheme.primary
    val belowColor = MaterialTheme.colorScheme.error
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val count = series.points.size

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .pointerInput(count) {
                    detectTapGestures { offset ->
                        val hit = dayIndexAt(offset.x, size.width.toFloat(), count)
                        // Touching the same day again lets go of it, so the card
                        // can get back to the window's own figures without
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
                        // Consumed so the LazyColumn around it cannot steal the
                        // gesture halfway through a scrub.
                        change.consume()
                        onFocus(dayIndexAt(change.position.x, size.width.toFloat(), count))
                    }
                },
        ) {
            // Room for the stroke and for the focus dot at either extreme, so
            // the best and worst days of the window are not sliced in half by
            // the edge of the canvas.
            val padV = 8.dp.toPx()
            val usableH = size.height - padV * 2
            val low = series.lowRunningMinor
            val high = series.highRunningMinor
            val step = if (count > 1) size.width / (count - 1) else 0f

            fun px(index: Int): Float = if (count > 1) index * step else size.width / 2f
            fun py(value: Long): Float = padV + (1f - yFraction(value, low, high)) * usableH

            // Where an empty account would sit. It is only a place on this
            // chart when the line actually reaches it — a household that never
            // went overdrawn has a zero somewhere below the bottom edge, and
            // the fill is then simply the area under the balance.
            val zeroY = py(0L)

            // ONE run. The line is carried across the 1st, because a balance is
            // not reset by the calendar: the money in the account on the 31st is
            // the money in it on the 1st. The break that used to be drawn here
            // belonged to a chart of the month's net, which this no longer is.
            val whole = 0..series.points.lastIndex

            fun lineOf(segment: IntRange): Path = Path().apply {
                segment.forEach { index ->
                    val x = px(index)
                    val y = py(series.runningMinor[index])
                    if (index == segment.first) moveTo(x, y) else lineTo(x, y)
                }
            }

            // Closed down onto break-even, so the fill measures the distance
            // from an empty account rather than from the bottom of the canvas.
            if (whole.first != whole.last) {
                val area = lineOf(whole).apply {
                    lineTo(px(whole.last), zeroY)
                    lineTo(px(whole.first), zeroY)
                    close()
                }
                clipRect(top = 0f, bottom = zeroY) {
                    drawPath(area, aboveColor.copy(alpha = 0.16f))
                }
                clipRect(top = zeroY, bottom = size.height) {
                    drawPath(area, belowColor.copy(alpha = 0.16f))
                }
            }

            // Drawn only when it is really in range. Pinned to the bottom of a
            // chart the line never comes near, it would read as an axis the
            // balance was sitting on rather than as the threshold it is.
            if (series.crossesZero) {
                drawLine(
                    color = axisColor.copy(alpha = 0.35f),
                    start = Offset(0f, zeroY),
                    end = Offset(size.width, zeroY),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // The turn of the month, under a line that runs straight through it.
            // Fainter than break-even: it is where "this month" began, not a
            // threshold the balance is measured against.
            series.monthStartIndex?.let { index ->
                drawLine(
                    color = axisColor.copy(alpha = 0.22f),
                    start = Offset(px(index), 0f),
                    end = Offset(px(index), size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            val stroke = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            if (whole.first == whole.last) {
                // A window one day long is a single point, and a Path holding
                // one point strokes to nothing at all.
                val value = series.runningMinor[whole.first]
                drawCircle(
                    color = if (value < 0) belowColor else aboveColor,
                    radius = 1.75.dp.toPx(),
                    center = Offset(px(whole.first), py(value)),
                )
            } else {
                val line = lineOf(whole)
                clipRect(top = 0f, bottom = zeroY) { drawPath(line, aboveColor, style = stroke) }
                clipRect(top = zeroY, bottom = size.height) {
                    drawPath(line, belowColor, style = stroke)
                }
            }

            focused?.let { index ->
                val value = series.runningMinor.getOrNull(index) ?: return@let
                val x = px(index)
                val y = py(value)
                drawLine(
                    color = axisColor.copy(alpha = 0.30f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                // Ringed in the card's own colour so the dot stays legible
                // wherever on the line it lands, fill included.
                drawCircle(surfaceColor, radius = 5.5.dp.toPx(), center = Offset(x, y))
                drawCircle(
                    color = if (value < 0) belowColor else aboveColor,
                    radius = 3.5.dp.toPx(),
                    center = Offset(x, y),
                )
            }
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
