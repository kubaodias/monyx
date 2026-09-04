package com.monyx.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monyx.R
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Money
import com.monyx.ui.JumpToToday
import com.monyx.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The controls the add screen is made of, so that editing a transaction can be
 * made of the same ones.
 *
 * They used to be private to AddScreen, and the editor was a dialog that
 * reimplemented all of them smaller: an amount in an OutlinedTextField, the
 * categories as a horizontal strip of 40dp circles, the account as its own
 * chip row. Two screens that both mean "which category is this" have to look
 * the same, or the second one reads as a different question — and the small
 * copies were the ones people complained about.
 *
 * `internal`, not private: the editor lives in ui.transactions, and this is a
 * single-module app.
 */

/**
 * A Text, not a TextField. There is no focus to request and no keyboard to wait
 * for, which is the whole point.
 *
 * Tapping it brings the keypad back. There is no dialpad glyph beside it any
 * more: an icon that appears and disappears next to the largest number on the
 * screen is a second thing to read where the number was already the target,
 * and every screen that shows this figure opens the keys when it is tapped.
 */
@Composable
internal fun AmountDisplay(amount: AmountInput, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // The running total, not just the sign: "60,00 +" while the second
        // operand is being typed, so the amount so far never leaves the screen.
        amount.operatorLabel?.let { op ->
            val soFar = amount.pendingDisplay(LocalConfiguration.current.locales[0])
            Text(
                text = if (soFar == null) op else "$soFar $op",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = amount.display(LocalConfiguration.current.locales[0]),
                fontSize = 52.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.currency_suffix),
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
internal fun ContextChip(
    icon: (@Composable () -> Unit)?,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
internal fun CategoryGrid(
    categories: List<CategoryEntity>,
    selectedId: String?,
    colorOf: (CategoryEntity) -> Color,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(categories, key = { it.id }) { category ->
            val color = colorOf(category)
            val selected = category.id == selectedId
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(category.id) },
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (selected) color else color.copy(alpha = 0.16f))
                        .then(
                            if (selected) {
                                Modifier.border(2.dp, color, CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Palette.icon(category.icon),
                        contentDescription = null,
                        tint = if (selected) Color.White else color,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = category.name,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
internal fun AccountPickerDialog(
    accounts: List<AccountEntity>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_pick_account)) },
        text = {
            Column {
                accounts.forEach { account ->
                    Text(
                        text = account.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(account.id) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPickerDialog(
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // A month grid, with no floor and no ceiling. It used to refuse the future
    // on the grounds that an expense has already happened — true of a receipt
    // and false of the standing order leaving on Friday, the deposit due next
    // week, the flights already booked. A household budget is as much about
    // what is coming as what went, and the month totals are the place it has
    // to show up.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(utcMillisToLocalDate(it)) } ?: onDismiss()
                },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    ) {
        DatePicker(state = state, title = { JumpToToday(state) })
    }
}

/**
 * DatePicker hands back a UTC midnight, always. Reading it back in the household
 * timezone would land on the previous day for anywhere east of Greenwich —
 * Warsaw included — so it is read as UTC and only then treated as a plain date.
 */
internal fun utcMillisToLocalDate(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * The one control that commits the expense, and it says so in words.
 *
 * It used to be the tick in the corner of the keypad — filled, primary-coloured,
 * sitting exactly where a calculator puts "=". So the key that ended the ENTRY
 * looked identical to the key that ended the SUM, and nothing on screen said
 * which of the two a tap was about to do. The tick is now honestly "=", grey
 * with the other function keys, and this is the only filled thing on the screen.
 *
 * When it cannot save it says what is missing instead of sitting there dead:
 * saving needs an amount, an account AND a category, and the third one used to
 * be invisible — you typed a price, typed a note, and the only feedback was a
 * grey button that would not explain itself.
 *
 * The amount is on the label because a save button is the last thing read before
 * money is written down, and "47,50 zł" there catches the mis-tap that the verb
 * alone never would. The verb does NOT name the kind: Expense/Income is stated
 * on the same screen already, and repeating it on the button only made the
 * button longer.
 *
 * @param blocker the string naming what is still missing, or null when the
 *   transaction is savable. The caller works it out, because "what is missing"
 *   is a different list for a new row and an existing one.
 */
@Composable
internal fun SaveBar(
    blocker: Int?,
    amountMinor: Long,
    onSave: () -> Unit,
    enabled: Boolean = true,
) {
    val label = blocker?.let { stringResource(it) } ?: stringResource(
        R.string.add_save_amount,
        // The evaluated total, not the digits on screen: with "60 +" pending and
        // 40 typed, this reads 100,00 — which is what pressing it will write.
        Money.formatWithCurrency(amountMinor),
    )

    Button(
        onClick = onSave,
        enabled = blocker == null && enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            // Material greys disabled text to 38% opacity, which is fine for a
            // label nobody needs to read and wrong for one that is the entire
            // instruction. Disabled here means "unfinished", not "unavailable".
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(50.dp),
    ) {
        Text(text = label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
