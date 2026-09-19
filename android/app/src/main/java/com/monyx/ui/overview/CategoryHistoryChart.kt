package com.monyx.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monyx.R
import com.monyx.data.Dates
import com.monyx.data.Money
import com.monyx.ui.theme.Palette

/**
 * Twelve months of spending as stacked bars, one bar per month, each split into
 * the categories that made it up.
 *
 * Stacked rather than a line per category, and the reason is the phone: eight
 * lines over twelve months is a thicket, while a stack says two things at once
 * that are both worth saying — how much a month cost in total, and what the
 * difference between two months was actually made of. Hiding a category in the
 * legend takes its segment out of every bar, which is how the question "what
 * happens to the shape without the holiday?" gets asked.
 *
 * The bars are stacked by average across the window — never by the selected
 * month's own figures. A stack that reordered itself month to month would have
 * no shape to read at all.
 *
 * Which is why this is no longer the legend's order. The legend can be switched
 * to show one month and re-sorts itself when it is; the stack does not follow,
 * because the two are answering different questions and only one of them can
 * afford to move.
 *
 * A tap selects that month, for the whole screen. The chart is then the month
 * switcher as well as the history: the highlight is the month the pie chart
 * behind it, the totals above it and the ledger below it are all showing.
 */
@Composable
fun CategoryHistoryChart(
    history: CategoryHistory,
    hidden: Set<String>,
    budgetHidden: Boolean,
    onSelectMonth: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember(history.categories, hidden) {
        history.categories.filterNot { it.id in hidden }
    }
    val colors = remember(visible) {
        visible.associate { it.id to Palette.colorFor(it.color, it.id) }
    }
    val totals = remember(history.months, visible) {
        val ids = visible.map { it.id }.toSet()
        history.months.map { it.totalMinor(ids) }
    }
    val budgets = remember(history.months, hidden, budgetHidden) {
        if (budgetHidden) emptyList() else history.months.map { it.budgetMinor(hidden) }
    }
    // The axis clears the line as well as the bars. A budget drawn off the top
    // of the chart is worse than no budget at all: the months under it look
    // like the months over it.
    val ticks = remember(totals, budgets) {
        axisTicks(maxOf(totals.maxOrNull() ?: 0L, budgets.filterNotNull().maxOrNull() ?: 0L))
    }
    val ceiling = ticks.last().coerceAtLeast(1L)

    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(
        fontSize = 9.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val monthStyle = axisStyle
    val selectedMonthStyle = axisStyle.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    )

    // The gutter is as wide as the widest number that will sit in it. Measured
    // rather than guessed at: "12 000" in one language and "12,000" in another
    // are not the same width, and a fixed gutter clips one of them.
    val density = LocalDensity.current
    val gutterPx = remember(ticks, measurer, density) {
        ticks.maxOf { measurer.measure(Money.formatWhole(it), axisStyle).size.width } +
            with(density) { 6.dp.toPx() }
    }
    val labelHeightPx = remember(measurer, density) {
        measurer.measure("0", axisStyle).size.height + with(density) { 6.dp.toPx() }
    }

    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    // Softened, because it is a reference and not a reading: at full strength a
    // saturated red line over the bars reads as an alarm on every month it
    // crosses, including the ones that came in under it.
    val budgetColor = MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val monthNames = remember(history.months) { history.months.map { shortMonth(it.period) } }
    val count = history.months.size

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(196.dp)
            .pointerInput(count, gutterPx) {
                detectTapGestures { offset ->
                    monthIndexAt(offset.x, gutterPx, size.width.toFloat(), count)
                        ?.let { onSelectMonth(history.months[it].period) }
                }
            },
    ) {
        val plotLeft = gutterPx
        val plotWidth = size.width - plotLeft
        val plotBottom = size.height - labelHeightPx
        val plotTop = 4.dp.toPx()
        val plotHeight = plotBottom - plotTop
        if (plotWidth <= 0f || plotHeight <= 0f || count == 0) return@Canvas

        val slot = plotWidth / count
        val barWidth = slot * 0.62f

        // The selected month first, so every gridline and every bar is drawn
        // over its tint rather than under it.
        history.selectedIndex?.let { index ->
            val left = plotLeft + slot * index + (slot - barWidth) / 2f - 4.dp.toPx()
            drawRoundRect(
                color = highlightColor,
                topLeft = Offset(left, plotTop),
                size = Size(barWidth + 8.dp.toPx(), plotHeight + 2.dp.toPx()),
                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
            )
        }

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
                topLeft = Offset(
                    plotLeft - 6.dp.toPx() - text.size.width,
                    y - text.size.height / 2f,
                ),
            )
        }

        history.months.forEachIndexed { index, month ->
            val left = plotLeft + slot * index + (slot - barWidth) / 2f
            // A month still being lived is a part-month, and drawn as one: at
            // full strength its short bar reads as a fall in spending rather
            // than as a month that is not over yet.
            val alpha = if (month.partial) 0.62f else 1f
            var y = plotBottom
            visible.forEach { category ->
                val amount = month.byCategory[category.id] ?: 0L
                if (amount > 0L) {
                    val height = plotHeight * (amount.toFloat() / ceiling.toFloat())
                    y -= height
                    drawRect(
                        color = colors[category.id] ?: Color.Gray,
                        topLeft = Offset(left, y),
                        size = Size(barWidth, height),
                        alpha = alpha,
                    )
                }
            }

            val style = if (index == history.selectedIndex) selectedMonthStyle else monthStyle
            val label = measurer.measure(monthNames[index], style)
            drawText(
                textLayoutResult = label,
                topLeft = Offset(
                    left + (barWidth - label.size.width) / 2f,
                    plotBottom + 4.dp.toPx(),
                ),
            )
        }

        // The budget, last, so it is never buried under a bar that overshoots
        // it — the crossing is the whole point of the line.
        if (budgets.size != count) return@Canvas
        val points = budgets.mapIndexed { index, budget ->
            budget?.let {
                Offset(
                    plotLeft + slot * index + slot / 2f,
                    plotBottom - plotHeight * (it.toFloat() / ceiling.toFloat()),
                )
            }
        }
        points.forEachIndexed { index, point ->
            if (point == null) return@forEachIndexed
            val next = points.getOrNull(index + 1)
            if (next != null) {
                drawLine(
                    color = budgetColor,
                    start = point,
                    end = next,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // A month with no month beside it would otherwise draw nothing at
            // all: the first budget a household ever sets is one point wide.
            if (next == null && points.getOrNull(index - 1) == null) {
                drawCircle(color = budgetColor, radius = 2.5f.dp.toPx(), center = point)
            }
        }
    }
}

/**
 * The legend, in history mode: what each category costs in an average month.
 *
 * The same rows as the pie chart's legend and a different number in them — the
 * average across the window rather than this month's figure — because the chart
 * above is a year, and a row saying what September cost would be answering a
 * question nothing on screen is asking.
 *
 * A tap still opens that category's transactions, exactly as it does on the
 * pie: the row means the same thing on both faces of the card, which is the
 * only reason a legend can change what it counts without becoming a different
 * control. Hiding is the eye at the end of the row, and it is deliberately a
 * separate target — taking a colour out of the chart and going to look at the
 * rows behind it are different intentions, and a single tap cannot be both.
 *
 * What is hidden sinks to the bottom and stays hidden on this phone until it is
 * turned back on, across launches. [categories] arrives already in that order.
 *
 * Show all / Hide all sits in the header, because isolating one category out of
 * eight is otherwise seven taps.
 */
@Composable
fun CategoryHistoryLegend(
    categories: List<HistoryCategory>,
    hidden: Set<String>,
    /** What to print per category — see [legendAmounts]. */
    amounts: Map<String, Long>,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Directly under the switch and above the rows, so the two controls
        // that govern this list sit together instead of one topping the list
        // and the other tailing it.
        //
        // One control rather than two, because the two are never both useful:
        // with a full chart the only move is to clear it, and with anything
        // hidden the move anyone reaches for is to get it all back.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(
                    // Asked of the rows on screen, not of the stored set, which
                    // outlives them and can name categories this window has
                    // never heard of.
                    if (categories.none { it.id in hidden }) {
                        R.string.overview_history_hide_all
                    } else {
                        R.string.overview_history_show_all
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onToggleAll)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        categories.forEach { category ->
            val isHidden = category.id in hidden
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onOpen(category.id) }
                        // Dimmed and sunk to the bottom of the list, never
                        // removed — see legendOrder. The cost is that the row
                        // under the finger moves when the eye is tapped, so
                        // hiding two categories in a row is two deliberate
                        // taps rather than two quick ones in the same place.
                        .alpha(if (isHidden) 0.38f else 1f)
                        .padding(vertical = 6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Palette.colorFor(category.color, category.id)),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = Money.format(amounts[category.id] ?: 0L),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(
                    onClick = { onToggle(category.id) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (isHidden) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = stringResource(
                            if (isHidden) {
                                R.string.overview_history_show_one
                            } else {
                                R.string.overview_history_hide_one
                            },
                            category.name,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        LegendTotal(total = visibleTotal(categories, hidden, amounts))
    }
}

/**
 * What the rows above add up to — the hidden ones left out.
 *
 * Hidden is the whole point of the figure. The list is a set of categories you
 * have chosen to look at, and a total that quietly included the ones you took
 * off the chart would not be the total of anything on screen. It is the same
 * rule the bars and the budget line already follow.
 */
internal fun visibleTotal(
    categories: List<HistoryCategory>,
    hidden: Set<String>,
    amounts: Map<String, Long>,
): Long = categories.filterNot { it.id in hidden }.sumOf { amounts[it.id] ?: 0L }

/**
 * The sum, set off from the list by a rule rather than by weight alone.
 *
 * Indented to the same column as the category names — the coloured dot's width
 * plus its gap — so the figure lines up under the figures it adds, which is the
 * only thing that makes it readable as a total rather than one more row.
 */
@Composable
private fun LegendTotal(total: Long) {
    HorizontalDivider(
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(end = 36.dp, top = 2.dp, bottom = 6.dp),
    ) {
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = stringResource(R.string.overview_history_total),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = Money.format(total),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The average/this-month switch, shared by both faces of the breakdown card.
 *
 * Its own full-width row, and the two halves say what they MEAN rather than
 * naming the month. It was briefly "Średnio na miesiąc" beside "Maj 2026",
 * which left the reader to work out that a month name in that position stood
 * for that month's spending — the one thing the column is, and the one thing
 * the label did not say.
 *
 * Both faces use it and each remembers its own answer, because their sensible
 * defaults differ: a pie under a month switcher is about that month, and a
 * twelve-month chart is about a normal one.
 */
@Composable
internal fun AmountToggle(showsMonth: Boolean, onSelect: (LegendAmount) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(2.dp),
    ) {
        // This month on the left, because it is what the card opens on and what
        // the month switcher above the screen is pointing at; the average is
        // the alternative you go looking for.
        AmountSegment(
            text = stringResource(R.string.overview_history_this_month),
            selected = showsMonth,
            onClick = { onSelect(LegendAmount.SelectedMonth) },
            modifier = Modifier.weight(1f),
        )
        AmountSegment(
            text = stringResource(R.string.overview_history_average),
            selected = !showsMonth,
            onClick = { onSelect(LegendAmount.Average) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One half of the average/month switch.
 *
 * The unselected half is a live target, not a label: tapping either side is
 * how the switch is worked, and only one of them ever does anything. Kept
 * legible rather than dimmed to 38% — it is the thing you are being asked to
 * choose, and a choice you cannot read is not being offered.
 */
@Composable
private fun AmountSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/**
 * Which month a tap at [x] landed on, or null for the axis gutter.
 *
 * Columns, not vertices: a bar owns its whole slot including the gaps either
 * side of it, so the months tile the plot with nothing between them to miss.
 * Separate from the gesture handler so the arithmetic can be tested — an
 * off-by-one here selects the month next to the one that was tapped, which is
 * exactly the kind of thing that looks right on an emulator.
 */
internal fun monthIndexAt(x: Float, gutter: Float, width: Float, count: Int): Int? {
    if (count <= 0 || width <= gutter) return null
    if (x < gutter) return null
    val slot = (width - gutter) / count
    return ((x - gutter) / slot).toInt().coerceIn(0, count - 1)
}

/** "Wrz", in the interface language — the picker's own month names. */
private fun shortMonth(period: String): String =
    Dates.monthNames()[Dates.monthOf(period) - 1]
