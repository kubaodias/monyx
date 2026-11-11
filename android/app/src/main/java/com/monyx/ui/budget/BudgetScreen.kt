package com.monyx.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monyx.MonyxApp
import androidx.compose.ui.text.style.TextOverflow
import com.monyx.R
import com.monyx.ui.add.AmountDisplay
import com.monyx.ui.add.AmountInput
import com.monyx.ui.add.Keypad
import com.monyx.ui.add.KeypadHeight
import com.monyx.ui.add.press
import com.monyx.data.BudgetUsage
import com.monyx.data.CategoryEntity
import com.monyx.data.Money
import com.monyx.sync.SyncWorker
import com.monyx.ui.MonthSwitcher
import com.monyx.ui.SyncPullToRefresh
import com.monyx.ui.theme.Palette
import kotlinx.coroutines.launch

/** What the edit dialog needs, whether it came from an existing budget row or
 *  from picking a not-yet-budgeted category. */
private data class EditTarget(
    val categoryId: String,
    val name: String,
    val icon: String?,
    val color: String?,
    /** Null means no limit is set yet for this period. */
    val limitMinor: Long?,
)

/**
 * Categories with usage bars, editing limits for the month.
 *
 * [initialCategoryId] and [initialPeriod] exist because a budget-alert
 * notification deep-links straight to a category's budget for a month:
 * tapping it should land here with that category's edit dialog already open.
 */
@Composable
fun BudgetScreen(
    initialCategoryId: String? = null,
    initialPeriod: String? = null,
    /**
     * The category's rows: its id, the month, and the account every figure here
     * is counted from. See [BudgetViewModel.budgetAccountId] — the ledger has to
     * be narrowed the same way the bar was.
     */
    onOpenCategoryTransactions: (String, String, String?) -> Unit,
    /** Pull-to-refresh. See [SyncPullToRefresh]. */
    onSyncRequested: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MonyxApp
    val viewModel: BudgetViewModel = viewModel(
        factory = BudgetViewModel.factory(app.repository, app.selectedMonth, app.budgetPreferences),
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val period by viewModel.period.collectAsStateWithLifecycle()
    val budgetUsage by viewModel.budgetUsage.collectAsStateWithLifecycle()
    val availableToAdd by viewModel.availableToAdd.collectAsStateWithLifecycle()

    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val countedAccountId by viewModel.budgetAccountId.collectAsStateWithLifecycle()

    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var showAddPicker by remember { mutableStateOf(false) }
    var editingPlan by remember { mutableStateOf(false) }
    var planSuggestionMinor by remember { mutableStateOf(0L) }
    var deepLinkHandled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialPeriod) {
        if (!initialPeriod.isNullOrBlank()) viewModel.setPeriod(initialPeriod)
    }

    // Auto-open the edit dialog for the deep-linked category, once its data
    // has arrived from either list — whichever one turns out to hold it.
    LaunchedEffect(budgetUsage, availableToAdd, initialCategoryId) {
        if (deepLinkHandled || initialCategoryId == null) return@LaunchedEffect
        val existing = budgetUsage.find { it.categoryId == initialCategoryId }
        if (existing != null) {
            editTarget = EditTarget(existing.categoryId, existing.name, existing.icon, existing.color, existing.limitMinor)
            deepLinkHandled = true
            return@LaunchedEffect
        }
        val notYetBudgeted = availableToAdd.find { it.id == initialCategoryId }
        if (notYetBudgeted != null) {
            editTarget = EditTarget(notYetBudgeted.id, notYetBudgeted.name, notYetBudgeted.icon, notYetBudgeted.color, null)
            deepLinkHandled = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Outside the list, so it stays put while the budgets scroll under it.
        // It is the control that says which month every row below belongs to,
        // and scrolling it away left a screen of figures with nothing on it
        // saying what they were figures FOR — the same reason Transactions has
        // always kept it pinned.
        MonthSwitcher(
            period = period,
            onSelect = viewModel::setPeriod,
            // Matches Overview and Transactions: the switcher is one control on
            // three screens and has to start at the same x on each.
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
        )
        SyncPullToRefresh(onSyncRequested = onSyncRequested) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                item {
                    PlanCard(
                        plan = plan,
                        onEditPlan = {
                            scope.launch {
                                planSuggestionMinor = viewModel.suggestedPlanMinor()
                                editingPlan = true
                            }
                        },
                    )
                }

                item { NotificationPermissionGate(modifier = Modifier.fillMaxWidth()) }

                if (budgetUsage.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.budget_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    // Above the rows and below the plan, where it reads as a
                    // property of the list rather than of the month. Not shown
                    // at all for an empty budget: there is nothing to order, and
                    // the control would be the only thing on the screen that
                    // worked.
                    item {
                        SortRow(sort = sort, onSelect = viewModel::setSort)
                    }
                    items(budgetUsage, key = { it.categoryId }) { usage ->
                        BudgetRow(
                            usage = usage,
                            onEdit = {
                                editTarget = EditTarget(usage.categoryId, usage.name, usage.icon, usage.color, usage.limitMinor)
                            },
                            onOpenTransactions = {
                                onOpenCategoryTransactions(usage.categoryId, period, countedAccountId)
                            },
                        )
                    }
                }

                if (availableToAdd.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAddPicker = true },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    text = stringResource(R.string.budget_add_category),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            }
    }

    editTarget?.let { target ->
        EditBudgetLimitDialog(
            target = target,
            onDismiss = { editTarget = null },
            onSave = { minor, alsoFuture ->
                scope.launch {
                    viewModel.setBudget(target.categoryId, minor, alsoFuture)
                    SyncWorker.enqueue(context)
                }
                editTarget = null
            },
            onClear = {
                scope.launch {
                    viewModel.clearBudget(target.categoryId)
                    SyncWorker.enqueue(context)
                }
                editTarget = null
            },
        )
    }

    if (editingPlan) {
        EditPlanDialog(
            // What a month with no plan opens on: whatever has already arrived,
            // scoped to the accounts this screen counts. The repository's
            // suggestion — the last plan made — is the fallback for a month
            // nothing has landed in yet, which is the one case where repeating
            // last month is a sensible guess. Offering 23 431,00 from a closed
            // October while 24 450,00 sat in November's own income was two
            // suggestions from two different months in one sheet.
            initialMinor = when {
                plan.hasPlan -> plan.plannedMinor
                plan.incomeMinor > 0 -> plan.incomeMinor
                else -> planSuggestionMinor
            },
            assignedMinor = plan.assignedMinor,
            carryOverMinor = plan.carryOverMinor,
            incomeMinor = plan.incomeMinor,
            hasPlan = plan.hasPlan,
            onDismiss = { editingPlan = false },
            onClear = {
                scope.launch {
                    viewModel.clearPlan()
                    SyncWorker.enqueue(context)
                }
                editingPlan = false
            },
            onSave = { minor ->
                scope.launch {
                    viewModel.setPlan(minor)
                    SyncWorker.enqueue(context)
                }
                editingPlan = false
            },
        )
    }

    if (showAddPicker) {
        AddCategoryPickerDialog(
            categories = availableToAdd,
            onDismiss = { showAddPicker = false },
            onPick = { category ->
                showAddPicker = false
                editTarget = EditTarget(category.id, category.name, category.icon, category.color, null)
            },
        )
    }
}

/**
 * Which order the limits are in, and the menu that changes it.
 *
 * A menu rather than a row of chips: four orders is more words than fit across a
 * phone, only one of them is ever in force, and the one in force is worth
 * naming on screen — a bare icon would leave a list that is not in the
 * household's own order with nothing on it saying why. It sits at the end of the
 * row, like every other control in the app that modifies the list below it.
 */
@Composable
private fun SortRow(sort: BudgetSort, onSelect: (BudgetSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { open = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = stringResource(R.string.budget_sort),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(sort.labelRes))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                BudgetSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelRes)) },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                        trailingIcon = {
                            // The same tick this app uses for "this one is
                            // chosen" in every other picker.
                            if (option == sort) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The month on one card, as a column sum.
 *
 * The figures were four equal lines under two headlines, and nothing on screen
 * said which of them added up. The household's own reading of it: "couldn't it
 * be a column, like primary-school arithmetic, with rules that total and
 * separate at the same time". They are right, and the form is not decoration —
 * it is a claim the old layout was making silently and sometimes wrongly:
 *
 *     Wpłynęło                   24 450,00
 *   + Z poprzedniego miesiąca       387,19
 *     ──────────────────────────────────────
 *     Budżet na miesiąc          24 837,19
 *   − Wydane                      6 424,33
 *     ──────────────────────────────────────
 *     Zostało do wydania
 *     18 412,86
 *
 * Writing it as a sum forced out one thing the card had been hiding. The pot is
 * `max(forecast, income) + carry-over`, so in a month whose forecast still
 * leads — December planned in November, 30 000 expected against 2 000 in — a
 * column headed "Wpłynęło 2 000" would not add up to the total under the rule.
 * The first term is therefore whichever figure WON, with what has arrived so
 * far as a sub-line under it. A column cannot lie about its own addends.
 *
 * **One figure leads, but the other one has an address.** "Left to plan" was a
 * headline beside this one until 0.29.1 and lost that place for a reason the
 * sum makes plain: it is not the next step of this column. Spending and
 * assigning are two branches off the SAME subtotal — both come off the budget,
 * neither off the other — so a column that subtracted one after the other would
 * read as arithmetic and produce nonsense.
 *
 * It does not follow that it belongs at the FOOT, which is where it went, and
 * that was wrong in the household's own words: "budżet na miesiąc jest
 * powiązany z zaplanowane i ponad budżet lub pozostało do zaplanowania".
 * Printed under the headline it was attached by position to "zostało do
 * wydania", a figure it is not computed from and does not move with; the reader
 * had to know to look two lines up. It now sits against the subtotal it comes
 * from, between that line and the subtraction that is the other branch — as a
 * tile, not a term, because a term of the column would claim to be the next
 * step of the sum. See [PlanningTile].
 *
 * Two things the headline does rather than print a negative: it says "over by"
 * the way every row of the list below it has always said it, and on a month
 * that has ended it says "unspent" instead of offering money back out of a
 * month nobody can spend in any more.
 */
@Composable
private fun PlanCard(plan: PlanState, onEditPlan: () -> Unit) {
    val overspent = plan.overspentMinor
    val headlineLabel = when {
        overspent != null -> stringResource(R.string.budget_plan_overspent)
        plan.hasPot && plan.closed -> stringResource(R.string.budget_plan_unspent)
        else -> stringResource(R.string.budget_plan_left_to_spend)
    }
    val headlineColor = when {
        // Red is reserved for an overshoot worth acting on. The word above the
        // figure still says "przekroczono o 0,78" either way — that is a fact,
        // and the rows below have separated the fact from the alarm since 0.21.
        plan.seriouslyOverspent -> MaterialTheme.colorScheme.error
        // Over by a rounding error lands in the same amber as 80–100%, never in
        // the neutral colour: the escalation has to stay monotone, and a month
        // a hair past its money cannot read calmer than one at 95% of it.
        overspent != null -> MaterialTheme.colorScheme.tertiary
        // A month that ended inside its means is this screen's good outcome,
        // and it gets the colour "left to assign" used to take for zero.
        plan.hasPot && plan.closed -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEditPlan),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (!plan.hasPot) {
                HeadlineRow(headlineLabel, "—", headlineColor, onEditPlan)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.budget_plan_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // The first term: whichever of the two the pot was taken from, so
            // that the figures above the rule add up to the one below it.
            val forecast = plan.forecastMinor
            if (forecast == null) {
                SumRow(
                    label = stringResource(R.string.budget_plan_income),
                    value = Money.formatWithCurrency(plan.incomeMinor),
                )
            } else {
                SumRow(
                    label = stringResource(R.string.budget_plan_total),
                    value = Money.formatWithCurrency(forecast),
                    hint = stringResource(
                        R.string.budget_plan_income_so_far,
                        Money.formatWithCurrency(plan.incomeMinor),
                    ),
                )
            }
            if (plan.carryOverMinor != 0L) {
                // The sign is the operator, in its own slot, not a minus glued
                // to a figure — which is what a month that closed in the red
                // used to be: "-5 128,98" printed in red, as if a shortfall
                // being carried were an error rather than a subtraction.
                SumRow(
                    sign = if (plan.carryOverMinor > 0) "+" else "−",
                    label = stringResource(R.string.budget_plan_carry_over),
                    value = Money.formatWithCurrency(kotlin.math.abs(plan.carryOverMinor)),
                )
            }
            SumRule()
            SumRow(
                label = stringResource(R.string.budget_plan_pot),
                value = Money.formatWithCurrency(plan.availableMinor),
                emphasis = true,
            )
            // The other branch off the line above, drawn where it branches.
            // It used to be the smallest, greyest text on the card, three
            // figures and a progress bar below the number it has nothing to do
            // with — so the one line that is a TASK was the one the eye found
            // last, in the week of the month when it is the only line that
            // matters.
            PlanningTile(plan)
            SumRow(
                sign = "−",
                label = stringResource(R.string.budget_plan_spent),
                value = Money.formatWithCurrency(plan.spentMinor),
            )
            SumRule()

            Spacer(Modifier.height(6.dp))
            HeadlineRow(
                label = headlineLabel,
                // The size, not the sign: the word above says which way it
                // points.
                value = Money.formatWithCurrency(overspent ?: plan.leftToSpendMinor ?: 0),
                color = headlineColor,
                onEditPlan = onEditPlan,
            )
            Spacer(Modifier.height(8.dp))
            // How much of the month's money has gone. Against the whole pot,
            // not against the forecast: a bar reading 82% under a headline
            // reading -767,74 is the bar contradicting the figure it sits
            // beneath. Capped at 1f, because going over is said in red by the
            // words, not by a bar drawing past its own end — and a hole bigger
            // than the pot leaves nothing to divide by, where a full bar is the
            // honest answer rather than an empty one.
            val spentFraction = when {
                plan.availableMinor > 0 ->
                    (plan.spentMinor.toFloat() / plan.availableMinor.toFloat()).coerceIn(0f, 1f)
                plan.spentMinor > 0 -> 1f
                else -> 0f
            }
            LinearProgressIndicator(
                progress = { spentFraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                // The same three bands a row draws, and in the same colours:
                // red past the alarm threshold, amber from 80% to it, primary
                // below. The card had two bands and the row three, which is how
                // a card could be shouting above a list of rows all saying they
                // were fine.
                color = when {
                    plan.seriouslyOverspent -> MaterialTheme.colorScheme.error
                    spentFraction >= 0.8f -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

/**
 * The planning branch, as a tile under the subtotal it is taken from.
 *
 * Everything about it is chosen to be found without being looked for, because
 * for three days a month this is the only line on the card with anything to do:
 *
 *  * **It sits under "Budżet na miesiąc"**, not under the headline. Both are
 *    `budget − something`; only one of them was next to the budget.
 *  * **It is a tile, not a row of the column.** The sum's terms are plain lines
 *    with an operator; this has a fill and no operator, so it cannot be read as
 *    the next step of the arithmetic it interrupts. Its background bleeds
 *    [TileBleed] past the card's padding on both sides, which is the whole
 *    reason for [drawBehind] here: the figure inside it still has to end on the
 *    same x as every figure above and below, and a tile with ordinary padding
 *    would have put it 12dp short of the column it belongs to.
 *  * **The figure is [MaterialTheme.typography.titleMedium], not bodySmall.**
 *    It was the smallest type on a card of numbers.
 *  * **The fill carries the state**, and goes quiet when there is nothing to do:
 *    a month handed out to the grosz, or one that has ended, is grey. Eleven
 *    days in twelve this tile is background; the colour means "your move".
 *
 * Over budget is said as a size and in a label, never as a minus glued to a
 * figure — the same rule the rows have followed since 0.21, and the rule the
 * foot line broke with "do zaplanowania −0,78 zł".
 */
@Composable
private fun PlanningTile(plan: PlanState) {
    val state = plan.planningState ?: return
    val leftToAssign = plan.leftToAssignMinor ?: return
    val over = state == PlanningState.Over || state == PlanningState.SeriouslyOver
    // The same three bands the rows and the bar above draw, in the same
    // colours. Settled borrows the card's quiet grey instead of a fourth one.
    val accent = when (state) {
        PlanningState.SeriouslyOver -> MaterialTheme.colorScheme.error
        PlanningState.Over -> MaterialTheme.colorScheme.tertiary
        PlanningState.Settled -> MaterialTheme.colorScheme.onSurfaceVariant
        PlanningState.ToPlan -> MaterialTheme.colorScheme.primary
    }
    // A wash of the accent rather than a container role: this scheme names
    // primary, tertiary and error and leaves the *Container roles to Material's
    // baseline, which is a lavender nobody here chose. The row icons have taken
    // their tint the same way since the first version.
    val fill = if (state == PlanningState.Settled) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        accent.copy(alpha = 0.12f)
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val bleed = TileBleed.toPx()
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(-bleed, 0f),
                    size = Size(size.width + bleed * 2, size.height),
                    cornerRadius = CornerRadius(TileCorner.toPx()),
                )
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = if (over) {
                    stringResource(R.string.budget_plan_over_budget)
                } else {
                    stringResource(R.string.budget_plan_left_to_assign)
                },
                style = MaterialTheme.typography.titleSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // What the figure above is the remainder of, so the tile answers
            // "and how much did I hand out, then?" without a second tap.
            Text(
                text = stringResource(
                    R.string.budget_plan_assigned_so_far,
                    Money.formatWithCurrency(plan.assignedMinor),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = Money.formatWithCurrency(if (leftToAssign < 0) -leftToAssign else leftToAssign),
            softWrap = false,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
    }
    Spacer(Modifier.height(8.dp))
}

/** How far the tile's fill reaches past the card's own padding, on each side. */
private val TileBleed = 12.dp

/** Rounded enough to read as a panel, not so much that it reads as a button. */
private val TileCorner = 10.dp

/**
 * The card's one leading figure: the last line of the column, in the size that
 * says it is the answer.
 *
 * It is right-aligned, like every term above it, because a result that does not
 * end on the same x as the figures it was made from is not the bottom of a sum —
 * it is a second, unrelated number. `softWrap = false` on the amount: a figure
 * that wraps mid-number is worse than one that is clipped.
 */
@Composable
private fun HeadlineRow(
    label: String,
    value: String,
    color: Color,
    onEditPlan: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The pencil goes in the margin, on the left. The right edge of this
        // card belongs to the figures, and a 48dp touch target standing where
        // the result ends would push the one figure that answers the column out
        // of line with every term in it.
        IconButton(onClick = onEditPlan) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.budget_plan_edit))
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                softWrap = false,
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

/**
 * One line of the column: an operator, what it is, and how much.
 *
 * The operator sits in a fixed slot to the left of the label rather than beside
 * the amount, so every digit in the card lines up under every other digit —
 * which is the whole point of writing it as a sum. `tnum` does the rest.
 */
@Composable
private fun SumRow(
    label: String,
    value: String,
    sign: String? = null,
    emphasis: Boolean = false,
    hint: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = sign ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(SignSlot),
        )
        // Exactly one weighted child, and it is the label. The amount is
        // measured first, at whatever width it needs, and the label takes all
        // the rest — so every figure in the card ends on the same x, which is
        // the only reason to write it as a column at all.
        //
        // This was briefly `weight(1f, fill = false)` beside a `weight(1f)`
        // spacer, and both halves of that were wrong: the free space split in
        // two, so the longest label was cut to "Budżet na miesi…" with room to
        // spare, and the slack the label did not use was left standing, putting
        // each row's amount at a different x. The label still ellipsises when
        // it genuinely cannot fit — it gives way, never the figure.
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                softWrap = false,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The width the operators get, so the labels start on the same x with or without one. */
private val SignSlot = 14.dp

/**
 * The rule that totals and separates at once.
 *
 * Over the figures only, not across the card: full width reads as a section
 * divider, and this is an underline in a notebook. Two thirds, so it clears the
 * longest label this card has.
 */
@Composable
private fun SumRule() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .weight(2f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun BudgetRow(
    usage: BudgetUsage,
    onEdit: () -> Unit,
    onOpenTransactions: () -> Unit,
) {
    // A limit of nothing is a real limit — "do not spend here" — so any spending
    // against it fills the bar rather than leaving it empty. Dividing by the
    // limit cannot answer that, and an empty bar beside the words "over by 40 zl"
    // reads as a bug.
    val pct = when {
        usage.limitMinor > 0 -> usage.spentMinor.toFloat() / usage.limitMinor.toFloat()
        usage.spentMinor > 0 -> 1f
        else -> 0f
    }
    val over = usage.spentMinor > usage.limitMinor
    // Over by less than one per cent is over by a rounding error: a 1 650 zł
    // limit missed by four złoty is not the same event as one missed by four
    // hundred, and painting both of them red taught the eye to ignore red.
    //
    // It lands in the SAME amber as 80–100%, not in grey. Grey would read as
    // calmer than 95% and the escalation has to stay monotone — an overshoot
    // cannot look like better news than nearly getting there.
    val seriouslyOver = overBudgetBand(usage.spentMinor, usage.limitMinor) == BudgetBand.Over
    val barColor = when {
        seriouslyOver -> MaterialTheme.colorScheme.error
        pct >= 0.8f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    // Only against a real limit. Spending 40 zł where nothing was allowed is
    // infinitely over, and "4000%" is a number nobody can do anything with —
    // the line below already says it in words.
    val percent = if (usage.limitMinor > 0) (pct * 100).roundToInt() else null
    val tint = Palette.colorForChild(usage.color, usage.parentColor, usage.parentId, usage.categoryId)

    // Tapping the row asks the question the row provokes — "what did I spend it
    // on?" — and the pencil changes the limit. It was the other way round, which
    // put the rarer action on the whole card and the commoner one behind a
    // chevron that looked like decoration.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenTransactions),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = Palette.icon(usage.icon), contentDescription = null, tint = tint)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(usage.name, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                SpendBar(fraction = pct, color = barColor)
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.budget_spent_of,
                            Money.format(usage.spentMinor),
                            Money.format(usage.limitMinor),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // The figure the bar is drawn from, in words, so "how far
                    // over?" has an answer that does not depend on reading a
                    // length. Over the limit it is the only place the scale of it
                    // is stated as a proportion rather than as an amount.
                    percent?.let {
                        Text(
                            text = stringResource(R.string.budget_percent, it),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = if (seriouslyOver) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (seriouslyOver) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Text(
                    text = if (over) {
                        stringResource(R.string.budget_over, Money.format(usage.spentMinor - usage.limitMinor))
                    } else {
                        stringResource(R.string.budget_left, Money.format(usage.limitMinor - usage.spentMinor))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    // The words still say "over by 4,20 zł" — that is a fact.
                    // Only the alarm colour is reserved for an overshoot worth
                    // acting on.
                    color = if (seriouslyOver) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.budget_edit_limit),
                )
            }
        }
    }
}

/**
 * How much of the limit has gone, and — past it — how far past.
 *
 * Under the limit the bar is the limit and the fill is the spending, which is
 * what a progress bar always was. Over it the bar becomes the SPENDING, split
 * where the limit fell: the pale part is what was allowed, the solid part is the
 * overspend, and the solid part's length is the size of the problem.
 *
 * That split is the whole change. A clamped bar draws 40 zł over a 300 zł limit
 * and 3 000 zł over it identically — both full, both red — so the one number the
 * household needed was the one the picture could not show, and the bar stopped
 * being worth looking at exactly when it mattered most.
 */
@Composable
private fun SpendBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        when {
            fraction > 1f -> {
                val limit = limitShare(fraction)
                Box(Modifier.weight(limit).fillMaxHeight().background(color.copy(alpha = 0.32f)))
                Box(Modifier.weight(1f - limit).fillMaxHeight().background(color))
            }
            fraction > 0f -> {
                val filled = fraction.coerceAtMost(1f)
                Box(Modifier.weight(filled).fillMaxHeight().background(color))
                if (filled < 1f) Spacer(Modifier.weight(1f - filled))
            }
        }
    }
}

/**
 * How much of an overspent bar the limit takes up: the reciprocal, since the bar
 * now measures the spending and the limit is a part of it.
 *
 * Bounded at both ends, and not for looks. Compose refuses a weight of zero, so
 * a category one grosz over its limit — or one twenty times over it — would
 * crash the screen rather than draw a bar with a sliver on one end. The bounds
 * mean the reading is approximate at the extremes; the figure beside it is not.
 */
internal fun limitShare(fraction: Float): Float = (1f / fraction).coerceIn(0.04f, 0.96f)

/**
 * Typing a budget figure, on the same keys the add screen uses and laid out the
 * same way: the figure, then the full-width keypad along the bottom, then the
 * button that saves. A dialog squeezed the keys into two thirds of the width and
 * onto a different background, and a number typed in two places should not
 * feel like two different calculators.
 *
 * [header] names what is being set — the category, with its icon, or the month.
 * [footnote] is re-evaluated on every key, so it can react to the figure, and is
 * handed a way to put a figure INTO the keypad — a sheet that can tell you what
 * the right number is should not then make you type it.
 * [savable] gets the evaluated figure and whether a key has been pressed yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetAmountSheet(
    initialMinor: Long,
    label: String,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
    header: @Composable () -> Unit,
    footnote: @Composable (minor: Long, use: (Long) -> Unit) -> Unit,
    savable: (minor: Long, touched: Boolean) -> Boolean = { minor, _ -> minor >= 0 },
    extraAction: (@Composable () -> Unit)? = null,
) {
    var amount by remember { mutableStateOf(AmountInput.ofMinor(initialMinor)) }
    var touched by remember { mutableStateOf(false) }
    val minor = amount.evaluate().toMinor()
    val canSave = savable(minor, touched)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Box(modifier = Modifier.padding(horizontal = 20.dp)) { header() }
            Spacer(Modifier.height(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            AmountDisplay(amount = amount, onClick = {})
            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                footnote(minor) { offered ->
                    amount = AmountInput.ofMinor(offered)
                    // Counts as typed: an offer taken is a figure chosen, and a
                    // new plan has to be savable without pressing a key after it.
                    touched = true
                }
            }
            Spacer(Modifier.height(12.dp))
            Keypad(
                onKey = {
                    amount = amount.press(it)
                    touched = true
                },
                equalsEnabled = amount.hasPendingOperation,
                modifier = Modifier.height(KeypadHeight),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                extraAction?.invoke()
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.budget_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(minor) }, enabled = canSave) {
                    Text(stringResource(R.string.budget_save))
                }
            }
        }
    }
}

/**
 * Typing a forecast: what the month is expected to receive, before it has.
 *
 * This sheet is no longer how a month gets its figures — income does that by
 * arriving — so it is open only when somebody wants to plan ahead of the money,
 * and it is down to one line under the keypad: what is handed out and what is
 * not, against the same pot the card counts ([PlanDraft], which applies the
 * same max rule to the figure still being pressed). Typing less than you have
 * already assigned is a real mistake and goes red while you make it.
 *
 * Three lines lived here for one release — a tappable "what came in", a note
 * that the carry-over is added separately, and this one. The first two were
 * answers to a question the max rule stopped anybody needing to ask: income
 * counts whether or not it is typed, so there is nothing to offer and nothing
 * to warn about double-counting. Typing a figure BELOW what arrived now changes
 * nothing at all, which is the honest behaviour and the reason the field can
 * carry a modest name.
 */
@Composable
private fun EditPlanDialog(
    initialMinor: Long,
    assignedMinor: Long,
    carryOverMinor: Long,
    incomeMinor: Long,
    hasPlan: Boolean,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
    onClear: () -> Unit,
) {
    BudgetAmountSheet(
        initialMinor = initialMinor,
        label = stringResource(R.string.budget_plan_total),
        onDismiss = onDismiss,
        onSave = onSave,
        header = {
            Text(stringResource(R.string.budget_plan_edit), style = MaterialTheme.typography.titleLarge)
        },
        footnote = { minor, _ ->
            val draft = PlanDraft(
                typedMinor = minor,
                assignedMinor = assignedMinor,
                carryOverMinor = carryOverMinor,
                incomeMinor = incomeMinor,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(
                        R.string.budget_plan_assigned_hint,
                        Money.formatWithCurrency(assignedMinor),
                        Money.formatWithCurrency(draft.leftToAssignMinor),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (draft.short) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // Why the figures above stood still while keys were pressed.
                // Only in this state: a month being planned before its pay has
                // landed has no income to lose to, and then every key moves
                // them. See [PlanDraft.belowIncome].
                if (draft.belowIncome) {
                    Text(
                        text = stringResource(
                            R.string.budget_plan_below_income,
                            Money.formatWithCurrency(incomeMinor),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        // A month planned too early — before its income is known — has to be
        // able to go back to having no plan, not to a plan of zero.
        extraAction = if (hasPlan) {
            {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.budget_plan_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun EditBudgetLimitDialog(
    target: EditTarget,
    onDismiss: () -> Unit,
    onSave: (Long, Boolean) -> Unit,
    onClear: () -> Unit,
) {
    val hasExisting = target.limitMinor != null
    // Off every time the sheet opens. Carrying a limit into every month to come
    // is the bigger claim of the two, and a box that remembered being ticked
    // would make the bigger claim on behalf of somebody who only came to fix
    // one month.
    var alsoFuture by remember(target.categoryId) { mutableStateOf(false) }
    val tint = Palette.colorFor(target.color, target.categoryId)

    BudgetAmountSheet(
        initialMinor = target.limitMinor ?: 0L,
        label = stringResource(if (hasExisting) R.string.budget_edit_limit else R.string.budget_set_limit),
        onDismiss = onDismiss,
        onSave = { minor -> onSave(minor, alsoFuture) },
        // The same circle the budget row shows, so the sheet plainly belongs
        // to the row that opened it.
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = Palette.icon(target.icon), contentDescription = null, tint = tint)
                }
                Spacer(Modifier.width(12.dp))
                Text(target.name, style = MaterialTheme.typography.titleLarge)
            }
        },
        // It used to say, in small grey text, that the limit would also apply in
        // later months. That was true and it was not a choice — which is the
        // wrong shape for something this consequential. It is a choice now, and
        // the unticked box is the promise that only this month changes.
        footnote = { _, _ ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = alsoFuture,
                        role = Role.Checkbox,
                        onValueChange = { alsoFuture = it },
                    ),
            ) {
                Checkbox(checked = alsoFuture, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.budget_apply_forward),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        // Zero is a limit like any other, so "shows 0" cannot mean "nothing
        // typed": a new limit is savable once a key has been pressed, an existing
        // one from the start. Negative is still refused — clearing a limit is the
        // Remove button, not a minus sign.
        savable = { minor, touched -> minor >= 0 && (hasExisting || touched) },
        extraAction = if (hasExisting) {
            {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.budget_remove_limit), color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun AddCategoryPickerDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onPick: (CategoryEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.budget_add_category)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(categories, key = { it.id }) { category ->
                    val tint = Palette.colorFor(category.color, category.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(category) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(tint.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(imageVector = Palette.icon(category.icon), contentDescription = null, tint = tint)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(category.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.budget_cancel)) }
        },
    )
}
