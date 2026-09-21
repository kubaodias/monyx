package com.monyx.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    onOpenCategoryTransactions: (String, String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as MonyxApp
    val viewModel: BudgetViewModel = viewModel(factory = BudgetViewModel.factory(app.repository, app.selectedMonth))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val period by viewModel.period.collectAsStateWithLifecycle()
    val budgetUsage by viewModel.budgetUsage.collectAsStateWithLifecycle()
    val availableToAdd by viewModel.availableToAdd.collectAsStateWithLifecycle()

    val plan by viewModel.plan.collectAsStateWithLifecycle()

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
                items(budgetUsage, key = { it.categoryId }) { usage ->
                    BudgetRow(
                        usage = usage,
                        onEdit = {
                            editTarget = EditTarget(usage.categoryId, usage.name, usage.icon, usage.color, usage.limitMinor)
                        },
                        onOpenTransactions = { onOpenCategoryTransactions(usage.categoryId, period) },
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

    editTarget?.let { target ->
        EditBudgetLimitDialog(
            target = target,
            onDismiss = { editTarget = null },
            onSave = { minor ->
                scope.launch {
                    viewModel.setBudget(target.categoryId, minor)
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
            initialMinor = if (plan.hasPlan) plan.plannedMinor else planSuggestionMinor,
            assignedMinor = plan.assignedMinor,
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
 * The month on one card: what there is, what has been handed out, what is left
 * to hand out — and, underneath, how much of it has actually gone.
 *
 * "Left to assign" is the headline because it is the number that tells you
 * whether the plan is finished. Zero is the goal, not a warning.
 */
@Composable
private fun PlanCard(plan: PlanState, onEditPlan: () -> Unit) {
    val leftColor = when {
        plan.overAssigned -> MaterialTheme.colorScheme.error
        plan.hasPlan && plan.leftToAssignMinor == 0L -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEditPlan),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.budget_plan_to_assign),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        // An em dash, not a figure, while the month is unplanned.
                        text = plan.leftToAssignMinor?.let { Money.formatWithCurrency(it) } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = leftColor,
                    )
                }
                IconButton(onClick = onEditPlan) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.budget_plan_edit),
                    )
                }
            }

            if (!plan.hasPlan) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.budget_plan_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Spacer(Modifier.height(12.dp))
            // How much of the plan has been handed out to categories. Capped at
            // 1f: over-assigning is shown by the red headline, not by a bar
            // drawing past its own end.
            val assignedFraction = if (plan.plannedMinor > 0) {
                (plan.assignedMinor.toFloat() / plan.plannedMinor.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { assignedFraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (plan.overAssigned) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )

            Spacer(Modifier.height(12.dp))
            PlanFigure(
                label = stringResource(R.string.budget_plan_total),
                value = Money.formatWithCurrency(plan.plannedMinor),
            )
            PlanFigure(
                label = stringResource(R.string.budget_plan_assigned),
                value = Money.formatWithCurrency(plan.assignedMinor),
            )
            if (plan.hasCarryOver) {
                PlanFigure(
                    label = stringResource(R.string.budget_plan_carry_over),
                    value = (if (plan.carryOverMinor > 0) "+" else "") +
                        Money.formatWithCurrency(plan.carryOverMinor),
                    valueColor = if (plan.carryOverMinor < 0) MaterialTheme.colorScheme.error else null,
                )
            }
            PlanFigure(
                label = stringResource(R.string.budget_plan_spent),
                value = Money.formatWithCurrency(plan.spentMinor),
            )
            val leftToSpend = plan.leftToSpendMinor ?: 0
            PlanFigure(
                label = stringResource(R.string.budget_plan_left_to_spend),
                value = Money.formatWithCurrency(leftToSpend),
                emphasis = true,
                valueColor = if (leftToSpend < 0) MaterialTheme.colorScheme.error else null,
            )
        }
    }
}

@Composable
private fun PlanFigure(
    label: String,
    value: String,
    emphasis: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // The label gives way, never the figure: a long carry-over label
        // ellipsises instead of wrapping the amount onto a second line.
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp),
        )
        Text(
            text = value,
            softWrap = false,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
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
    val barColor = when {
        pct >= 1f -> MaterialTheme.colorScheme.error
        pct >= 0.8f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val over = usage.spentMinor > usage.limitMinor
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
                LinearProgressIndicator(
                    progress = { pct.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = barColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.budget_spent_of, Money.format(usage.spentMinor), Money.format(usage.limitMinor)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (over) {
                        stringResource(R.string.budget_over, Money.format(usage.spentMinor - usage.limitMinor))
                    } else {
                        stringResource(R.string.budget_left, Money.format(usage.limitMinor - usage.spentMinor))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
 * Typing a budget figure, on the same keys the add screen uses and laid out the
 * same way: the figure, then the full-width keypad along the bottom, then the
 * button that saves. A dialog squeezed the keys into two thirds of the width and
 * onto a different background, and a number typed in two places should not
 * feel like two different calculators.
 *
 * [header] names what is being set — the category, with its icon, or the month.
 * [footnote] is re-evaluated on every key, so it can react to the figure.
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
    footnote: @Composable (minor: Long) -> Unit,
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
            Box(modifier = Modifier.padding(horizontal = 20.dp)) { footnote(minor) }
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
 * Setting the month's total. The already-assigned figure is shown alongside so
 * the number being typed can be judged against it without leaving the sheet —
 * typing less than you have already handed out is a real mistake, and it should
 * be visible while you make it, not after.
 */
@Composable
private fun EditPlanDialog(
    initialMinor: Long,
    assignedMinor: Long,
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
        footnote = { minor ->
            Text(
                text = stringResource(
                    R.string.budget_plan_assigned_hint,
                    Money.formatWithCurrency(assignedMinor),
                    Money.formatWithCurrency(minor - assignedMinor),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (minor < assignedMinor) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
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
    onSave: (Long) -> Unit,
    onClear: () -> Unit,
) {
    val hasExisting = target.limitMinor != null
    val tint = Palette.colorFor(target.color, target.categoryId)

    BudgetAmountSheet(
        initialMinor = target.limitMinor ?: 0L,
        label = stringResource(if (hasExisting) R.string.budget_edit_limit else R.string.budget_set_limit),
        onDismiss = onDismiss,
        onSave = onSave,
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
        footnote = {
            Text(
                text = stringResource(R.string.budget_carries_forward),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
