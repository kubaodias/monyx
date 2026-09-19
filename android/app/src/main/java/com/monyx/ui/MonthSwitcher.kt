package com.monyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.Dates

/**
 * A month with an arrow either side, and a picker behind the title.
 *
 * Overview, Budget and Transactions all step through months, and they now do it
 * with the SAME control over the SAME month — see [SelectedMonth]. Two
 * hand-rolled copies had already drifted into two different type sizes, which
 * reads as two different widgets rather than the one thing it is.
 *
 * It takes a [period] rather than a label because the title is no longer only a
 * label: tapping it opens [MonthPickerDialog], which needs the month it is
 * editing. Every way of changing the month — either arrow, any cell of the
 * grid, the jump to today — arrives at the caller through the one [onSelect].
 *
 * On a card, like every other panel on these screens. It used to float on the
 * background, which made the one control that governs all three screens the
 * only thing on them that did not look like a thing — it read as a heading
 * rather than as something to press. The card is the same shape, colour and
 * elevation the balance and breakdown cards use, and it is applied here rather
 * than at the three call sites so the control cannot drift apart again.
 */
@Composable
fun MonthSwitcher(
    period: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    val openPicker = stringResource(R.string.month_picker_open)

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            // Tighter than the other cards' 24: the arrows are 48dp touch
            // targets that already carry their own space, and padding them
            // again turns a control into a banner.
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSelect(Dates.shiftPeriod(period, -1)) }) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.overview_previous_month),
                )
            }
            Text(
                text = Dates.monthLabel(period),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                // The accent, not plain onSurface. It is the one word on the
                // screen that says what every figure below it is about, and it
                // is also a button — the colour does both jobs at once, and
                // matches the other things on these screens you can tap.
                //
                // On the accent's own wash, which is what finally makes it look
                // like the control it is. Green text alone was the same weight
                // of ink as the two arrows beside it; a pill has an edge, and
                // an edge is what the eye reads as "press here". The text stays
                // the deep green rather than going bright: the brand's green is
                // a fill colour, and the same hue as type on a near-white card
                // is a legibility problem before it is a branding one.
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    // onClickLabel rather than a contentDescription: the label
                    // describes the ACTION and leaves the month name as the node's
                    // text. Overriding the description would announce "choose a
                    // month" and swallow which month is on.
                    .clickable(onClickLabel = openPicker) { picking = true }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            IconButton(onClick = { onSelect(Dates.shiftPeriod(period, 1)) }) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.overview_next_month),
                )
            }
        }
    }

    if (picking) {
        MonthPickerDialog(
            period = period,
            onSelect = {
                picking = false
                onSelect(it)
            },
            onDismiss = { picking = false },
        )
    }
}
