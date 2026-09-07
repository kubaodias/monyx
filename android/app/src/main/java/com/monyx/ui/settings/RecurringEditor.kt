package com.monyx.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monyx.R
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
import com.monyx.data.Money
import com.monyx.data.Recurrence
import com.monyx.ui.JumpToToday
import com.monyx.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Setting up a repeating transaction, full screen.
 *
 * It was an `AlertDialog` — nine controls, two date pickers and a scrolling body
 * inside a box a third of the screen tall, with the Save button pinned outside
 * the scroll so the field you were filling in and the button you were aiming for
 * were never both visible. A form this long is a screen, not a dialog.
 *
 * The category is chosen the way it is chosen on the keypad: a grid of its own
 * icons in its own colours, not a dropdown of names. Picking "Rent" out of a
 * menu of eleven words is the same number of taps and tells you nothing on the
 * way past.
 *
 * That colour used to run up into the header, the amount card, the chips and
 * the save button. It does not any more. The screen is the
 * theme's, like every other editor in the app; the category's colour marks the
 * category.
 *
 * It REPLACES the settings content rather than floating over it in a Dialog.
 * A full-screen Dialog has to be told how tall the screen is, and gets it wrong:
 * the window is placed below the status bar while its content is still measured
 * against the whole display, which put the save button a status bar's worth
 * below the bottom edge where nothing could reach it. Swapping the content has
 * no window of its own to measure, and the save bar then sits above the
 * navigation bar exactly like the keypad's does.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecurringEditor(
    seed: RuleSeed,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (RuleDraft) -> Unit,
) {
    var kind by remember { mutableStateOf(seed.kind) }
    var amountText by remember {
        mutableStateOf(seed.amountMinor?.takeIf { it > 0 }?.let { Money.format(it) } ?: "")
    }
    var accountId by remember { mutableStateOf(seed.accountId ?: accounts.firstOrNull()?.id) }
    var categoryId by remember { mutableStateOf(seed.categoryId) }
    var note by remember { mutableStateOf(seed.note.orEmpty()) }
    var freq by remember { mutableStateOf(seed.freq) }
    var startsOn by remember { mutableStateOf(seed.startsOn ?: Dates.today()) }
    var endsOn by remember { mutableStateOf(seed.endsOn) }
    var picking by remember { mutableStateOf<DateField?>(null) }

    /**
     * The earliest anchor the picker will offer.
     *
     * A rule is a statement about the future — it does not backfill — so a new
     * one cannot start before today. Left unbounded, a mis-scrolled year would
     * have the rule quietly write hundreds of transactions into a history nobody
     * asked for, sixty at a time, over many app opens.
     *
     * An existing rule keeps its own anchor as the floor instead. Otherwise
     * opening September's rent in November would show its start date greyed out
     * and unselectable, which reads as the app having broken the rule.
     */
    val anchorFloor = remember(seed) {
        val today = Dates.today()
        val original = seed.startsOn?.takeIf { seed.ruleId != null }
        if (original != null && original.isBefore(today)) original else today
    }

    val ofKind = remember(categories, kind) { categories.filter { it.kind == kind && it.deleted == 0 } }
    val amountMinor = Money.parseToMinor(amountText)
    val chosen = ofKind.firstOrNull { it.id == categoryId }

    // Family first, then — only if that family has one — the subcategory.
    //
    // One flat row of every category put "Dom i ogród" and "Transport > Paliwo"
    // side by side as equals, which is not what they are: nothing on screen said
    // Paliwo belonged to Transport, and the row grew a line every time anybody
    // added a subcategory anywhere. Two steps say the shape out loud, and the
    // second one is optional in the honest sense — filing the rent under "Dom i
    // ogród" and stopping there is a complete answer.
    //
    // There is still ONE stored value. [rootId] is derived from it rather than
    // held beside it: two pieces of state for one choice is how a picker ends up
    // showing a parent selected and a child from a different family.
    val roots = remember(ofKind) { ofKind.filter { it.parentId == null }.sortedBy { it.sortOrder } }
    val rootId = remember(ofKind, categoryId) {
        categoryId?.let { id -> ofKind.firstOrNull { it.id == id }?.parentId ?: id }
    }
    val children = remember(ofKind, rootId) {
        rootId?.let { r -> ofKind.filter { it.parentId == r }.sortedBy { it.sortOrder } }.orEmpty()
    }

    // A subcategory takes its parent's colour unless it has one of its own,
    // the same rule the keypad and the edit dialog draw these circles by.
    val colorOf: (CategoryEntity) -> Color = remember(categories) {
        val byId = categories.associateBy { it.id }
        val resolve: (CategoryEntity) -> Color = { c ->
            Palette.colorForChild(c.color, byId[c.parentId]?.color, c.parentId, c.id)
        }
        resolve
    }

    // The ONE coloured thing on the screen, and it is the category's circle —
    // the same mark the ledger will show. Everything else is the theme.
    //
    // The whole editor used to be painted in it: the app bar, the card behind
    // the amount, the selected chips, the save button, all animating from one
    // category's colour to the next. That made a form for setting up the rent
    // the loudest screen in the app, and it looked like nothing else — no other
    // editor here takes its colour from its content. The category colour is a
    // way to recognise a category at a glance, and it stops being that when it
    // is also the background, the button and the chrome.
    val chosenColor = chosen?.let(colorOf)

    // Which single thing is still missing, in the order a person fills the form
    // in. Naming it beats grey-and-silent: a disabled control that will not say
    // what it wants is the exact bug this screen's sibling had.
    val blocker: Int? = when {
        amountMinor <= 0 -> R.string.add_needs_amount
        accountId == null -> R.string.add_needs_account
        categoryId == null -> R.string.add_needs_category
        else -> null
    }

    Scaffold(
        // Zero, because the navigation Scaffold this screen lives in has
        // already applied them. Taking the status bar a second time pushes the
        // whole editor down by its height.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (seed.ruleId == null) R.string.recurring_add_title
                            else R.string.recurring_edit_title,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.settings_cancel),
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        bottomBar = {
            SaveButton(
                blocker = blocker,
                amountMinor = amountMinor,
                onSave = {
                    onSave(
                        RuleDraft(
                            kind = kind,
                            amountMinor = amountMinor,
                            accountId = accountId!!,
                            categoryId = categoryId!!,
                            note = note.trim().takeIf { it.isNotBlank() },
                            freq = freq,
                            startsOn = startsOn,
                            endsOn = endsOn,
                        ),
                    )
                },
            )
        },
            ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // The amount in the shape of the row it will write: the
            // category's own circle, then the figure. The card behind it is
            // the theme's surface, not the category's colour.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            chosenColor ?: MaterialTheme.colorScheme.surface,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Palette.icon(chosen?.icon),
                        contentDescription = null,
                        tint = if (chosen == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            Color.White
                        },
                    )
                }
                Spacer(Modifier.width(14.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.recurring_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            KindToggle(
                selected = kind,
                onSelect = {
                    kind = it
                    // Expense and income categories are different lists,
                    // so a category chosen under one kind is meaningless
                    // under the other. Same rule as AddViewModel.setKind.
                    categoryId = null
                },
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel(stringResource(R.string.add_pick_category))
            CategoryPicker(
                categories = roots,
                // The root is lit whether the rule is filed on the root itself
                // or on one of its children — otherwise choosing Paliwo would
                // leave the step above it looking unanswered.
                selectedId = rootId,
                colorOf = colorOf,
                // Straight to the root, dropping any subcategory: picking a new
                // family cannot keep the old family's child.
                onSelect = { categoryId = it },
            )

            if (children.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                FieldLabel(stringResource(R.string.add_pick_subcategory))
                CategoryPicker(
                    categories = children,
                    // Null while the rule sits on the parent itself, so nothing
                    // is lit and the step reads as genuinely unanswered rather
                    // than as answered with the first option.
                    selectedId = categoryId.takeIf { it != rootId },
                    colorOf = colorOf,
                    // Tapping the lit one puts the rule back on the parent.
                    // Optional has to be undoable or it is just a second
                    // required step with a softer label.
                    onSelect = { categoryId = if (it == categoryId) rootId else it },
                )
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel(stringResource(R.string.add_pick_account))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                accounts.forEach { account ->
                    FilterChip(
                        selected = account.id == accountId,
                        onClick = { accountId = account.id },
                        label = { Text(account.name) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel(stringResource(R.string.recurring_how_often))
            // FlowRow, not Row. Three chips reading "Every month" /
            // "Every week" / "Every year" overflow a narrow screen, and
            // a Row does not wrap — it compresses the last child until
            // "Every year" renders as a vertical column of single
            // letters. Wrapping also survives translation, which a
            // hand-tuned width would not.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Recurrence.FREQUENCIES.forEach { option ->
                    FilterChip(
                        selected = freq == option,
                        onClick = { freq = option },
                        label = { Text(labelForFreq(option)) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            DateField(
                label = stringResource(R.string.recurring_first_on),
                value = Dates.dayLabel(startsOn.toString()),
                onClick = { picking = DateField.Start },
                onClear = null,
            )
            Spacer(Modifier.height(8.dp))
            DateField(
                label = stringResource(R.string.recurring_until),
                value = endsOn?.let { Dates.dayLabel(it.toString()) }
                    ?: stringResource(R.string.recurring_no_end),
                onClick = { picking = DateField.End },
                onClear = endsOn?.let { { endsOn = null } },
            )
            if (freq == Recurrence.MONTHLY && startsOn.dayOfMonth >= 29) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.recurring_month_end_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.add_note_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    picking?.let { field ->
        RuleDatePickerDialog(
            selected = if (field == DateField.Start) startsOn else endsOn ?: startsOn,
            // An end date before the anchor is a rule that never fires, and the
            // server rejects it outright. Cheaper to make it unselectable.
            notBefore = if (field == DateField.End) startsOn else anchorFloor,
            onPick = { date ->
                if (field == DateField.Start) {
                    startsOn = date
                    // Dragging the anchor past the end date would strand the
                    // rule in the state the server refuses.
                    if (endsOn != null && endsOn!!.isBefore(date)) endsOn = null
                } else {
                    endsOn = date
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/**
 * The categories, as circles.
 *
 * Not a `LazyVerticalGrid`: this is inside a vertically scrolling column, and a
 * lazy grid there has no bounded height to measure against. A household has
 * a dozen categories, so laying all of them out costs nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(
    categories: List<CategoryEntity>,
    selectedId: String?,
    colorOf: (CategoryEntity) -> Color,
    onSelect: (String) -> Unit,
) {
    if (categories.isEmpty()) {
        Text(
            stringResource(R.string.settings_no_categories),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        categories.forEach { category ->
            val color = colorOf(category)
            val selected = category.id == selectedId
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(72.dp).clickable { onSelect(category.id) },
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (selected) color else color.copy(alpha = 0.16f))
                        .then(
                            if (selected) Modifier.border(2.dp, color, CircleShape) else Modifier,
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
                    // The column is 72dp wide and centred, so a one-line name
                    // sits under its icon on its own. A name that wraps does
                    // not: the block is as wide as its longer line and the
                    // shorter one hangs off the left of it.
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun KindToggle(selected: String, onSelect: (String) -> Unit) {
    val options = listOf("expense" to R.string.add_expense, "income" to R.string.add_income)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(label),
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * Saves, or says what is still missing.
 *
 * Same bargain as the keypad's save button: a control that refuses to act and
 * refuses to explain is the bug, not the guard.
 */
@Composable
private fun SaveButton(blocker: Int?, amountMinor: Long, onSave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Button(
            onClick = onSave,
            enabled = blocker == null,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(52.dp),
        ) {
            Text(
                text = blocker?.let { stringResource(it) }
                    ?: stringResource(
                        R.string.add_save_amount,
                        Money.formatWithCurrency(amountMinor),
                    ),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** Which of the two date fields the picker is currently editing. */
private enum class DateField { Start, End }

/** Shared with the rule list, which names the same frequencies. */
@Composable
internal fun labelForFreq(freq: String): String = stringResource(
    when (freq) {
        Recurrence.WEEKLY -> R.string.recurring_freq_weekly
        Recurrence.YEARLY -> R.string.recurring_freq_yearly
        else -> R.string.recurring_freq_monthly
    },
)

@Composable
private fun DateField(
    label: String,
    value: String,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            )
            onClear?.let {
                TextButton(onClick = it) { Text(stringResource(R.string.recurring_clear_end)) }
            }
        }
    }
}

/**
 * The mirror image of the add screen's picker, which blocks the future because
 * an expense has already happened. A rule is the opposite kind of thing — it is
 * about what has NOT happened yet, so "starts next month" is ordinary and the
 * floor sits at the near end instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleDatePickerDialog(
    selected: LocalDate,
    notBefore: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableYear(year: Int): Boolean =
                notBefore == null || year >= notBefore.year

            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                notBefore == null || !utcMillisToLocalDate(utcTimeMillis).isBefore(notBefore)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(utcMillisToLocalDate(it)) } },
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
 * The picker reports UTC midnight for the day the user tapped. Reading it back
 * in the local zone can land on the day before, so it is read in UTC and only
 * then treated as a plain calendar date.
 */
private fun utcMillisToLocalDate(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
