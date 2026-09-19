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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 *
 * @param action a control on the left of the figure, or null for none. Null on
 *   the add screen — that screen's way out of the keypad is picking a category,
 *   which it is about to do anyway. The edit sheet passes the note button here,
 *   because a row opened for editing already HAS its category: tapping the
 *   amount there put the keys up over the note with nothing on screen that
 *   would take them down again, and a transfer has no grid to tap at all.
 */
@Composable
internal fun AmountDisplay(
    amount: AmountInput,
    onClick: () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Its own clickable wins over the row's, so the one control on this
        // line that does not mean "type the amount" does not have to be outside
        // the line to say so.
        action?.let { Box(modifier = Modifier.padding(bottom = 10.dp)) { it() } }
        AmountFigure(amount = amount, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AmountFigure(amount: AmountInput, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
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

/**
 * @param accent the colour this chip is *about*, or null for a neutral one.
 *   The account chip passes the account's own colour, so the wallet at the top
 *   of the add screen is the same green or blue as the button for that account
 *   on the overview — "which account is this going on" answered by the shape of
 *   the thing rather than by reading the word. Date and Repeat stay neutral:
 *   they are not colour-coded anywhere else in the app, and three tinted chips
 *   in a row would be three accents competing with the category grid below.
 */
@Composable
internal fun ContextChip(
    icon: (@Composable () -> Unit)?,
    label: String,
    onClick: () -> Unit,
    accent: Color? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            // The same 0.16 wash the unselected category pills use, so a tinted
            // chip reads as a quiet label rather than as a filled button.
            .background(accent?.copy(alpha = 0.16f) ?: MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * The categories, and — once one is chosen — its subcategories below them.
 *
 * It was one flat grid: every root followed by its children, so a household
 * with eleven families put fifty circles on screen and "Paliwo" sat beside "Dom
 * i ogród" as an equal, with nothing saying which belonged to which.
 *
 * Then it was a drill-down, which said the shape out loud but took the roots
 * away to do it: choosing the wrong family meant a trip back out through a
 * header, and the grid you were aiming at moved under your thumb between the
 * first tap and the second.
 *
 * So both, stacked. The roots never move. Choosing one opens a second block
 * under a rule, and the two blocks are visibly different things rather than one
 * long list. The subcategory step is optional in the honest sense — filing on
 * "Dom i ogród" and stopping there is a complete answer, and tapping the lit
 * child again puts it back on the parent.
 *
 * Which family is open is not state. It is read off the selection: the chosen
 * category, or its parent if the chosen one is a child. Two pieces of state for
 * one choice is how a picker ends up showing a parent from one family and a
 * child from another — and it is why this used to stay three taps deep in
 * whatever the last saved transaction was filed under.
 */
@Composable
internal fun CategoryGrid(
    categories: List<CategoryEntity>,
    selectedId: String?,
    colorOf: (CategoryEntity) -> Color,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Full width, after everything else — the add screen puts its note here.
     *
     * Inside the grid rather than under it, because under it the note took its
     * height out of the grid's window and sat on top of the subcategories: the
     * family you had just opened slid behind a text field. As the last item it
     * scrolls WITH the categories, never covers one, and the only thing that
     * can hide it is the keypad below.
     */
    footer: (@Composable () -> Unit)? = null,
) {
    val roots = remember(categories) {
        categories.filter { it.parentId == null }.sortedBy { it.sortOrder }
    }
    val childrenOf = remember(categories) {
        categories.filter { it.parentId != null }
            .sortedBy { it.sortOrder }
            .groupBy { it.parentId }
    }

    val selected = categories.firstOrNull { it.id == selectedId }
    val openRootId = selected?.let { it.parentId ?: it.id }
    val children = openRootId?.let { childrenOf[it] }.orEmpty()

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.fillMaxWidth(),
        // Tight, because every dp here is a dp of label width: at 12/6 a name
        // like "Zakupy spożywcze" wrapped on a phone where 8/4 fits it on one
        // line, and four columns multiply the saving by four.
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(roots, key = { it.id }) { category ->
            CategoryCell(
                category = category,
                color = colorOf(category),
                selected = category.id == selectedId,
                // The family whose children are showing below, when the choice
                // itself is one of those children. A ring rather than a fill:
                // it is not the answer, it is where the answer came from.
                open = category.id == openRootId && category.id != selectedId,
                onClick = { onSelect(category.id) },
            )
        }
        if (openRootId != null && children.isNotEmpty()) {
            item(key = "split", span = { GridItemSpan(maxLineSpan) }) {
                SubcategorySplit()
            }
            items(children, key = { it.id }) { category ->
                CategoryCell(
                    category = category,
                    color = colorOf(category),
                    selected = category.id == selectedId,
                    open = false,
                    // Tapping the lit one goes back to the parent. Optional has
                    // to be undoable or it is a second required step with a
                    // softer label.
                    onClick = { onSelect(if (category.id == selectedId) openRootId else category.id) },
                )
            }
        }
        footer?.let { content ->
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { content() }
        }
    }
}

/** One circle and its name, the shape both halves of the grid are made of. */
@Composable
private fun CategoryCell(
    category: CategoryEntity,
    color: Color,
    selected: Boolean,
    open: Boolean,
    onClick: () -> Unit,
) {
    // fillMaxWidth, or the cell is only as wide as its widest child and sits at
    // the start of the grid slot: "Dom" made a narrow column with the icon
    // centred over three letters, "Zakupy spożywcze" made a column the full
    // width of the slot, and the icons in one row stopped lining up.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (selected) color else color.copy(alpha = 0.16f))
                .then(
                    if (selected || open) {
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
            // 11sp default leading is ~15sp, which makes a wrapped name look
            // like two separate labels rather than one over two lines. Two
            // lines is the floor, not a failure: a narrow screen cannot fit
            // "Dom i ogród" beside three other columns, and truncating to
            // "Dom i o…" loses the word that distinguishes it from "Dom".
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Centring the Column centres the text BLOCK; it says nothing about
            // the lines inside it. Without this, "Zakupy spożywcze" wraps to two
            // lines whose width is set by the longer one, and the short line
            // hangs off its left edge — under the icon by accident.
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * The rule between the families and the family that is open.
 *
 * Without it the children read as a fifth row of roots — same circles, same
 * size, no boundary — and the grid grows a row every time somebody adds a
 * subcategory anywhere. A line and a word are enough to say these are the
 * inside of the one above.
 */
@Composable
private fun SubcategorySplit() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.add_pick_subcategory),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * Each account with its own icon and its own colour, the way the overview's
 * buttons and the chip that opens this dialog both draw it.
 *
 * It used to be a column of bare names. An account is the one thing in this app
 * that people recognise by colour before they read it — the strip at the top of
 * the overview is nothing but coloured icons — and the moment of choosing one
 * was the single place that made you read instead.
 */
@Composable
internal fun AccountPickerDialog(
    accounts: List<AccountEntity>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    selectedId: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_pick_account)) },
        text = {
            Column {
                accounts.forEach { account ->
                    val color = Palette.colorFor(account.color, account.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(account.id) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = account.icon?.let { Palette.icon(it) }
                                    ?: Icons.Filled.Wallet,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            text = account.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = if (account.id == selectedId) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                        )
                        // Which one is already on the transaction. Without it
                        // the dialog is a list of options with no answer in it,
                        // and the chip that opened it is now behind a scrim.
                        if (account.id == selectedId) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
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
