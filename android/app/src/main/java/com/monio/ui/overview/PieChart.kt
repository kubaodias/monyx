package com.monio.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monio.R
import com.monio.data.Money
import kotlin.math.atan2
import kotlin.math.hypot

/** One slice of the breakdown: a top-level category and what it cost this month. */
data class PieSlice(
    val id: String,
    val label: String,
    val amountMinor: Long,
    val color: Color,
)

/**
 * The breakdown pie chart: Canvas plus drawArc, no charting library.
 * A donut — the ring leaves a hole — with the month's total in the middle,
 * and a legend below listing each slice's colour, name, amount and share.
 */
@Composable
fun PieChart(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    onSliceClick: (String) -> Unit = {},
) {
    val total = slices.sumOf { it.amountMinor }

    if (slices.isEmpty() || total <= 0) {
        Box(
            modifier = modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.overview_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Which slice the last tap landed on, so the ring can show what was hit
    // rather than silently navigating.
    var focused by remember(slices) { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .size(180.dp)
                    .pointerInput(slices, total) {
                        detectRingTaps(slices, total) { id ->
                            focused = id
                            onSliceClick(id)
                        }
                    },
            ) {
                val strokeWidth = 32.dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                var startAngle = -90f
                slices.forEach { slice ->
                    val sweep = 360f * slice.amountMinor.toFloat() / total.toFloat()
                    val isFocused = slice.id == focused
                    drawArc(
                        color = slice.color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        // The tapped slice thickens rather than moving: a real
                        // explode would change every other slice's hit area too.
                        style = Stroke(
                            width = if (isFocused) strokeWidth * 1.25f else strokeWidth,
                            cap = StrokeCap.Butt,
                        ),
                    )
                    startAngle += sweep
                }
            }
            Text(
                text = Money.formatWithCurrency(total),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            slices.forEach { slice ->
                val percent = (slice.amountMinor * 100 / total).toInt()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            focused = slice.id
                            onSliceClick(slice.id)
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(slice.color),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = slice.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = Money.format(slice.amountMinor),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.overview_percent, percent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(40.dp),
                    )
                }
            }
        }
    }
}

/**
 * Hit-testing the donut.
 *
 * Angles are measured the way drawArc lays slices out — clockwise from twelve
 * o'clock — and a tap outside the ring's radius is ignored rather than snapped
 * to the nearest slice, so the hole in the middle stays inert.
 */
private suspend fun PointerInputScope.detectRingTaps(
    slices: List<PieSlice>,
    total: Long,
    onSlice: (String) -> Unit,
) {
    detectTapGestures { offset ->
        if (total <= 0L) return@detectTapGestures
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dx = offset.x - cx
        val dy = offset.y - cy

        val strokeWidth = 32.dp.toPx()
        // PointerInputScope.size is an IntSize, so there is no minDimension here.
        val outer = minOf(size.width, size.height) / 2f
        val inner = outer - strokeWidth * 1.25f
        val distance = hypot(dx, dy)
        if (distance > outer || distance < inner) return@detectTapGestures

        // atan2 gives 0 at three o'clock counting anticlockwise; the ring starts
        // at twelve and runs clockwise.
        var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (degrees < 0f) degrees += 360f

        sliceIdAt(slices, total, degrees)?.let(onSlice)
    }
}

/**
 * Which slice covers [degrees], measured clockwise from twelve o'clock — the
 * same convention drawArc lays the ring out in.
 *
 * Separate from the gesture handler so it is testable: the wrap-around at 360
 * and the slice whose sweep rounds to zero are both easy to get wrong and
 * impossible to check reliably by tapping at an emulator.
 */
internal fun sliceIdAt(slices: List<PieSlice>, total: Long, degrees: Float): String? {
    if (total <= 0L || slices.isEmpty()) return null
    var start = 0f
    for (slice in slices) {
        val sweep = 360f * slice.amountMinor.toFloat() / total.toFloat()
        if (degrees >= start && degrees < start + sweep) return slice.id
        start += sweep
    }
    // Floating-point sweeps need not sum to exactly 360; anything landing in the
    // gap at the end belongs to the last slice that actually has a size.
    return slices.lastOrNull { it.amountMinor > 0 }?.id
}
