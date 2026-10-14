package com.monyx.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.Currencies
import com.monyx.data.Currency

/**
 * Which currencies this phone is willing to be asked about.
 *
 * A switch per row, which is what this screen is: a column of independent
 * yes/no answers, read down once and rarely returned to. The list is the nine
 * with NBP rates plus whatever the household has typed in, so every row looks
 * like every other row and the spacing between them is one number in one place.
 *
 * Two kinds of row cannot be switched off, and the switch simply sits there
 * greyed rather than explaining itself:
 *
 *  - **złoty**, which every total is in. See ADR 0004 and 0022.
 *  - **any currency an account holds**, because that is a fact already written
 *    down. Switch the account to something else first, and this row unlocks.
 *
 * It used to say both of those in a line of small print under the row, which
 * was two of the three texts on the screen and told a household something it
 * already knows about its own accounts. A greyed switch says the same thing in
 * no words, and keeps every row the same height — which is the other half of
 * why the prose is gone.
 *
 * Anything can be added, not only the nine: a code and the text to print after
 * a figure. An added currency is on this list because somebody typed it, so
 * switching it off is the end of it — there is nothing built in to fall back to,
 * and the row goes with the entry. The nine are always here whether they are on
 * or off.
 *
 * Nothing on this screen changes a stored row, a balance or a total. Switching
 * the krona off does not make a krona row unreadable; it stops the krona being
 * one more thing to scroll past on the way to the euro.
 */
@Composable
fun CurrenciesSection(
    /** The codes the household's accounts are denominated in. */
    inUse: Set<String>,
    /** The stored entries, or null when nobody has chosen. See [Currencies]. */
    chosen: Set<String>?,
    onSetShown: (String, Boolean) -> Unit,
    onAdd: (String, String) -> Unit,
) {
    val rows = Currencies.listed(chosen, inUse)
    val offered = Currencies.offered(chosen, inUse).map { it.code }.toSet()
    var adding by remember { mutableStateOf(false) }

    SectionCard(
        title = stringResource(R.string.settings_currencies),
        icon = Icons.Filled.Payments,
        trailing = {
            IconButton(onClick = { adding = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.settings_currency_add),
                )
            }
        },
    ) {
        Text(
            stringResource(R.string.settings_currencies_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        rows.forEach { currency ->
            val locked = Currencies.locked(currency, inUse)
            val on = currency.code in offered
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = on,
                        enabled = !locked,
                        role = Role.Switch,
                        onValueChange = { onSetShown(currency.code, it) },
                    )
                    .padding(vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = currency.code,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    // The symbol, where it is not the code again. It is what the
                    // row on the ledger will actually read.
                    if (currency.suffix != currency.code) {
                        Text(
                            text = currency.suffix,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = on, enabled = !locked, onCheckedChange = null)
            }
        }
    }

    if (adding) {
        AddCurrencyDialog(
            already = rows.map { it.code }.toSet(),
            onAdd = { code, suffix ->
                onAdd(code, suffix)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

/**
 * Typing in a currency: three capitals, and whatever should follow a figure in
 * it.
 *
 * Two fields and a line of advice, and the symbol field is optional: left empty,
 * the code itself goes after the figure, which is what the krona and the franc
 * do anyway. The nine with rates are not offered here — they are already rows
 * on the list behind this dialog, with a switch each, so a shortcut to them
 * would be a second way to do the same thing that leaves the first one looking
 * broken.
 *
 * What the dialog does say is what happens when a code has no NBP rate, because
 * that is the part nobody can guess: the currency keeps its own figures
 * perfectly and contributes nothing to a złoty total.
 */
@Composable
private fun AddCurrencyDialog(
    already: Set<String>,
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    val clean = code.trim().uppercase()
    val shaped = Currency.isValidCode(clean)
    val duplicate = clean in already

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_currency_add)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.take(3) },
                        label = { Text(stringResource(R.string.settings_currency_code)) },
                        singleLine = true,
                        isError = code.isNotBlank() && (!shaped || duplicate),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = suffix,
                        onValueChange = { suffix = it.take(4) },
                        label = { Text(stringResource(R.string.settings_currency_symbol)) },
                        // Empty is a real answer: the code goes after the
                        // figure, the way SEK and CHF already do.
                        placeholder = { Text(clean.ifBlank { "฿" }) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        when {
                            duplicate -> R.string.settings_currency_exists
                            clean.isNotBlank() && !shaped -> R.string.settings_currency_bad_code
                            // What a currency outside the NBP list does, said
                            // before it is added rather than discovered later on
                            // a summary sheet that does not add up.
                            shaped && clean !in Currency.RATED -> R.string.settings_currency_no_rate
                            else -> R.string.settings_currency_code_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (duplicate || (clean.isNotBlank() && !shaped)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(clean, suffix) },
                enabled = shaped && !duplicate,
            ) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}
