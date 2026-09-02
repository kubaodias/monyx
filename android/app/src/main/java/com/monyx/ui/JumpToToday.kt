package com.monyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.Dates
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * "Today", for a calendar that has been scrolled somewhere else.
 *
 * A month grid can reach any date, which is what makes it easy to get lost in:
 * three flicks back and there is no way home except three flicks forward,
 * counting. This is the way home, and it is one tap.
 *
 * It goes in the picker's `title` slot, which is otherwise an empty band of
 * padding above the month name.
 *
 * Both the selection and the displayed month are set. Moving the selection
 * alone leaves the grid parked on whichever month was being browsed, showing no
 * selected day at all — which reads as the button having done nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JumpToToday(state: DatePickerState, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            enabled = enabled,
            onClick = {
                val today = Dates.today()
                state.displayedMonthMillis = utcMillis(today.withDayOfMonth(1))
                state.selectedDateMillis = utcMillis(today)
            },
        ) {
            Icon(Icons.Filled.Today, contentDescription = null)
            Text(
                text = stringResource(R.string.date_jump_today),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * The picker speaks UTC midnight, always — reading its answer back in the
 * household zone would land on the previous day anywhere east of Greenwich,
 * Warsaw included. So dates are handed to it the same way they are read from it.
 */
private fun utcMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
