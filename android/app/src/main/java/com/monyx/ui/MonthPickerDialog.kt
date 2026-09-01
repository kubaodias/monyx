package com.monyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.monyx.R
import com.monyx.data.Dates

/**
 * Pick a month, any month.
 *
 * The arrows either side of the title are for a neighbouring month; they are a
 * poor way to reach last December, which is eight taps away and passes through
 * seven full reloads of the screen behind the dialog. This is the direct route:
 * a year on the header, twelve months under it, one tap to commit.
 *
 * Deliberately not a Material date picker. Those pick a DAY — the calendar grid
 * would offer thirty-one answers to a question with twelve, and the day chosen
 * would be thrown away.
 */
@Composable
fun MonthPickerDialog(
    period: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The year being browsed, which is not the year selected: paging to 2024 and
    // then closing without a tap must leave the selection where it was.
    var year by remember(period) { mutableIntStateOf(Dates.yearOf(period)) }
    val selectedYear = Dates.yearOf(period)
    val selectedMonth = Dates.monthOf(period)
    val today = Dates.today()
    val names = Dates.monthNames()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { year -= 1 }) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.month_picker_previous_year),
                        )
                    }
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { year += 1 }) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.month_picker_next_year),
                        )
                    }
                    // No wording, because there is nothing to word it in that is
                    // shorter than the button: "this month" is the whole label
                    // and the icon is the whole label too.
                    IconButton(onClick = { onSelect(Dates.currentPeriod()) }) {
                        Icon(
                            imageVector = Icons.Filled.Today,
                            contentDescription = stringResource(R.string.month_picker_current),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // Three across, four down. A hand-built grid rather than a
                // LazyVerticalGrid: twelve cells never scroll, and a lazy grid
                // inside a dialog has no height to measure against.
                (0 until 4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        (0 until 3).forEach { column ->
                            val month = row * 3 + column + 1
                            MonthCell(
                                label = names[month - 1],
                                selected = year == selectedYear && month == selectedMonth,
                                isToday = year == today.year && month == today.monthValue,
                                onClick = { onSelect(Dates.period(year, month)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    label: String,
    selected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .padding(vertical = 4.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) scheme.primary else scheme.surface)
            // The present month keeps a ring even when another month is chosen,
            // so the grid always says where "now" is relative to what is on
            // screen. Suppressed under the fill, which would only fight it.
            .then(
                if (isToday && !selected) {
                    Modifier.border(1.dp, scheme.primary, RoundedCornerShape(24.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) scheme.onPrimary else scheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected || isToday) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
