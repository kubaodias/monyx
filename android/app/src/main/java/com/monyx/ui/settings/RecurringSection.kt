package com.monyx.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Repeat
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.Dates
import com.monyx.data.Money
import com.monyx.data.Recurrence
import com.monyx.data.RecurringRuleListItem
import com.monyx.ui.theme.Palette
import java.time.LocalDate

/**
 * The fixed shape of a household's month: rent, the phone bill, the loan, the
 * salary that pays for them.
 *
 * It lives in Settings rather than as a sixth tab, and that is a claim about how
 * often it is touched. A repeating transaction is set up roughly as often as an
 * account is opened — twice a year, not twice a day. The keypad stays the thing
 * the app opens on.
 *
 * The list is here; setting one up is [RecurringEditor], which is a whole
 * screen and therefore belongs to the screen, not to this card — [onOpen] hands
 * it up. Null means a new rule.
 */
@Composable
fun RecurringSection(
    rules: List<RecurringRuleListItem>,
    hasAccounts: Boolean,
    onOpen: (RecurringRuleListItem?) -> Unit,
    onDelete: (String) -> Unit,
) {
    var deleting by remember { mutableStateOf<RecurringRuleListItem?>(null) }

    SectionCard(
        title = stringResource(R.string.settings_recurring),
        icon = Icons.Filled.Repeat,
        trailing = {
            IconButton(onClick = { onOpen(null) }, enabled = hasAccounts) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.settings_add_recurring))
            }
        },
    ) {
        if (rules.isEmpty()) {
            Text(
                stringResource(
                    if (!hasAccounts) R.string.recurring_needs_account
                    else R.string.settings_no_recurring,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rules.forEach { rule ->
                RuleRow(
                    rule = rule,
                    onEdit = { onOpen(rule) },
                    onDelete = { deleting = rule },
                )
            }
        }
    }

    deleting?.let { rule ->
        ConfirmDialog(
            title = stringResource(R.string.settings_delete),
            // The count is in the message on purpose. Stopping a rule and
            // erasing what it did are two different things, and the only moment
            // that distinction matters is the moment before it is stopped. It is
            // NOT on the row: what a rule has produced is history, and the list
            // is asked "is this still going to happen".
            //
            // Plurals, not a format argument, and a separate string for none.
            // "The 1 transactions it has already added" is what a bare %1$d
            // gives you, and Polish needs three forms rather than two.
            message = if (rule.generatedCount == 0) {
                stringResource(R.string.recurring_delete_confirm_none)
            } else {
                pluralStringResource(
                    R.plurals.recurring_delete_confirm,
                    rule.generatedCount,
                    rule.generatedCount,
                )
            },
            confirmLabel = stringResource(R.string.settings_delete),
            onConfirm = {
                onDelete(rule.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun RuleRow(
    rule: RecurringRuleListItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val color = Palette.colorFor(rule.categoryColor, rule.categoryId ?: rule.id)
    val anchor = runCatching { LocalDate.parse(rule.startsOn) }.getOrNull()
    val next = anchor?.let {
        Recurrence.nextOccurrence(
            freq = rule.freq,
            anchor = it,
            endsOn = rule.endsOn?.let { end -> runCatching { LocalDate.parse(end) }.getOrNull() },
            after = Dates.today().minusDays(1),
        )
    }

    // The row itself opens the editor, so only one icon button competes with the
    // text for width. With two, "Every month · Next on 30 September" wrapped
    // mid-phrase, and it is the line that answers the only question anyone has
    // about a rule they set up months ago. Tapping a row to open it is how the
    // transaction list already behaves.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Palette.icon(rule.categoryIcon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                rule.note?.takeIf { it.isNotBlank() }
                    ?: rule.categoryName
                    ?: rule.accountName.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                labelForFreq(rule.freq),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // "Next on ..." answers the only question anyone has about a rule
            // they set up in March: is it still going to happen.
            Text(
                next?.let {
                    stringResource(R.string.recurring_next, Dates.shortDayLabel(it.toString()))
                } ?: stringResource(R.string.recurring_finished),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            Money.formatSigned(rule.amountMinor, rule.kind),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (rule.kind == "income") {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_delete))
        }
    }
}

/** Everything a rule needs that the caller does not already know. */
data class RuleDraft(
    val kind: String,
    val amountMinor: Long,
    val accountId: String,
    val categoryId: String,
    val note: String?,
    val freq: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
)
